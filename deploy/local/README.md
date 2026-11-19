# 本机开发环境

这套环境只用于在一台电脑上开发和调试，所有端口只应在本机访问。对外部署使用 `deploy/zixuan`。

## 组成

| 组件 | 地址 | 启动方式 |
|---|---|---|
| MySQL、Redis | 127.0.0.1:3306、127.0.0.1:6379 | `docker compose -f deploy/local/docker-compose.yml up -d` |
| manager-api | http://127.0.0.1:8002/zixuan | 在 `server/main/manager-api` 下 `mvn -q -DskipTests package` 后 `ZIXUAN_SMS_LOG_CODES=true java -jar target/zixuan-manager-api.jar` |
| zixuan-server | ws://127.0.0.1:8000/zixuan/v1/ 和 http://127.0.0.1:8003 | 在 `server/main/zixuan-server` 下 `PYTHONPATH=<仓库根目录> <仓库根目录>/.venv310/bin/python app.py` |
| companion-console | http://localhost:8001 | 在 `server/main/companion-console` 下 `npm run dev` |
| companion-web | http://localhost:8010 | 在 `server/main/companion-web` 下 `npm run dev` |

MQTT 网关只服务实体设备，网页端调试不需要启动。

## 首次准备

1. 启动 MySQL 和 Redis。Docker Hub 拉不动时，在 `deploy/local/.env` 里设置 `ZIXUAN_LOCAL_MYSQL_IMAGE` 和 `ZIXUAN_LOCAL_REDIS_IMAGE`，指向本机已有或镜像站的 MySQL 8.x、Redis 6 以上镜像。这个文件不进版本库。
2. 启动 manager-api。它使用 `application-dev.yml` 里的本机账号，并在首次启动时自动建表。
3. 写入本机服务地址并清掉缓存。全新数据库里 `server.websocket` 等参数是字符串 `null`，zixuan-server 会因此拒绝启动：

   ```bash
   docker exec -i zixuan-local-db mysql -uroot -p123456 zixuan_esp32_server < deploy/local/local-params.sql
   docker exec zixuan-local-redis redis-cli del zixuan:sys:params zixuan:server:config
   ```

4. 把 `server/main/zixuan-server/config_from_api.yaml` 复制为 `data/.config.yaml`，`secret` 填数据库 `sys_params` 表里 `server.secret` 的值。
5. 下载语音识别模型。按 `server/docs/Deployment.md` 的说明，把 SenseVoiceSmall 的 `model.pt` 放到 `server/main/zixuan-server/models/SenseVoiceSmall/`。
6. 把 `server/main/companion-web/.env.example` 复制为 `.env.local`。
7. 打开 http://localhost:8001 注册第一个账号。**第一个通过管理台注册的账号会成为超级管理员，必须先于任何手机号用户创建**，否则管理台注册出来的只是普通账号。
8. 用管理员账号打开“模型管理”，在“连接与对话模型”里依次完成三步：添加连接（选服务商、填密钥、测试连接），拉取模型并勾选要用的模型，在“默认模型”里把对话模型指定为刚添加的模型。默认语音合成是免密钥的 EdgeTTS，默认语音识别是本地 FunASR，不需要配置。

## 用手机号登录网页端

网页端 http://127.0.0.1:8010 用手机号加短信验证码登录。本机没有配置短信通道，所以启动 manager-api 时带上 `ZIXUAN_SMS_LOG_CODES=true`：验证码不会真的发出去，而是写进 `server/main/manager-api/logs/local-dev.out`，搜索“本机调试”就能看到。这个开关只能在本机使用，任何能被外部访问的环境都不能打开。

1. 用管理员账号打开管理台的“邀请码”页面，生成一个邀请码。
2. 打开 http://127.0.0.1:8010 ，填一个测试手机号，例如 `13800000001`，点“获取验证码”。
3. 从 manager-api 日志里读出验证码，连同邀请码一起填入并登录。新手机号第一次登录时必须填邀请码，之后再登录不需要。
4. 第一次登录后选一个陪伴角色，就可以开始对话。

## 常见问题

- 改了 `sys_params` 不生效：manager-api 把参数缓存在 Redis 的 `zixuan:sys:params` 和 `zixuan:server:config`，改库后要删掉这两个键，并重启 zixuan-server。
- zixuan-server 报 `No module named 'server.main.shared'`：没有把仓库根目录放进 `PYTHONPATH`。
- zixuan-server 报 `FunASR模型目录不完整`：缺少第 5 步的 `model.pt`。
- 网页端新加的接口返回 404：Next.js 开发服务器偶尔监听不到新建的目录，重启 companion-web 的开发服务器，或者 `touch` 一下对应的 `route.ts`。
- 管理台或后端测试显示全部通过但其实没跑：manager-api 的构建默认跳过测试，要加 `-DskipTests=false`。
