# 混合检索、发布与回滚运行手册

## 1. 链路边界

文档完成解析与分块后，INDEX Worker 使用 `qwen3-embedding:4b-q4_K_M` 生成 2560 维向量，并将同一批稳定 Chunk ID 写入 Elasticsearch 草稿索引和 Milvus 草稿集合。只有两端的数量、Chunk ID 与正文 SHA-256 清单均和 MySQL 一致，版本才进入 `READY`。

人工发布或回滚先幂等写入两个发布层索引，验证成功后再用 MySQL 短事务切换 `current_published_version_id`。Agent 检索结束前仍会批量回查这一发布指针，因此旧索引异步清理失败也不会把旧内容返回给用户。停用文档时先清空 MySQL 发布指针，再异步清理索引。

在线查询固定执行：ES IK BM25 Top30 + Milvus COSINE Top30 → 加权 RRF Top10 → `bge-reranker-v2-m3` → MySQL 指针终审 → Top5。返回证据不足时明确拒答，不让模型脱离证据编造。BGE 超时或不可用时统一返回无可靠证据，不能仅凭 RRF 排名判定知识库可以回答。

## 2. 本地依赖启动

Compose 不接管现有 MySQL 和 Redis。文档 MinIO 与 Milvus 内部 MinIO 是两个独立服务和数据卷，禁止混用。

```powershell
$env:KNOWLEDGE_MINIO_ROOT_USER = Read-Host '文档 MinIO 用户名'
$env:KNOWLEDGE_MINIO_ROOT_PASSWORD = Read-Host -MaskInput '文档 MinIO 密码'
$env:KNOWLEDGE_RABBITMQ_PASSWORD = Read-Host -MaskInput 'RabbitMQ 密码'
$env:KNOWLEDGE_MILVUS_MINIO_USER = Read-Host 'Milvus 内部 MinIO 用户名'
$env:KNOWLEDGE_MILVUS_MINIO_PASSWORD = Read-Host -MaskInput 'Milvus 内部 MinIO 密码'

docker compose -f compose.knowledge.yml up -d --build
docker compose -f compose.knowledge.yml ps
```

首次在本机安装并启动 Ollama 后拉取已确认的量化模型：

```powershell
ollama pull qwen3-embedding:4b-q4_K_M
ollama list
```

BGE 精排复用本机已有的 `D:\GitCode\BizAgent\reranker-service`，默认监听 `http://127.0.0.1:8000`。该服务第一次启动需要下载 `BAAI/bge-reranker-v2-m3`，模型就绪前健康检查会保持 503。

关键健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:9200/_cluster/health
Invoke-RestMethod http://127.0.0.1:9091/healthz
Invoke-RestMethod http://127.0.0.1:11434/api/tags
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8091/health
```

## 3. 启动知识库服务

密钥只放环境变量或 Nacos 密文配置，不提交 Git。内部调用密钥至少 32 个字符，并与 Agent 使用同一值。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SSPX_CLIENT_SECRET = Read-Host -MaskInput 'SSPX Client Secret'
$env:KNOWLEDGE_MINIO_ACCESS_KEY = Read-Host '文档 MinIO Access Key'
$env:KNOWLEDGE_MINIO_SECRET_KEY = Read-Host -MaskInput '文档 MinIO Secret Key'
$env:KNOWLEDGE_RABBITMQ_PASSWORD = Read-Host -MaskInput 'RabbitMQ 密码'
$env:KNOWLEDGE_INTERNAL_API_SECRET = Read-Host -MaskInput 'Agent 内部调用密钥'
mvn spring-boot:run
```

默认依赖地址：ES `9200`、Milvus `19530`、Ollama `11434`、BGE `8000`、OCR `8091`。需要覆盖时使用 `application-local.yml` 中对应的 `KNOWLEDGE_*` 环境变量。

## 4. 管理端验收

以下写接口都需要管理端 Cookie、`X-XSRF-TOKEN` 和 `X-Request-Id`：

```text
POST .../documents/{documentId}/versions/{versionId}/publish
POST .../documents/{documentId}/versions/{versionId}/rollback
POST .../documents/{documentId}/disable
POST /api/v1/admin/tenants/{tenantId}/knowledge/retrieve?layer=DRAFT
POST /api/v1/admin/tenants/{tenantId}/knowledge/retrieve?layer=PUBLISHED
```

推荐验收顺序：上传文档 → 等待版本 `READY` → 查询 `layer=DRAFT` → 手动发布 → 查询 `layer=PUBLISHED` → 修订形成新版本 → 再发布 → 回滚到旧版本 → 停用。每一步都检查返回的 `versionId`、`strategyVersion`、`degradationMode` 与证据位置。

管理端诊断请求体：

```json
{
  "question": "商品签收后几天内可以退货？",
  "knowledgeBaseIds": [10]
}
```

## 5. Agent 内部接口签名

内部接口为 `POST /api/v1/internal/knowledge/retrieve`。调用方发送租户、当前登录用户、毫秒时间戳、随机 nonce、签名和可选请求号。签名原文严格为：

```text
POST\n/api/v1/internal/knowledge/retrieve\n{tenantId}\n{userId}\n{timestamp}\n{nonce}\n{sha256(trim(question))}\n{sortedDistinctKnowledgeBaseIdsCsv}
```

`sortedDistinctKnowledgeBaseIdsCsv` 是将 `knowledgeBaseIds` 去重、升序排序后用英文逗号连接的结果；未限定知识库时为空字符串。对以上 UTF-8 文本使用共享密钥计算 HMAC-SHA256，并发送小写十六进制结果。服务端先验证五分钟时间窗和签名，再用 Redis `SET NX` 保存 nonce 十分钟；Redis 不可用时入口关闭，防止降级后出现重放风险。接口只查询当前发布层，用户无法通过请求切换到草稿层。

## 6. 故障矩阵

| 故障 | 行为 |
|---|---|
| Elasticsearch 不可用 | 使用 Milvus + BGE |
| Embedding 或 Milvus 不可用 | 使用 Elasticsearch + BGE |
| 仅 BGE 不可用 | 返回 `NO_RELIABLE_EVIDENCE`，不生成知识答案 |
| BGE 与任一路召回同时不可用 | 返回无可靠证据，不生成知识答案 |
| 两路召回都不可用 | 返回 `KNOWLEDGE_SERVICE_UNAVAILABLE` |
| 发布索引写入或校验失败 | MySQL 发布指针保持不变 |
| 发布后旧索引清理失败 | 记录重试任务；MySQL 终审继续屏蔽旧版本 |
| 内部签名、时间窗或 nonce 无效 | 返回 `INTERNAL_SIGNATURE_INVALID` |

日志只记录请求号、数量、耗时、策略版本、降级模式和稳定错误码，不记录用户问题、文档正文、签名、共享密钥或访问令牌。
