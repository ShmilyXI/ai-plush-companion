# TencentDB Agent Memory 部署与运维

这套部署把 MemoryCore 放在项目自己的 Docker Compose 网络中。数据保存在 `tencentdb_memory_data` 命名卷。`zixuan-server` 负责记忆写入、召回和管理，`manager-api` 负责读取后台保存的记忆 LLM 与 Embedding 配置。

## 本地启动

在 `server/main/zixuan-server` 目录运行本地配置脚本。脚本从当前 MySQL 读取后台参数 `server.secret`，生成独立的 MemoryCore 密钥，写入被 Git 忽略的环境文件，然后启动本地 MemoryCore。重复执行会保留原 MemoryCore 密钥。

```bash
bash deploy/tencentdb-memory/configure-local.sh
```

本地后台配置 `Memory_tencentdb` 时，MemoryCore 地址填写 `http://host.docker.internal:8420`。完整部署到服务器 Compose 后填写 `http://tencentdb-memory-core:8420`。`TENCENTDB_MEMORY_MODEL_PROXY_KEY` 必须等于后台参数 `server.secret`。`TENCENTDB_MEMORY_CORE_KEY` 是 MemoryCore 自身接口密钥，必须使用另一个值，不能和代理密钥相同。向量维度要和后台配置的 Embedding 模型一致。

加载环境变量后启动完整服务。生产 Compose 不暴露 MemoryCore 主机端口。

```bash
set -a
. ./.env.tencentdb-memory
set +a
docker compose -f docker-compose_all.yml up -d
docker compose -f docker-compose_all.yml up -d tencentdb-memory-core
```

只有本机诊断时才叠加开发覆盖文件。它只监听 `127.0.0.1:8420`。

```bash
docker compose -f docker-compose_all.yml -f docker-compose.tencentdb-memory.dev.yml up -d tencentdb-memory-core
```

后台先在 Embedding 模型页配置并启用 OpenAI 兼容的向量模型。再进入记忆模型页编辑 `Memory_tencentdb`，填写 MemoryCore 地址与密钥，从下拉框选择已启用的记忆 LLM 和 Embedding 模型。保存并启用后，在测试陪伴角色的模型设置中把长期记忆切换到 `Memory_tencentdb`。

地址、密钥或模型名修改后，下一次请求立即生效。修改 `embedding_dimensions` 后要只重启 MemoryCore，并等待向量重新索引。manager-api、zixuan-server 和其他容器不用重启。

L0 对话记录会先写入。L1 原子记忆由异步提取生成，因此后台记忆列表不会和一句对话同时出现。L2 场景与 L3 核心画像需要更多对话，生成时间也更晚。同一用户和角色的长期记忆跨设备共享，不同用户或角色互相隔离。

## 冒烟检查

开发端口打开后运行脚本。脚本只输出请求 ID 和数量，不打印记忆内容或密钥。

```bash
MEMORY_CORE_URL=http://127.0.0.1:8420 \
MEMORY_CORE_API_KEY="$TENCENTDB_MEMORY_CORE_KEY" \
bash deploy/tencentdb-memory/smoke-test.sh
```

首次检查会写入独立的 smoke 范围并等待 L1。重启 MemoryCore 后用下面的命令确认 L0 和 smoke 范围仍在。

```bash
MEMORY_CORE_URL=http://127.0.0.1:8420 \
MEMORY_CORE_API_KEY="$TENCENTDB_MEMORY_CORE_KEY" \
bash deploy/tencentdb-memory/smoke-test.sh --verify-existing
```

## 停止、备份与恢复

停止容器不会删除数据卷。

```bash
docker compose -f docker-compose_all.yml stop tencentdb-memory-core
```

备份时先停止 MemoryCore，避免 SQLite 正在写入。下面的卷名适用于从 `zixuan-server` 目录启动且未覆盖 Compose project name 的部署。

```bash
docker run --rm -v zixuan-server_tencentdb_memory_data:/data -v "$PWD":/backup alpine tar czf /backup/tencentdb-memory-backup.tgz -C /data .
```

恢复前停止 MemoryCore，并确认目标数据卷是空卷。旧数据需要保留时先另做备份。

```bash
docker run --rm -v zixuan-server_tencentdb_memory_data:/data -v "$PWD":/backup alpine tar xzf /backup/tencentdb-memory-backup.tgz -C /data
docker compose -f docker-compose_all.yml up -d tencentdb-memory-core
```

## 升级与回滚

升级前先备份数据卷，记录当前固定的镜像摘要。修改 `docker-compose_all.yml` 的镜像摘要后，运行 TencentDB Memory 契约测试、Compose 渲染检查和真实冒烟测试。验证通过后再用于服务器部署。

升级失败时停止 MemoryCore，恢复上一版镜像摘要和数据卷备份，再重新启动。上游版本、固定摘要和架构摘要记录在 `deploy/tencentdb-memory/UPSTREAM.md`。

## 彻底删除

删除不可恢复。删除前必须停止 MemoryCore，并确认备份已经可用。下面的命令会永久删除全部 TencentDB Memory 数据。

```bash
docker compose -f docker-compose_all.yml stop tencentdb-memory-core
docker volume rm zixuan-server_tencentdb_memory_data
```
