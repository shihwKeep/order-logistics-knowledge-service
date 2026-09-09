# 版本化文档摄取运行手册

## 1. 处理链路

上传接口完成租户与知识库校验、文件签名校验、版本登记、MinIO 原件写入、数据库任务和 Outbox 登记。Outbox 投递 RabbitMQ 只负责唤醒 Worker；数据库定时扫描负责消息丢失、进程重启和租约过期后的恢复。

Worker 按格式提取文本。PDF 有文本层时直接提取，扫描页和 PNG/JPEG 调用独立 PaddleOCR；Office、HTML、CSV、TXT 与 Markdown 使用格式专用解析器。解析结果先保存为可定位原文单元，再按标题、段落和表格结构形成 Chunk，随后写入 Elasticsearch 草稿索引与 Milvus 草稿集合；两端数量和内容清单都与 MySQL 一致后，版本才进入 `READY`。

## 2. 启动基础设施

仓库内 Compose 不包含或修改现有 MySQL、Redis；它会启动文档 MinIO、RabbitMQ、PaddleOCR、带 IK 的 Elasticsearch，以及使用独立内部 MinIO 的 Milvus：

```powershell
$env:KNOWLEDGE_MINIO_ROOT_USER = Read-Host 'MinIO 用户名'
$env:KNOWLEDGE_MINIO_ROOT_PASSWORD = Read-Host -MaskInput 'MinIO 密码'
$env:KNOWLEDGE_RABBITMQ_PASSWORD = Read-Host -MaskInput 'RabbitMQ 密码'
$env:KNOWLEDGE_MILVUS_MINIO_USER = Read-Host 'Milvus 内部 MinIO 用户名'
$env:KNOWLEDGE_MILVUS_MINIO_PASSWORD = Read-Host -MaskInput 'Milvus 内部 MinIO 密码'
docker compose -f compose.knowledge.yml up -d
docker compose -f compose.knowledge.yml ps
```

本地应用配置中的 `KNOWLEDGE_MINIO_ACCESS_KEY`、`KNOWLEDGE_MINIO_SECRET_KEY` 和 `KNOWLEDGE_RABBITMQ_PASSWORD` 必须与上面的容器配置一致。首次 OCR 请求才会加载模型，因此第一次识别耗时会明显高于后续请求。

## 3. 管理接口

所有写接口沿用登录 Cookie、CSRF Header 和 `X-Request-Id`。以下省略统一前缀：

```text
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/versions
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/versions/{versionId}/task
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/versions/{versionId}/units
PUT  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/versions/{versionId}/units/{unitId}/correction
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/versions/{versionId}/retry
```

上传示例：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "$baseUrl/api/v1/admin/tenants/1/knowledge-bases/10/documents" `
  -WebSession $webSession -Headers $csrfHeaders `
  -Form @{ file = Get-Item 'D:\test\退款规则.pdf'; title = '退款规则' }
```

只查看低置信度 OCR 单元：

```powershell
Invoke-RestMethod -Method Get `
  -Uri "$baseUrl/api/v1/admin/tenants/1/knowledge-bases/10/documents/13/versions/21/units?lowConfidence=true&offset=0&limit=20" `
  -WebSession $webSession
```

校正接口不会覆盖 `rawText`；它追加修订记录、更新 `effectiveText`，并登记重新分块任务。

## 4. 故障定位

- `DOCUMENT_SIGNATURE_MISMATCH`：扩展名、MIME 和文件签名不一致。
- `DOCUMENT_STORAGE_UNAVAILABLE`：检查 MinIO 9000 端口、Bucket 与凭据。
- `DOCUMENT_OCR_FAILED`：检查 8091 健康检查、图片大小与 OCR 容器日志。
- 任务处于 `RETRY`：查看稳定的 `lastErrorCode`，到达 `nextRunAt` 后扫描器自动恢复。
- 任务处于 `DEAD`：修复外部依赖后调用版本 `retry` 接口。
- RabbitMQ 不可用：任务仍保留在 MySQL；RabbitMQ 恢复后或扫描周期到达时继续处理。

禁止在日志、Nacos、Git 或故障截图中放入 SSPX Secret、MinIO Secret、RabbitMQ 密码、图片 Base64 和文档正文。
