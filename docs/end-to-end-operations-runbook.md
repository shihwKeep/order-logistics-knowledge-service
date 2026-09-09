# 知识库平台端到端运行、诊断与恢复手册

## 1. 服务边界与启动顺序

推荐严格按以下顺序启动，前一层健康后再启动后一层：

1. 现有 MySQL、Redis、SSPX 与 Gateway。
2. `compose.knowledge.yml`：文档 MinIO、RabbitMQ、PaddleOCR、Elasticsearch、Milvus。
3. 本机 Ollama 与 `qwen3-embedding:4b-q4_K_M`。
4. BGE Reranker。
5. Knowledge Service（默认 `8084`）。
6. Agent（当前本地端口 `8082`）。
7. 知识管理台与 Electron 坐席端。

后台入库默认 `knowledge.document.ingestion.max-concurrent-tasks=1`，Embedding 单批最多 8 个 Chunk。MQ 和兜底扫描器同时唤醒时，未获得进程内许可的任务不会领取数据库租约，之后仍会被扫描器重新发现，因此不会丢任务。

## 2. 环境变量与启动

密钥只注入当前进程或受控配置中心，不写入 Git、浏览器存储或命令输出。

```powershell
$env:KNOWLEDGE_MINIO_ROOT_USER = Read-Host '文档 MinIO 用户名'
$env:KNOWLEDGE_MINIO_ROOT_PASSWORD = Read-Host -MaskInput '文档 MinIO 密码'
$env:KNOWLEDGE_RABBITMQ_PASSWORD = Read-Host -MaskInput 'RabbitMQ 密码'
$env:KNOWLEDGE_MILVUS_MINIO_USER = Read-Host 'Milvus MinIO 用户名'
$env:KNOWLEDGE_MILVUS_MINIO_PASSWORD = Read-Host -MaskInput 'Milvus MinIO 密码'
docker compose -f compose.knowledge.yml up -d --build
docker compose -f compose.knowledge.yml ps
```

Knowledge Service：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SSPX_CLIENT_SECRET = Read-Host -MaskInput 'SSPX Client Secret'
$env:KNOWLEDGE_MINIO_ACCESS_KEY = Read-Host '文档 MinIO Access Key'
$env:KNOWLEDGE_MINIO_SECRET_KEY = Read-Host -MaskInput '文档 MinIO Secret Key'
$env:KNOWLEDGE_RABBITMQ_PASSWORD = Read-Host -MaskInput 'RabbitMQ 密码'
$env:KNOWLEDGE_INTERNAL_API_SECRET = Read-Host -MaskInput 'Agent/Knowledge 共享内部密钥'
mvn spring-boot:run
```

Agent 与 Knowledge Service 必须使用同一个内部密钥。Agent Nacos 增加：

```properties
integration.knowledge.base-url=http://127.0.0.1:8084
integration.knowledge.internal-secret=${KNOWLEDGE_INTERNAL_API_SECRET}
integration.knowledge.connect-timeout=2s
integration.knowledge.read-timeout=10s
agent.tool.knowledge.enabled=true
agent.tool.knowledge.rollout-mode=ALL
agent.tool.knowledge.allowed-org-ids=
```

Knowledge Service 本地配置或 Nacos 对应项：

```properties
knowledge.retrieval.internal-api.enabled=true
knowledge.retrieval.internal-api.secret=${KNOWLEDGE_INTERNAL_API_SECRET}
knowledge.retrieval.internal-api.allowed-clock-skew=5m
knowledge.retrieval.internal-api.nonce-ttl=10m
knowledge.document.ingestion.max-concurrent-tasks=1
```

管理台：

```powershell
Set-Location D:\GitCode\order-logistics-agent-admin-web
npm install
npm run dev
```

生产构建使用 `npm run build`，由 Gateway 将 `/knowledge/**` 去除前缀后转发到 `http://127.0.0.1:8084`。

## 3. 健康与监控

```powershell
Invoke-RestMethod http://127.0.0.1:9200/_cluster/health
Invoke-RestMethod http://127.0.0.1:9091/healthz
Invoke-RestMethod http://127.0.0.1:8091/health
Invoke-RestMethod http://127.0.0.1:11434/api/tags
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8084/actuator/health
```

Prometheus 指标入口为 `/actuator/prometheus`。健康检查允许匿名访问；指标端点只允许持有服务端会话的超级管理员读取，不能经 Gateway 对公网暴露。接入自动采集前应在内网增加独立的监控鉴权代理，不要把管理员 Cookie 固化到采集配置。重点告警：

- `knowledge_retrieval_duration_seconds`：按 `degradation`、`outcome` 观察在线检索延迟与降级。
- `knowledge_ingestion_duration_seconds`：按 `stage`、`outcome` 观察入库任务。
- `knowledge_ingestion_rejected_total`：持续增长表示后台舱壁饱和，需要检查积压，而不是直接提高并发。
- MySQL 中 `kb_ingestion_task` 的 `RETRY/DEAD` 数量和过期租约。
- `kb_search_log` 的拒答率、降级模式与组件错误码。

上述指标和日志禁止使用 tenantId、userId、requestId、文档 ID、问题或正文作为指标标签。请求号只用于日志关联，不记录问题、正文、签名和密钥。

## 4. 故障注入验收

每次只停止一个组件，验证后立即恢复：

```powershell
docker compose -f compose.knowledge.yml stop knowledge-elasticsearch
# 预期：Milvus + BGE，degradationMode=VECTOR_ONLY
docker compose -f compose.knowledge.yml start knowledge-elasticsearch

docker compose -f compose.knowledge.yml stop knowledge-milvus
# 预期：ES + BGE，degradationMode=KEYWORD_ONLY
docker compose -f compose.knowledge.yml start knowledge-milvus
```

停止 Reranker 时，只有同时被 ES 与 Milvus 命中且达到严格 RRF 阈值的证据可以返回；再停止任一路召回后必须变为 `answerable=false`。同时停止 ES 与 Milvus时，内部接口必须返回 `KNOWLEDGE_SERVICE_UNAVAILABLE`。停止 Redis 后，内部签名入口必须失败关闭，不能跳过 nonce 防重放。

入库故障验收：处理期间停止 OCR、Ollama、ES 或 Milvus，版本不得进入 `READY`；任务进入 `RETRY`，超过上限进入 `DEAD`。恢复组件并手动重试后，任务从 MySQL 真相继续处理。发布索引校验失败时，`current_published_version_id` 不得变化。

## 5. 中文检索离线评测

先复制示例评测集并换成当前租户真实文档标题，不要把客户隐私或生产原文提交 Git：

```powershell
Copy-Item evaluation\chinese-retrieval-golden.example.jsonl evaluation\chinese-retrieval-golden.local.jsonl
$env:KNOWLEDGE_INTERNAL_API_SECRET = Read-Host -MaskInput '内部密钥'
.\tools\evaluate-retrieval.ps1 -TenantId 1 -UserId 10567 `
  -Dataset .\evaluation\chinese-retrieval-golden.local.jsonl
```

脚本只输出聚合值，不输出问题和证据正文。首期发布门槛建议：Recall@5 ≥ 0.90、首条命中率 ≥ 0.75、拒答准确率 ≥ 0.95、无依据回答率 = 0、服务错误数 = 0。示例数据只是结构模板，未导入对应文档前不作为发布成绩。

## 6. 备份策略

备份顺序：先 MySQL，再文档 MinIO。Elasticsearch 与 Milvus是可重建派生数据，不作为发布真相；可做快照加速恢复，但不能替代 MySQL 与原文件备份。

MySQL（命令会提示输入密码；每次生成带时间戳的新文件，备份文件不得提交 Git）：

```powershell
$knowledgeBackupDir = 'D:\backup\knowledge'
New-Item -ItemType Directory -Force -Path $knowledgeBackupDir | Out-Null
$knowledgeBackupFile = Join-Path $knowledgeBackupDir `
  ("knowledge-{0}.sql" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
mysqldump --single-transaction --routines --triggers `
  -h 127.0.0.1 -P 3307 -u root -p `
  order_logistics_knowledge > $knowledgeBackupFile
```

文档 MinIO 使用 `mc mirror` 复制整个 `knowledge-documents` 桶到受控备份目录或备份桶。保存对象版本时必须保持原始对象键不变。Redis 仅保存管理会话与 nonce，不恢复；RabbitMQ 仅负责唤醒，恢复后由 MySQL 扫描器重新发现任务。

Elasticsearch 可使用官方 Snapshot API 保存索引快照；Milvus 使用对应版本的 Milvus Backup。两者的快照版本必须记录应用提交、Embedding 模型、维度、分块策略和索引名称。快照用于缩短恢复时间；即使没有可用快照，也可通过下一节的维护命令从 MySQL Chunk 全量重建。

## 7. 恢复顺序

1. 在隔离环境恢复 MySQL，确认 Flyway 版本和发布指针。
2. 恢复文档 MinIO，并抽查数据库对象键都能读取。
3. 启动空的 Elasticsearch/Milvus，停止 Agent 对 Knowledge Service 的流量，并保持 Knowledge 入库任务关闭。
4. 在已设置本手册第 2 节环境变量的 Knowledge Service 目录执行恢复命令：

```powershell
mvn spring-boot:run `
  '-Dspring-boot.run.arguments=--spring.profiles.active=local --knowledge.document.ingestion.enabled=false --knowledge.retrieval.publication-cleanup.enabled=false --knowledge.maintenance.rebuild-indexes-on-startup=true'
```

维护任务严格以 `kb_document.current_draft_version_id` 和 `current_published_version_id` 为真相：读取 MySQL Chunk，重新调用当前配置的 Embedding，并分别替换、校验 ES/Milvus 的 `DRAFT` 与 `PUBLISHED` 层。日志出现 `knowledge_index_recovery completed=true` 且统计数符合数据库指针数量后停止该进程；任一版本数量、Embedding 契约、Manifest 或双索引指纹不一致都会令启动失败，不能开放流量。

5. 关闭恢复开关并正常启动 Knowledge Service，再抽样查询已发布文档和当前草稿预览。
6. 启动 Redis、RabbitMQ；过期会话重新登录，未完成任务由扫描器恢复。
7. 执行中文评测、跨租户安全用例、发布/回滚冒烟测试后再恢复 Agent 流量。

恢复期间不要先切换或伪造发布指针来迁就缺失索引；MySQL 指针是业务真相，索引必须重建到与它一致。

## 8. 桌面端验收

导入并发布至少一份规则文档后，新建对话依次提问：

- “退款期限是几天？”应出现基于证据的回答和“参考来源”卡片。
- “发票怎么开？”应调用知识工具，而不是凭模型常识作答。
- 一个知识库不存在的问题应固定回复“知识库中暂未找到相关规定……”，且没有引用卡片。
- 刷新会话后，历史消息中的引用卡片仍存在。
- 停止 Knowledge Service 后，不得出现伪造成功回答。
