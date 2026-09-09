# 知识库平台验收追踪矩阵

| 设计验收项 | 自动化证据 | 人工/运行环境证据 |
|---|---|---|
| 所有约定格式可上传、解析、预览、发布 | 各 Parser、UploadPolicy、DocumentController、IngestionPipeline 测试 | 管理台逐格式上传清单 |
| 扫描 PDF、图片、PPTX 图片经 PaddleOCR | PdfAndImageParser、OfficeDocumentParser、PaddleOcrHttpClient、OCR API 测试 | OCR 容器健康与混合样本预览 |
| 低置信度 OCR 可校正并重建 | DocumentCorrectionService、DocumentPreview 测试 | 管理台低置信度标记与校正发布 |
| 新版本期间旧版本可查，发布/回滚引用正确 | PublicationService、并发集成、PublishedVersionValidator 测试 | 发布、回滚、停用冒烟链路 |
| 系统管理员/超级管理员权限边界 | AdminSecurity、TenantAccessGuard、Controller 测试 | SSPX Application 444 两角色登录 |
| 坐席只检索本租户已发布知识 | InternalRequestVerifier、PublishedVersionValidator、跨租户测试 | 使用两个租户做负向查询 |
| 回答引用来自真实结构化证据 | Agent KnowledgeServiceGateway、SSE/历史契约、桌面解析测试 | 实时与刷新后引用卡片一致 |
| 无可靠证据不生成业务规则 | FreshBusinessResultGate 与 OUTPUT_LIMIT/异常测试 | 无答案问题和停服场景 |
| 单组件故障受控降级 | HybridRetrievalService 故障矩阵测试 | 本手册第 4 节故障注入 |
| Worker 重启或 MQ 丢失可恢复 | 租约、心跳、Scanner、Outbox、Pipeline 测试 | 处理中停服并恢复 |
| 入库不挤占在线检索 | IngestionBulkhead、IngestionWorker 饱和测试 | 观察拒绝计数和检索 P95 |
| 日志/指标不泄露敏感正文 | KnowledgeMetrics 标签测试、Gateway 日志测试 | 密钥扫描与日志抽查 |
| 中文检索质量可重复评估 | 版本化 JSONL 格式与评测脚本 | 本地真实文档集发布阈值报告 |
| 可备份、恢复并重建派生索引 | IndexRecoveryService、IndexRecoveryRunner、索引 Manifest 测试 | 本手册第 6～7 节恢复演练 |

发布前必须同时满足自动化测试、真实依赖健康检查、中文评测门槛和桌面端五个冒烟场景。示例数据或 Mock 通过不能替代真实文档与真实依赖验收。
