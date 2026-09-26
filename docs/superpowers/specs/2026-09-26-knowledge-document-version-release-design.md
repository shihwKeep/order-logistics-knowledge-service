# 知识文档版本与知识库 Release 设计

## 1. 背景

当前系统已经具备文档、文档版本、草稿索引、正式索引、单文档发布指针、回滚和异步清理等基础能力，但文件上传入口始终创建新的 `kb_document` 与 v1。用户重新上传同一份业务文档时，会得到另一个独立文档，而不是原文档的 v2、v3。

当前发布粒度也是单文档：`kb_document.current_published_version_id` 只能保证单个文档原子切换，无法保证一批相互关联的文档同时生效。草稿层还缺少旧草稿和失败索引残留的完整异步清理机制。

本设计补齐以下能力：

- 新文档上传与已有文档上传新版本使用不同入口。
- 同一文档支持 v1、v2、v3 等完整版本生命周期。
- 上传请求幂等、相同内容去重和并发版本号保护。
- 新草稿完全就绪后才切换当前草稿指针。
- 旧草稿、失败草稿和无效正式索引统一进入安全的异步清理流程。
- 通过知识库 Release 清单实现单文档或多文档原子发布。
- 支持按照历史 Release 安全回滚整个知识库。
- 管理端明确展示线上版本、当前草稿和最新处理版本。

## 2. 目标与非目标

### 2.1 目标

1. 一个知识库包含多个文档，一个文档包含多个文件版本。
2. 一个文档同一时刻最多有一个当前草稿版本，但可保留全部历史版本。
3. 一个知识库同一时刻只有一个活动 Release；该 Release 可以包含任意数量文档的确定版本。
4. 单文档发布与批量发布共用 Release 激活机制。
5. 用户要么看到完整旧 Release，要么看到完整新 Release，不会看到只切换一部分文档的混合状态。
6. ES、Milvus 和 MySQL 不使用分布式事务，而通过预构建、指针切换、查询终审和异步清理保证正确性。

### 2.2 非目标

- 本轮不为每个 Release 创建独立 ES Index 或 Milvus Collection。
- 本轮不实现自动根据文件名或标题推断文档身份。
- 本轮不改变现有文档解析、OCR、结构化切片、Embedding、RRF 与重排算法。
- 本轮不要求兼容生产环境的历史流量切换；本地存量数据通过 Flyway 迁移到首个 Release。

## 3. 核心方案

采用“共享双层索引 + MySQL Release 清单”方案：

- 草稿层继续使用共享的 ES 草稿索引和 Milvus 草稿 Collection。
- 正式层继续使用共享的 ES 正式索引和 Milvus 正式 Collection。
- 每条索引记录继续携带 `tenantId`、`knowledgeBaseId`、`documentId` 和 `versionId`。
- MySQL 保存当前草稿指针和当前 Release 指针，是版本可见性的唯一事实源。
- 外部索引先准备并校验，全部成功后再通过 MySQL 短事务原子切换 Release。
- 检索候选返回前必须以当前 Release 明细做批量终审。

该方案不会重新解析或重新向量化 Release 中未变化的文档；它们只是在新 Release 清单中继续引用原版本。

## 4. 数据模型

### 4.1 文档与版本

保留：

- `kb_document.current_draft_version_id`
- `kb_document.current_published_version_id`
- `kb_document.row_version`
- `kb_document_version.version_number`
- `kb_document_version.source_sha256`

为 `kb_document_version` 增加：

- `upload_request_id VARCHAR(64) NOT NULL`：上传幂等请求号。

增加唯一约束：

- `(tenant_id, upload_request_id)`：同一租户相同上传请求只创建一次。
- `(tenant_id, document_id, version_number)`：防止并发创建两个相同版本号。

文档身份只由明确的 `documentId` 决定。文件名、标题和 SHA-256 不用于猜测两次上传是否属于同一业务文档。

迁移旧数据时先以 `legacy-version-{versionId}` 为每条存量版本回填唯一的 `upload_request_id`，再增加非空和唯一约束，避免直接增加 `NOT NULL` 字段导致 Flyway 在有数据的环境中失败。

### 4.2 Release

新增 `kb_release`：

- `id`
- `tenant_id`
- `knowledge_base_id`
- `release_number`
- `status`：`PREPARING`、`ACTIVE`、`SUPERSEDED`、`FAILED`、`CONFLICT`
- `base_release_id`：准备本次发布时看到的活动 Release。
- `request_id`：发布幂等请求号。
- `manifest_sha256`：完整 Release 清单摘要。
- `created_by`
- `failure_code`
- `created_at`、`activated_at`、`updated_at`

唯一约束：

- `(tenant_id, knowledge_base_id, release_number)`
- `(tenant_id, request_id)`

新增 `kb_release_item`：

- `release_id`
- `tenant_id`
- `knowledge_base_id`
- `document_id`
- `version_id`
- `content_manifest_sha256`

唯一约束：

- `(release_id, document_id)`

新增 `kb_release_task`，作为异步发布的状态真相：

- `release_id`、`tenant_id`、`knowledge_base_id`
- `status`：`PENDING`、`RUNNING`、`RETRY`、`DONE`、`FAILED`
- `retry_count`、`next_run_at`
- `lease_token`、`locked_by`、`locked_until`
- `last_error_code`、`last_error_message`
- `created_at`、`updated_at`

`release_id` 唯一，一个 Release 只对应一个可恢复任务；RabbitMQ 仍然只负责快速唤醒，Worker 通过 MySQL 租约领取任务，消息重复、丢失或实例宕机都不改变任务事实。

在 `kb_knowledge_base` 增加：

- `current_release_id`
- 继续使用现有 `row_version` 作为发布乐观锁；若当前表尚无该字段则由迁移增加。

`current_published_version_id` 保留用于管理端快速展示和兼容文档级查询，但线上检索最终以 `current_release_id + kb_release_item` 为准。

迁移时，每个存在 `current_published_version_id` 的知识库都创建一个初始 `ACTIVE` Release，其 Item 来自该知识库所有未删除文档的当前发布指针，并回填 `current_release_id`。没有任何已发布文档的知识库保持 `current_release_id = NULL`。迁移完成后的首次线上检索与迁移前看到相同版本集合。

### 4.3 派生索引清理

新增 `kb_derived_index_cleanup`，并将现有正式索引清理记录迁移到该表。任务至少记录：

- 租户、知识库、文档和版本。
- 索引层：`DRAFT` 或 `PUBLISHED`。
- 清理原因：`DRAFT_REPLACED`、`INGESTION_FAILED`、`RELEASE_SUPERSEDED`、`RELEASE_FAILED`。
- 状态、重试次数、下次执行时间、租约令牌和最后错误码。

现有正式索引清理记录迁移为 `PUBLISHED` 层任务。清理操作必须先读取 MySQL 当前指针或当前 Release，仍被引用的版本不得删除。

## 5. API 与交互语义

### 5.1 上传新文档

```http
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents
```

创建新的 `kb_document` 和 v1。

### 5.2 上传已有文档的新版本

```http
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/versions
```

创建 v2、v3 等后续版本。后端锁定文档行后计算下一个版本号，并校验文档归属当前租户和知识库。

如果该文档已经存在相同 `source_sha256` 的版本，则返回 `409 DOCUMENT_CONTENT_UNCHANGED`，不重复创建、存储和索引。相同 `X-Request-Id` 重试时返回第一次创建的结果。

### 5.3 单文档发布

保留现有文档发布入口。内部不再直接切换单文档指针，而是创建一个只替换该文档版本的新 Release，然后走统一 Release 准备与激活流程。

### 5.4 批量发布

新增知识库级发布入口：

```http
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases
```

请求体提交一个或多个 `documentId + versionId`。后端基于当前活动 Release 生成完整新清单，选中的文档替换为目标版本，未选中的文档继续引用原版本。接口完成数据库登记后返回 `202 Accepted` 和 `PREPARING` Release；正式索引准备和激活由数据库任务 + Outbox 唤醒的 Release Worker 异步执行，任务状态仍以 MySQL 为准。

### 5.5 Release 历史与回滚

提供以下接口：

```http
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases/{releaseId}
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases/{releaseId}/rollback
```

回滚入口同样返回新建的 `PREPARING` Release，而不是修改目标历史记录。Worker 先重新准备可能已被清理的正式索引，成功后激活新 Release。

现有单文档发布、回滚和停用入口保留以兼容管理端调用，但内部全部转交 Release 服务，响应统一返回 Release 状态；不再允许任何入口绕过 Release 直接修改线上指针。

## 6. 文档版本生命周期

### 6.1 创建版本

1. 校验管理权限、文件类型、大小和内容签名。
2. 使用 `X-Request-Id` 查询已有版本；存在则幂等返回。
3. 锁定 `kb_document`。
4. 拒绝同文档相同 SHA-256 内容。
5. 计算 `MAX(version_number) + 1`，唯一约束作为并发最终防线。
6. 在同一个数据库事务中新增版本、解析任务与 Outbox 事件，并生成不包含用户文件名的确定性 MinIO 对象键。
7. 在外层事务提交前把原文件写入该对象键；MinIO 写入失败则抛出异常并回滚数据库记录。
8. 数据库事务提交后，由 Outbox 快速唤醒异步解析 Worker。

MinIO 与 MySQL 不做分布式事务。极少数“对象已写成功但数据库提交失败”的对象不会被任何有效版本引用，由孤儿对象扫描任务按对象创建时间和数据库引用关系延迟清理；扫描必须保留安全时间窗，避免删除尚未提交的上传对象。

### 6.2 草稿构建

异步流水线继续执行解析、OCR、切片、Embedding、ES 草稿写入和 Milvus 草稿写入。只有 MySQL Chunk、ES 和 Milvus 的数量与内容指纹完全一致时，才允许把版本标记为 `READY`。

标记 `READY` 与切换 `current_draft_version_id` 在同一 MySQL 短事务完成。事务还需确认当前版本仍是该文档最新创建的候选版本，防止较旧任务延迟完成后覆盖更新版本。

切换成功后，为此前的草稿版本登记 `DRAFT_REPLACED` 清理任务。

### 6.3 失败版本

流水线失败时不改变当前草稿与当前 Release。任务可按原有退避策略重试；耗尽重试后，版本保留失败状态用于诊断，并登记 `INGESTION_FAILED` 清理任务删除 ES/Milvus 部分写入。

## 7. Release 发布流程

### 7.1 创建准备态 Release

在短事务中：

1. 锁定知识库并读取 `current_release_id + row_version`。
2. 校验目标版本均为 `READY`、属于该知识库且内容清单未变化。
3. 复制当前 Release 全部条目。
4. 用本次选择的文档版本覆盖相应条目。
5. 插入 `PREPARING` Release 和完整 Release Item 清单。
6. 如果新清单摘要与当前 Release 完全相同，返回 `409 RELEASE_NO_CHANGES`，不创建空操作 Release。
7. 插入唯一的 Release Task 和 Outbox 事件。
8. 记录 `base_release_id` 与准备时的知识库 `row_version`。

首个 Release 没有基础清单，只包含本次明确选择发布的文档。

### 7.2 准备正式索引

事务外只处理发生变化的版本：

1. 从 MySQL 重新读取 Chunk。
2. 校验 Chunk 数量和版本内容清单。
3. 校验 Embedding 模型、维度和指令版本。
4. 生成向量并替换该版本的 ES/Milvus 正式层数据。
5. 对两套索引执行完整指纹校验。

任何一步失败都不得切换 `current_release_id`。Release 标记为 `FAILED`，并登记 `RELEASE_FAILED` 清理任务。

### 7.3 原子激活

外部索引全部准备成功后，在第二个 MySQL 短事务中：

1. 锁定知识库。
2. 确认当前 `current_release_id` 仍等于 `base_release_id`。
3. 使用准备时的 `row_version` 做条件更新。
4. 再次校验目标文档版本、修订号和内容清单。
5. 旧 Release 改为 `SUPERSEDED`。
6. 新 Release 改为 `ACTIVE`。
7. 原子切换 `kb_knowledge_base.current_release_id`。
8. 同一事务更新受影响文档的 `current_published_version_id` 和版本状态。
9. 写入发布审计和后续清理任务。

条件不匹配时，新 Release 标记为 `CONFLICT`，旧 Release 继续生效。

## 8. 检索可见性

线上检索仍先查询正式 ES 和 Milvus，执行 RRF 与重排。候选返回前必须批量查询：

```text
kb_knowledge_base.current_release_id
    -> kb_release_item(document_id, version_id)
```

只返回属于当前活动 Release 的 `documentId + versionId`。因此：

- 新版本已预写正式索引但 Release 未激活时不可见。
- 旧版本尚未物理清理时不可见。
- 失败或冲突 Release 的数据不可见。
- 多文档发布只在 `current_release_id` 成功切换后整体可见。

草稿检索继续使用 `current_draft_version_id` 终审，只供管理端诊断。

## 9. 回滚与停用

### 9.1 文档级回滚

选择历史文档版本后，创建只替换该文档的新 Release，并执行完整索引准备和原子激活流程。

### 9.2 知识库级回滚

选择历史 Release 后，以其清单作为目标快照。系统先重建或校验全部缺失的正式索引，再创建一个新的 Release 激活。历史记录本身保持不可变。

### 9.3 停用文档

创建一个从当前清单移除目标文档的新 Release。激活后该文档不再通过线上终审，其旧正式索引异步清理。

## 10. 幂等与并发

- 前端为一次上传或发布动作生成稳定请求号，在 CSRF 刷新和明确网络重试中复用。
- 后端以唯一约束保证同一请求只产生一个版本或 Release。相同请求号且请求目标、内容摘要或发布清单完全一致时幂等返回第一次结果；相同请求号被用于不同内容时返回 `409 IDEMPOTENCY_KEY_REUSED`，不能静默复用旧结果。
- 文档行锁和版本号唯一约束共同保护版本递增。
- 知识库行锁、`base_release_id` 和 `row_version` 共同保护 Release 激活。
- 外部索引替换以租户、文档和版本为范围，重复执行得到相同结果。
- 清理任务使用租约、条件更新与有限重试，避免多个实例重复处理。
- 两个并发 Release 可以分别完成外部索引准备，但只有仍基于当前活动 Release 的一个能激活；另一个进入 `CONFLICT`，其预写数据不可见并进入清理流程。

## 11. 管理端设计

### 11.1 文档列表

- 保留“上传新文档”。
- 每个文档卡片分别展示线上版本、当前可用草稿和最新处理版本。
- READY 草稿可勾选并执行“批量发布”。
- 不再只显示一个容易混淆的版本状态。

### 11.2 文档详情

- 增加“上传新版本”。
- 保留版本历史、人工校正、重试和单文档发布。
- 相同内容、并发冲突和处理失败显示稳定中文错误码与建议动作。

### 11.3 Release 历史

- 展示 Release 编号、状态、清单摘要、涉及文档、操作人和时间。
- `ACTIVE` 明确标记为当前线上版本。
- 历史 Release 提供回滚操作。

## 12. 测试策略

所有行为按测试驱动方式实现：

1. Flyway 迁移测试覆盖表、字段、唯一约束、外键和旧数据迁移。
2. 仓储集成测试覆盖 v2/v3 递增、并发唯一性、SHA-256 去重和请求幂等。
3. 服务测试覆盖新文档与新版本入口隔离、MinIO 失败回滚和 Outbox 登记。
4. 草稿流水线测试覆盖三方校验后切换指针、延迟任务保护和失败残留登记。
5. 清理 Worker 测试覆盖 DRAFT/PUBLISHED、安全门禁、租约和重试。
6. Release 服务测试覆盖单文档、批量发布、首次发布、并发冲突和失败不切指针。
7. 回滚测试覆盖缺失索引重建后激活，以及失败时保持旧 Release。
8. 检索测试覆盖只允许当前 Release Item 通过终审。
9. 前端测试覆盖上传新版本、双状态展示、批量发布、历史 Release 和中文错误提示。
10. 完整回归测试后，在本地实际上传 PDF，验证 v1 → v2、批量发布、查询隔离与回滚。

## 13. 验收标准

- 同一文档能够连续创建 v1、v2、v3，且不会产生重复 `kb_document`。
- 相同内容不会创建新版本，相同请求重试不会重复创建。
- 新版本失败或未 READY 时，旧草稿和旧 Release 保持可用。
- 单文档发布和多文档发布都生成 Release。
- 多文档 Release 激活前全部使用旧清单，激活后全部使用新清单。
- ES/Milvus 中的预写或残留版本无法绕过 MySQL Release 终审。
- 草稿和正式层无效数据能够异步、安全、可重试地清理。
- 管理端能够清楚区分线上、草稿和处理中版本，并查看与回滚 Release。
