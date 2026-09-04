import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'

const runtimeContract = JSON.parse(
  readFileSync(new URL('../contracts/zixuan-runtime.json', import.meta.url), 'utf8'),
)
const productRoutes = runtimeContract.routes

const string = (description, extra = {}) => ({ type: 'string', description, ...extra })
const integer = (description, extra = {}) => ({ type: 'integer', description, ...extra })
const object = (description, properties = {}, required = []) => ({
  type: 'object', description, properties, ...(required.length ? { required } : {})
})
const response = (description, schema = { type: 'object', description: '响应数据' }) => ({
  description,
  content: { 'application/json': { schema } }
})
const bearer = {
  bearerAuth: []
}
const security = [{ bearerAuth: [] }]

const python = {
  openapi: '3.0.3',
  info: {
    title: 'Zixuan Python Runtime API',
    version: '2026.08.23',
    description: 'zixuan-server Python HTTP、Playground、视觉、设备控制和记忆运行时接口。'
  },
  servers: [{ url: 'http://localhost:8003', description: 'Python runtime HTTP server' }],
  components: {
    securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer', description: '服务端 auth_key 或 manager-api.secret' } },
    schemas: {
      Error: object('错误响应', { success: { type: 'boolean', description: '是否成功' }, message: string('错误消息') }),
      MemoryOperation: object('记忆操作结果', { success: { type: 'boolean', description: '是否成功' }, summary: string('记忆存储摘要') }),
      PlaygroundInput: object('Playground 输入', {
        session_id: string('会话 ID'), sequence: integer('客户端递增序号', { minimum: 1 }),
        kind: string('输入类型', { enum: ['text', 'audio', 'tts', 'vision', 'activity'] }),
        text: string('文本或 TTS 内容'), audio_ref: string('音频引用'), image_ref: string('图片引用'), activity: object('活动传感器数据')
      }, ['session_id', 'sequence', 'kind']),
      PlaygroundEvent: object('Playground 事件', {
        session_id: string('会话 ID'), sequence: integer('事件序号'), capability: string('能力名称'), stage: string('处理阶段'),
        status: string('事件状态', { enum: ['started', 'completed', 'failed'] }), started_at: integer('开始时间戳，毫秒'),
        finished_at: integer('结束时间戳，毫秒'), duration_ms: integer('耗时，毫秒'), input_summary: string('输入摘要'),
        output_summary: string('输出摘要'), error: string('错误信息'), details: object('结构化详情')
      })
    }
  },
  paths: {}
}

const addPython = (path, method, summary, description, operation = {}) => {
  python.paths[path] ??= {}
  python.paths[path][method] = { summary, description, operationId: `python_${method}_${path.replace(/[^a-zA-Z0-9]+/g, '_')}`, ...operation }
}
const jsonRequest = (schema, required = true) => ({ required, content: { 'application/json': { schema } } })

addPython(productRoutes.ota, 'get', 'OTA 健康检查', '检查 OTA 服务并返回设备应连接的 WebSocket 地址。', { responses: { 200: { description: '纯文本健康状态' } } })
addPython(productRoutes.ota, 'post', '设备 OTA 配置', '设备上报身份和固件信息，获取 MQTT 或 WebSocket 连接配置及可用固件升级地址。', {
  parameters: [
    { name: 'device-id', in: 'header', required: true, description: '设备 ID 或 MAC 地址', schema: string('设备 ID') },
    { name: 'client-id', in: 'header', required: true, description: '客户端 ID', schema: string('客户端 ID') },
    { name: 'device-model', in: 'header', description: '设备型号', schema: string('设备型号') },
    { name: 'device-version', in: 'header', description: '当前固件版本', schema: string('当前固件版本') }
  ],
  requestBody: jsonRequest(object('OTA 请求体', { application: object('应用信息', { version: string('当前应用版本') }), board: object('板卡信息', { type: string('板卡型号') }), model: string('设备型号') }), false),
  responses: { 200: response('OTA 配置', object('OTA 响应', {
    server_time: object('服务器时间', { timestamp: integer('服务器时间戳，毫秒'), timezone_offset: integer('时区偏移，分钟') }),
    firmware: object('固件升级信息', { version: string('固件版本'), url: string('固件下载地址') }),
    mqtt: object('MQTT 连接配置', { endpoint: string('MQTT 地址'), client_id: string('MQTT 客户端 ID'), username: string('用户名'), password: string('密码'), publish_topic: string('上行 topic'), subscribe_topic: string('下行 topic') }),
    websocket: object('WebSocket 连接配置', { url: string('WebSocket 地址'), token: string('认证令牌') })
  })) }
})
addPython(`${productRoutes.ota}download/{filename}`, 'get', '下载固件', '下载 data/bin 目录下经过文件名校验的固件文件。', {
  parameters: [{ name: 'filename', in: 'path', required: true, description: '固件文件名，仅允许安全的 .bin 文件名', schema: string('固件文件名') }],
  responses: { 200: { description: '二进制固件文件', content: { 'application/octet-stream': { schema: { type: 'string', format: 'binary', description: '固件二进制' } } } } }
})
addPython('/mcp/vision/explain', 'get', '视觉服务健康检查', '检查视觉解释服务配置。', { responses: { 200: { description: '纯文本健康状态' } } })
addPython('/mcp/vision/explain', 'post', '图片视觉解释', '上传问题和图片，调用配置的视觉模型返回解释结果。', {
  security,
  parameters: [
    { name: 'Device-Id', in: 'header', required: true, description: '设备 ID', schema: string('设备 ID') },
    { name: 'Client-Id', in: 'header', required: true, description: '客户端 ID', schema: string('客户端 ID') },
    { name: 'Authorization', in: 'header', required: true, description: 'Bearer 认证令牌', schema: string('Bearer 令牌') }
  ],
  requestBody: { required: true, content: { 'multipart/form-data': { schema: object('视觉请求', { question: string('用户问题'), image: { type: 'string', format: 'binary', description: '图片，最大 5 MB，支持 JPEG/PNG/GIF/BMP/TIFF/WEBP' } }, ['question', 'image']) } } },
  responses: { 200: response('视觉解释结果', object('视觉响应', { success: { type: 'boolean', description: '是否成功' }, action: string('动作类型'), response: string('模型解释文本') })), 401: response('认证失败', { $ref: '#/components/schemas/Error' }) }
})
addPython('/internal/companion-memory', 'get', '读取陪伴记忆', '按设备身份读取记忆条目。', { security, parameters: [{ name: 'mac_address', in: 'query', required: true, description: '设备 MAC 地址', schema: string('设备 MAC 地址') }], responses: { 200: response('记忆条目', object('记忆列表', { items: { type: 'array', description: '记忆条目列表', items: object('记忆条目', { memory_id: string('记忆 ID'), content: string('记忆内容'), source_device_id: string('来源设备 ID'), source_profile_id: string('来源角色 ID') }) } })) } })
addPython('/internal/companion-memory', 'put', '更新陪伴记忆', '更新指定设备的单条记忆。', { security, requestBody: jsonRequest(object('记忆更新', { mac_address: string('设备 MAC 地址'), memory_id: string('记忆 ID'), content: string('记忆内容，最多 4000 字符') }, ['mac_address', 'memory_id', 'content'])), responses: { 200: response('操作结果', { $ref: '#/components/schemas/MemoryOperation' }) } })
addPython('/internal/companion-memory', 'delete', '删除陪伴记忆', '删除指定记忆，未提供 memory_id 时清空设备全部记忆。', { security, requestBody: jsonRequest(object('记忆删除', { mac_address: string('设备 MAC 地址'), memory_id: string('可选的记忆 ID') }, ['mac_address'])), responses: { 200: response('操作结果', { $ref: '#/components/schemas/MemoryOperation' }) } })
addPython('/internal/companion-memory/migration', 'post', '迁移陪伴记忆', '在两个设备之间合并或覆盖迁移记忆。', { security, requestBody: jsonRequest(object('记忆迁移', { source_mac_address: string('源设备 MAC 地址'), target_mac_address: string('目标设备 MAC 地址'), mode: string('迁移模式', { enum: ['merge', 'overwrite'] }) }, ['source_mac_address', 'target_mac_address', 'mode'])), responses: { 200: response('迁移结果', object('迁移结果', { success: { type: 'boolean', description: '是否成功' }, mode: string('迁移模式'), source_count: integer('源记忆数量'), target_count: integer('目标原有数量'), imported_count: integer('导入数量'), skipped_count: integer('跳过数量'), recovered: { type: 'boolean', description: '失败时是否已恢复' } })) } })
addPython('/internal/device-control', 'post', '调用设备 MCP 工具', '通过已认证的设备 WebSocket 连接执行 tools/list 或 tools/call。', { security, requestBody: jsonRequest(object('设备控制请求', { mac_address: string('设备 MAC 地址'), method: string('MCP 方法', { enum: ['tools/list', 'tools/call'] }), params: object('MCP 参数') }, ['mac_address', 'method'])), responses: { 200: response('设备工具结果', object('设备工具响应', { success: { type: 'boolean', description: '是否成功' }, data: object('工具列表或调用结果'), error: string('错误信息') })) } })
addPython('/internal/capabilities/plugin-executors', 'get', '读取 Plugin 执行器', '返回当前 Python 服务已登记的 Plugin 执行器及输入 Schema。', { security, responses: { 200: response('执行器列表', object('执行器列表响应', { executors: { type: 'array', description: '执行器列表', items: object('执行器', { name: string('工具名称'), description: string('工具说明'), inputSchema: object('输入 JSON Schema') }) } })) } })
addPython('/internal/capabilities/mcp-test', 'post', '测试 MCP 配置', '校验服务端批准的 MCP 配置并返回发现的工具。', { security, requestBody: jsonRequest(object('MCP 测试请求', { transport: string('传输协议', { enum: ['STDIO', 'SSE', 'STREAMABLE_HTTP', 'HTTP'] }), connectionConfig: object('连接配置'), approvedCommandTemplate: object('批准的 STDIO 模板') }, ['transport', 'connectionConfig'])), responses: { 200: response('MCP 工具列表', object('MCP 测试响应', { success: { type: 'boolean', description: '是否成功' }, tools: { type: 'array', description: '发现的工具', items: object('MCP 工具', { name: string('工具名称'), inputSchema: object('输入 Schema') }) }, errorClass: string('错误类型') })) } })
addPython('/internal/wake-word-assets', 'post', '生成唤醒词资源', '根据设备、唤醒词和槽位参数生成布局 2 唤醒词二进制资源。', { security, requestBody: jsonRequest(object('唤醒词资源请求', { device_id: string('设备 ID'), word: string('唤醒词'), version: integer('资源版本', { minimum: 1 }), chip: string('芯片型号'), slot_size: integer('槽位大小', { minimum: 1 }) }, ['device_id', 'word', 'version', 'chip', 'slot_size'])), responses: { 200: { description: '唤醒词二进制资源', headers: { 'X-Wake-Word-Sha256': { description: '资源 SHA-256', schema: string('SHA-256') }, 'X-Wake-Word-Size': { description: '资源大小', schema: integer('字节数') }, 'X-Wake-Word-Version': { description: '资源版本', schema: integer('版本') } }, content: { 'application/octet-stream': { schema: { type: 'string', format: 'binary', description: '唤醒词资源' } } } } } })
addPython(productRoutes.playground, 'post', '创建 Playground 会话', '创建虚拟设备 Playground 会话。需要 Bearer auth_key。', { security, requestBody: jsonRequest(object('会话创建请求', { session_id: string('会话 ID'), snapshot_version: integer('配置快照版本'), config: object('运行配置'), virtual_device: object('虚拟设备', { width: integer('屏幕宽度'), height: integer('屏幕高度'), depth: integer('色深'), orientation: string('屏幕方向'), screen: { type: 'boolean', description: '是否有屏幕' }, camera: { type: 'boolean', description: '是否有摄像头' }, microphone: { type: 'boolean', description: '是否有麦克风' }, activity_sensor: { type: 'boolean', description: '是否有活动传感器' } }), runtime_models: object('运行模型配置') }, ['session_id'])), responses: { 200: response('会话创建结果', object('会话创建结果', { session_id: string('会话 ID') })) } })
addPython(`${productRoutes.playground}/{session_id}/inputs`, 'post', '提交 Playground 输入', '向会话提交文本、音频、TTS、视觉或活动输入并返回生成事件。', { security, parameters: [{ name: 'session_id', in: 'path', required: true, description: '会话 ID', schema: string('会话 ID') }], requestBody: jsonRequest({ $ref: '#/components/schemas/PlaygroundInput' }), responses: { 200: response('生成事件', object('事件响应', { events: { type: 'array', description: '事件列表', items: { $ref: '#/components/schemas/PlaygroundEvent' } } })) } })
addPython(`${productRoutes.playground}/{session_id}/events`, 'get', '读取 Playground 事件', '按游标读取会话事件并以 Server-Sent Events 返回。', { security, parameters: [{ name: 'session_id', in: 'path', required: true, description: '会话 ID', schema: string('会话 ID') }, { name: 'after', in: 'query', description: '只返回序号大于该值的事件', schema: integer('事件游标', { default: 0 }) }], responses: { 200: { description: 'SSE 事件流', content: { 'text/event-stream': { schema: { type: 'string', description: 'id、event、data 组成的事件流' } } } } } })
addPython(`${productRoutes.playground}/{session_id}`, 'delete', '关闭 Playground 会话', '释放指定 Playground 会话。', { security, parameters: [{ name: 'session_id', in: 'path', required: true, description: '会话 ID', schema: string('会话 ID') }], responses: { 200: response('关闭结果', object('关闭结果', { ok: { type: 'boolean', description: '是否关闭成功' } })) } })
addPython('/api/v1/conversations/{conversation_id}/stream', 'get', '读取公开会话事件', '按会话 ID 建立 Server-Sent Events 流。', {
  security,
  parameters: [{ name: 'conversation_id', in: 'path', required: true, description: '公开会话 ID', schema: string('公开会话 ID') }],
  responses: { 200: { description: '公开会话 SSE 事件流', content: { 'text/event-stream': { schema: { type: 'string', description: '会话事件流' } } } } },
})

const gateway = {
  openapi: '3.0.3',
  info: { title: 'AI-Live MQTT Gateway API', version: '2026.08.23', description: 'mqtt-gateway 设备控制、设备状态和通话管理 HTTP 接口。' },
  servers: [{ url: 'http://localhost:8007', description: 'MQTT gateway admin API' }],
  components: { securitySchemes: { dailyBearer: { type: 'http', scheme: 'bearer', description: 'SHA-256(yyyy-MM-dd + MQTT_SIGNATURE_KEY)' } }, schemas: {
    GatewayError: object('网关错误', { error: string('错误消息') }),
    DeviceStatus: object('设备状态', { isAlive: { type: 'boolean', description: '连接是否存活' }, exists: { type: 'boolean', description: '连接是否存在' } })
  } },
  paths: {},
  'x-mqtt-topics': {
    description: '网关同时承载 MQTT 3.0/3.1.1 TCP 协议。设备上行 publish_topic 默认是 device-server，下行 subscribe_topic 默认是 devices/p2p/{mac}。',
    topics: [
      { name: 'device-server', direction: 'device -> gateway', description: '设备上行 MCP、状态和音频桥接消息' },
      { name: 'devices/p2p/{mac}', direction: 'gateway -> device', description: '网关向指定设备下发 MCP 或通话唤醒消息' }
    ]
  }
}
const addGateway = (path, summary, description, schema, responseSchema) => {
  gateway.paths[path] = { post: { summary, description, operationId: `gateway_post_${path.replace(/[^a-zA-Z0-9]+/g, '_')}`, security: [{ dailyBearer: [] }], requestBody: jsonRequest(schema), responses: { 200: response('请求成功', responseSchema), 400: response('参数错误', { $ref: '#/components/schemas/GatewayError' }), 401: response('认证失败', { $ref: '#/components/schemas/GatewayError' }), 500: response('服务端错误', { $ref: '#/components/schemas/GatewayError' }) } } }
}
addGateway('/api/commands/{clientId}', '向设备下发 MCP 指令', '向在线设备发送 tools/list 或 tools/call 请求，视觉调用超时为 60 秒。', object('设备指令', { type: string('指令类型', { enum: ['mcp'] }), payload: object('MCP 请求', { jsonrpc: string('JSON-RPC 版本'), id: integer('请求 ID'), method: string('MCP 方法'), params: object('MCP 参数') }) }, ['type', 'payload']), object('指令响应', { success: { type: 'boolean', description: '是否成功' }, data: object('设备返回数据'), error: string('错误消息') }))
gateway.paths['/api/commands/{clientId}'].post.parameters = [{ name: 'clientId', in: 'path', required: true, description: '设备连接 ID', schema: string('设备连接 ID') }]
addGateway('/api/devices/status', '查询设备在线状态', '批量查询设备连接是否存在及是否存活。', object('设备状态查询', { clientIds: { type: 'array', description: '设备连接 ID 列表', items: string('设备连接 ID') } }, ['clientIds']), { type: 'object', description: '以设备连接 ID 为键的状态映射', additionalProperties: { $ref: '#/components/schemas/DeviceStatus' } })
addGateway('/api/call/request', '发起设备通话', '检查被叫在线状态，建立或挂起设备间通话，并在需要时远程唤醒被叫设备。', object('通话请求', { caller_mac: string('主叫设备 MAC 地址'), target_mac: string('被叫设备 MAC 地址'), caller_nickname: string('主叫昵称') }, ['caller_mac', 'target_mac']), object('通话请求结果', { status: string('通话状态', { enum: ['offline', 'pending', 'bridged', 'error'] }), message: string('状态消息') }))
addGateway('/api/call/accept', '接听设备通话', '让被叫设备加入等待中的通话并建立桥接。', object('接听请求', { mac: string('被叫设备 MAC 地址') }, ['mac']), object('接听结果', { status: string('通话状态', { enum: ['bridged', 'no_pending', 'caller_gone', 'error'] }), peerMac: string('对端设备 MAC 地址'), message: string('状态消息') }))

mkdirSync('docs/api', { recursive: true })
writeFileSync('docs/api/zixuan-server-openapi.json', JSON.stringify(python, null, 2) + '\n')
writeFileSync('docs/api/mqtt-gateway-openapi.json', JSON.stringify(gateway, null, 2) + '\n')
const protocolDoc = [
  '# AI-Live Runtime Protocols', '', '## Python WebSocket', '',
  `地址为 \`ws://<host>:8000${productRoutes.websocket}\`。客户端需要发送 \`device-id\`、\`client-id\` 和 Bearer 认证头。连接后的业务帧是设备协议 JSON/二进制消息，认证失败会关闭连接。`, '',
  '## MQTT', '',
  '网关支持 MQTT 3.0 和 3.1.1。设备上行 topic 默认为 `device-server`，网关下行 topic 默认为 `devices/p2p/{mac}`。管理 HTTP API 的 Bearer 令牌为当天日期和 `MQTT_SIGNATURE_KEY` 拼接后 SHA-256 的十六进制结果。', '',
  '设备 OTA 响应中的 `mqtt.client_id`、`mqtt.username`、`mqtt.password` 和 topic 是动态生成值，不能写死在客户端。'
].join('\n')
writeFileSync('docs/api/ai-live-runtime-protocols.md', protocolDoc + '\n')
console.log(`generated python_paths=${Object.keys(python.paths).length} gateway_paths=${Object.keys(gateway.paths).length}`)
