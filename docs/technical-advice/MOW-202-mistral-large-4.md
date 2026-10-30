# MOW-202：Mistral Large 4 技术建议

## 结论

**暂不接入生产，也不替换当前模型；仅登记为“资源批准后可做离线 A/B”的候选。**

原因是：模型能力和 OpenAI-compatible 接入形态与 zixuan-server 兼容，但 1.05T 总参数带来的多卡显存、部署复杂度和数据处理风险明显高于当前阶段的收益。官方 API/托管推理属于付费或商业许可核查范围，本任务不调用、不购买。

## 官方信息核对

- 模型：`mistralai/Mistral-Large-4-1T-A52B`，1.05T 总参数、约 52B active parameters、1.6B vision encoder，原生多模态 MoE。
- 官方模型页列出 1M context、function calling、structured outputs、chat completions 和 reasoning/chat 两种模式。
- 官方发布页说明权重已开放，可用 vLLM 或 SGLang 自托管；也列出 TensorRT-LLM、NVIDIA NIM 等部署路径。
- 许可证：**Mistral Ecosystem Open-Weight License v26.10**（官方称 modified MIT）。允许使用、修改、微调和商业化；**若业务把模型作为 Model-as-a-Service 对外提供，需在商业使用前向 Mistral 取得单独商业许可**。
- 官方 API 页面给出的价格是输入 $0.70/M、缓存输入 $0.07/M、输出 $2.10/M token；本任务不使用该付费通道。

来源：

- https://mistral.ai/news/mistral-large-4-oss/
- https://docs.mistral.ai/models/mistral-large-4-0
- https://huggingface.co/mistralai/Mistral-Large-4-1T-A52B/blob/main/LICENSE

## 显存与推理可行性

按参数量做下限估算（未计 KV cache、运行时 buffer、视觉编码器和并行通信）：

| 权重格式 | 仅权重数量级 | 判断 |
|---|---:|---|
| BF16/FP16 | 约 2.1 TB | 消费级单卡不可行，多卡集群才有意义 |
| FP8 | 约 1.05 TB | 仍需约 TB 级显存与多卡并行 |
| 4-bit | 约 0.53 TB | 仍需数百 GB 显存；量化格式和 kernel 必须由部署机实测 |

因此：

1. ESP32 只负责设备能力、音频和 UI，完全不承担该模型推理；模型只能在服务端 GPU 集群或合规托管端运行。
2. 当前仓库的 `server/main/zixuan-server/core/providers/llm/openai/openai.py` 已支持 `base_url`、`model_name`、流式输出、工具调用和首字超时；如果后续有合规的 OpenAI-compatible endpoint，接入主要是配置级，不需要复制固件或新增板级代码。
3. 自托管需要部署机确认：权重下载体积、GPU 型号/显存、tensor/expert parallel、vLLM/SGLang 版本与 ML4 专用支持、图像输入协议、工具调用 parser、首响和并发。当前容器没有对应 GPU，不能把上述可行性写成已实测。
4. 许可证允许自托管商业使用，但对外提供模型服务有额外许可边界；“能部署”不等于“可以把它作为公共 API 产品提供”。

## 是否用真实对话集做小规模 A/B

**建议做，但先做“脱敏、离线、只读回放设计”，不在本任务执行真实数据上传或付费调用。**

### 最小方案

- 样本：约 30–50 条已获授权的真实陪伴轮次，覆盖闲聊/情绪承接、记忆召回、拍照或设备状态、工具失败重试、用户打断、连续 3–6 步任务。
- 对照：当前生产模型固定版本为 A；ML4 固定权重/推理配置为 B。system prompt、角色卡、Memory 快照、工具 schema、采样参数和最大输出长度保持一致。
- 数据处理：去除姓名、设备 UUID、家庭地址、语音原文中的个人信息和访问凭据；保留必要的事件顺序。未取得明确授权的原始语音、完整 transcript 和 Memory 不上传第三方服务。
- 执行：先用本地/公司已批准 GPU 做事件回放；若没有免费且已授权的推理资源，则只完成数据集和评分脚本，不申请 API key、不购买 GPU。

### 指标与门槛

| 维度 | 指标 |
|---|---|
| 陪伴质量 | 情绪接住、自然度、简洁度、事实一致性；盲评 1–5 分 |
| 安全 | 儿童安全、自伤/现实危险转介、依赖诱导、提示词泄露；红队样本不得劣化 |
| 工具 | schema 遵循、误调用率、失败后恢复、跨连接工具泄漏为 0 |
| 记忆 | 证据选择准确率、冲突旧记忆抑制、无记忆时不编造 |
| 实时性 | 首个可见文本 P50/P95、完整轮次时延、打断恢复率 |
| 成本/资源 | 实测中文 input/output tokens、显存峰值、吞吐、并发下错误率 |

建议只有在以下条件同时满足时才进入下一步：安全项不低于 A、工具隔离为 0 泄漏、关键陪伴维度盲评不低于 A，且首响/完整时延不超过当前基线可接受范围。没有这些实测，不声称 ML4 更适合陪伴产品。

## 风险和后续门槛

- 不把 Mistral 官方 benchmark 直接当作陪伴效果证据；编码、网络浏览和防御安全成绩与儿童陪伴、中文口语和设备工具链不是同一任务。
- 自托管前要单独核对完整 LICENSE、模型卡、权重来源、第三方量化包许可证和部署镜像；不采用未核验的量化文件。
- 若后续需要 GPU、API key、托管额度或商业许可，列为“等老板拍板”，本任务不触发。
- 若进入实现阶段，先在独立测试配置/分流路由中接入，不改生产默认模型；完成离线 A/B 和代码审查后再讨论是否申请发布审批。
