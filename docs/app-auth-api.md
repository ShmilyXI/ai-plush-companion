# App 认证 API

消费者 App 的独立认证通道，位于 `manager-api` 的 `/app/v1/auth` 路径。支持**手机号+验证码**与**手机号+密码**两种注册/登录方式。手机号统一使用国际区号格式（如 `+8613800138000`），与平台校验规则一致。

## 设计要点

- App 用户复用 `sys_user` 主数据，新增 `phone` 唯一列（changelog `202609060900`）。**不新建第二套用户表**，因此 `ai_device.user_id`、`ai_agent.user_id` 与 Memory namespace 的 `user_id` 口径保持不变，设备链路（固件、MQTT 网关、Python 运行时）零改动。
- 历史"手机号即用户名"的存量账号由 changelog 自动回填 `phone`，可用原手机号直接登录。
- App 注册用户永远是普通用户（`super_admin=0`），不复用管理台注册的"首个用户成超管"逻辑。
- 会话 token 独立于管理台 `sys_user_token`：多端并存，库中只保存 SHA-256 哈希，明文只在签发/刷新响应中出现一次。

## 端点

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/app/v1/auth/sms-code` | 发送短信验证码 | 匿名 |
| POST | `/app/v1/auth/register` | 注册（手机号+验证码+SM2 密码） | 匿名 |
| POST | `/app/v1/auth/login-password` | 密码登录（手机号+SM2 密码） | 匿名 |
| POST | `/app/v1/auth/login-code` | 验证码登录（未注册自动创建账号） | 匿名 |
| POST | `/app/v1/auth/refresh` | 用刷新令牌轮换访问令牌 | 匿名 |
| POST | `/app/v1/auth/logout` | 退出当前会话 | App token |
| GET | `/app/v1/auth/profile` | 当前用户信息 | App token |

请求头 `X-Device-Label`（可选）携带设备标识，用于多端会话列表展示。

### 令牌模型

登录成功返回：

```json
{
  "code": 0,
  "data": {
    "accessToken": "app_<43位随机串>",
    "expiresIn": 43200,
    "refreshToken": "appr_<43位随机串>",
    "refreshExpiresIn": 2592000
  }
}
```

- 访问令牌 `app_` 前缀，12 小时有效，校验时剩余不足一半会滑动续期。
- 刷新令牌 `appr_` 前缀，30 天有效；`POST /app/v1/auth/refresh` 只轮换访问令牌。
- 每用户最多 10 个并存会话，超限自动淘汰最旧会话。
- 后续请求使用 `Authorization: Bearer app_...`。

### 密码传输

密码字段使用 SM2 加密传输：客户端先 `GET /user/pub-config` 获取 `sm2PublicKey`，加密后填入 `password` 字段，服务端解密后做 BCrypt 校验/存储。密码强度要求与平台一致（含大小写字母和数字）。

### 限流与防爆破

- 短信验证码：同一手机号 60 秒一条、每日上限（`server.sms.max_send_count`，默认 5）；同一来源 IP 每日最多 20 条。
- 密码登录：同一手机号 60 秒窗口内最多 10 次失败，超限临时锁定。
- App 通道不要求图形验证码，不依赖 `server.enable_mobile_register` 开关。

## App token 的作用面

`app_` 令牌经 Shiro 校验后按**普通用户**授权（即使底层账号是管理台超管），且只能在以下路径使用：

- `/app/v1/**`：App 认证自身接口；
- `/api/v1/**`：公共会话 API（创建对话、资源列表、历史、API Key 管理等）；
- `/api/v1/web-sessions/bootstrap`：Web 会话签发；
- `/companion/**`：消费者设备管理（设备列表、绑定、改绑角色、记忆管理等）；
- `/device/bind/**`、`/device/unbind`：设备激活码绑定与解绑。

管理台其余路由（`/admin/**`、`/agent/**`、`/user/**` 等）不接受 App 令牌。

## 与公共会话的衔接

App 登录拿到 `app_` 令牌后，可直接调用公共会话 API：

```bash
# 1. 创建会话
curl -X POST https://<manager-api>/api/v1/conversations \
  -H "Authorization: Bearer app_<accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"agentId":"<agentId>","inputModes":["text"],"outputModes":["text"]}'

# 2. 用返回的 runtimeToken 连接 Python WebSocket
#    wss://<runtime>/api/v1/conversations/<id>/stream，子协议 bearer.<runtimeToken>
```

对话能力、事件流、Memory 隔离与设备侧完全一致，参见 `docs/public-conversation-api.yaml` 与 `docs/api/README.md`。

## curl 示例

```bash
# 发送验证码
curl -X POST https://<manager-api>/app/v1/auth/sms-code \
  -H "Content-Type: application/json" -d '{"phone":"+8613800138000"}'

# 验证码登录（未注册自动建号）
curl -X POST https://<manager-api>/app/v1/auth/login-code \
  -H "Content-Type: application/json" \
  -d '{"phone":"+8613800138000","code":"123456"}'

# 密码登录（password 为 SM2 密文）
curl -X POST https://<manager-api>/app/v1/auth/login-password \
  -H "Content-Type: application/json" \
  -d '{"phone":"+8613800138000","password":"<sm2-ciphertext>"}'

# 刷新
curl -X POST https://<manager-api>/app/v1/auth/refresh \
  -H "Content-Type: application/json" -d '{"refreshToken":"appr_<...>"}'

# 退出当前会话
curl -X POST https://<manager-api>/app/v1/auth/logout \
  -H "Authorization: Bearer app_<accessToken>"
```

## 本地操练场验证

`docs/api/public-conversation-demo.html` 已内置 App 认证面板（手机号登录/注册、SM2 密码加密、刷新与退出），登录成功后 `Bearer app_...` 自动填入"用户令牌"输入框，填角色 ID 后点"连接角色"即可直接对话。

```bash
# 启动本地静态服务后访问页面
python3 -m http.server 8011 --directory docs/api
# 浏览器打开 http://127.0.0.1:8011/public-conversation-demo.html
```

本地联调不想真发短信时，可直接向 Redis 写入测试验证码（5 分钟有效，登录成功即消费）：

```bash
docker exec ai-plush-companion-redis redis-cli SET \
  "zixuan:sys:captcha:zixuan:sms:Validate:Code:+8613800138000" '"123456"' EX 300
```

随后在页面填手机号 `+8613800138000`、验证码 `123456`，点"验证码登录"。密码登录/注册的密码字段由页面用服务端 SM2 公钥加密后提交。

## 实现索引

- 认证流程：`zixuan.modules.appauth.service.impl.AppAuthServiceImpl`
- 令牌存取：`zixuan.modules.appauth.service.impl.AppUserTokenServiceImpl`
- 端点：`zixuan.modules.appauth.controller.AppAuthController`
- Shiro 接线：`Oauth2Filter#isAppTokenPath`（路径门禁）、`Oauth2Realm`（`app_` 解析与普通用户降权）
- 数据库：`db/changelog/202609060900.sql`
- 实施计划：`docs/superpowers/plans/2026-09-05-app-auth.md`

## 已知边界

- 验证码直登创建的账号没有密码，密码登录会被拒绝；设置密码需先走注册密码或后续补密码接口（暂未提供）。
- 邮箱登录/注册不在本期（用户需求为手机号两种方式）。
- App 侧对话列表、重命名、删除等管理接口仍未实现（属于公共会话后续切片）。
