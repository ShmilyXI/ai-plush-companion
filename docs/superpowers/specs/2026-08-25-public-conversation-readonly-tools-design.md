# 公共会话只读工具调用设计

## 目标

让公共文字和语音会话在角色已发布版本明确绑定 Skill 时，真实调用天气和新闻工具，再把工具结果交给 LLM 组织回复并生成 TTS。没有绑定的角色继续不能使用工具。

首期只开放两个服务端只读 Plugin：`get_weather` 和 `get_news_from_newsnow`。设备控制、设备 MCP、角色 MCP、任意外部 URL、任意代码执行和客户端自带工具均不进入公共会话。

## 权限和配置

manager-api 读取 Agent active version 的已发布 Skill 绑定。Skill manifest 中声明的工具必须属于公共工具白名单，且在服务端 Plugin 注册表中存在可执行函数和完整函数 schema。Java runtime bundle 传递经过校验的 Skill、工具名称、函数 schema、默认参数、Skill 版本和工具类型。未通过白名单、发布状态、版本状态或 schema 校验的工具从 bundle 中移除，并写入脱敏审计事件。

公共会话的工具集合按 `conversation_id` 固定。Python 不从本地默认配置补工具，不接受客户端修改工具集合，不把设备工具或 MCP 工具混入公共 bundle。工具执行使用独立的公共运行时适配器，只能调用服务端 Plugin，并继承当前会话的用户、角色和请求上下文。

## 运行流程

公共会话打开时解析 bundle 中的工具定义，并把当前 Skill 允许的工具 schema 传给 LLM provider。用户提出天气或新闻问题时，LLM 返回工具调用，Python 先发送 `tool.started`，校验工具名称和参数，再执行对应 Plugin。成功后发送脱敏的 `tool.completed` 元数据和内部工具结果，工具结果只进入同一轮 LLM 上下文，不直接作为最终回复。LLM 继续生成文字增量，随后按现有流程发送 `tts.audio` 和 `turn.completed`。

工具调用必须属于当前 `turn_id`，每轮限制工具调用次数和总执行时间。一次轮次内允许多个只读工具调用，但不得超过固定上限。工具超时、参数错误、网络失败或结果过大时发送 `tool.failed`，并让 LLM 使用可读的失败上下文继续回复；工具失败不能伪造成功天气或新闻，也不能让 WebSocket 直接断开。

## 事件协议

新增事件保持现有公共 WebSocket JSON 事件格式：

```json
{"type":"tool.started","turn_id":"t1","details":{"name":"get_weather"}}
{"type":"tool.completed","turn_id":"t1","details":{"name":"get_weather","duration_ms":420}}
{"type":"tool.failed","turn_id":"t1","details":{"name":"get_weather","code":"timeout","message":"天气服务暂时不可用"}}
```

事件不返回 API Key、完整请求参数中的敏感字段、原始供应商响应或内部堆栈。实时测试页展示工具事件名称、耗时和失败代码；普通聊天页不显示诊断细节，只展示最终文字和语音。

## 角色绑定和验收

为紫萱 active version 绑定天气查询和新闻查询 Skill，发布新版本后重新创建公共会话。天气问题必须出现真实天气工具事件，回复包含工具返回的地区和时间信息；新闻问题必须出现新闻工具事件，回复说明来源和时间。断开网络或让工具超时时，必须出现 `tool.failed`，LLM 回复明确说明暂时无法获取，不能使用编造的实时数据。

验收同时覆盖没有 Skill 绑定的角色、未发布 Skill、非法工具名称、参数校验、工具超时、工具结果过大、文字输入和音频输入。设备 MQTT 会话和设备工具隔离测试继续保持通过。
