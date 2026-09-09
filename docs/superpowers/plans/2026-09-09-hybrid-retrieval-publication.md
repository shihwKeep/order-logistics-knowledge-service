# 混合检索、发布与回滚 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为知识库服务补齐 Qwen3 Embedding、Elasticsearch/Milvus 双路索引、RRF 与 BGE 精排、可靠性门控、手动发布和回滚，使 Agent 只能检索租户内当前已发布的可靠证据。

**Architecture:** MySQL 继续保存文档、版本、发布指针和任务真相；入库 Worker 在解析/分块后登记独立 INDEX 任务，把草稿写入独立 ES Index 和 Milvus Collection，双端校验成功才把版本置为 READY。发布或回滚先幂等写入线上双索引，再校验数量与内容清单，最后在 MySQL 短事务中切换发布指针；在线检索在 RRF、BGE 精排后必须批量回查 MySQL 当前发布版本，过滤索引残留。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、MySQL 8、Ollama `/api/embed`、Qwen3-Embedding-4B（2560 维）、Milvus Java SDK 3.0.5、Elasticsearch REST API + IK、BAAI/bge-reranker-v2-m3 HTTP 服务、JUnit 5、Testcontainers。

---

### Task 1: 增加发布与检索元数据

**Files:**
- Create: `src/main/resources/db/migration/V3__create_retrieval_publication.sql`
- Create: `src/test/java/com/xjjk/knowledge/persistence/RetrievalPublicationMigrationTest.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/domain/DocumentVersion.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/persistence/DocumentVersionEntity.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/persistence/DocumentMapper.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/persistence/MybatisDocumentRepository.java`

- [ ] **Step 1: 写迁移失败测试**

  使用 MySQL Testcontainers 执行 Flyway，并断言 `kb_document_version` 新增 `embedding_model`、`embedding_dimension`、`embedding_instruction_version`、`index_manifest_sha256`、`indexed_at`；断言新增 `kb_publish_record` 和 `kb_search_log`，且租户、文档、请求号和动作索引存在。

- [ ] **Step 2: 验证 RED**

  Run: `mvn -Dtest=RetrievalPublicationMigrationTest test`
  Expected: FAIL，原因是 V3 表或字段不存在。

- [ ] **Step 3: 编写 V3 迁移**

  `kb_publish_record` 固定保存 `tenant_id, knowledge_base_id, document_id, from_version_id, to_version_id, action, actor_user_id, request_id, chunk_count, manifest_sha256, created_at`；`action` 仅使用 `PUBLISH/ROLLBACK/DISABLE`。`kb_search_log` 只保存候选数量、结果码、降级模式和耗时，不保存问题或正文。

- [ ] **Step 4: 扩展版本领域对象映射**

  保持字段顺序在 Entity、Mapper SELECT 与 `DocumentVersion` record 中一致，并允许旧版本的嵌入元数据为空。

- [ ] **Step 5: 验证 GREEN 并提交**

  Run: `mvn -Dtest=RetrievalPublicationMigrationTest,DocumentRepositoryIntegrationTest test`
  Expected: PASS。

  Commit: `feat: add retrieval publication schema`

### Task 2: 实现 Qwen3 Embedding 客户端

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/embedding/EmbeddingClient.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/embedding/EmbeddingProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/embedding/OllamaEmbeddingClient.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/embedding/EmbeddingUnavailableException.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/embedding/OllamaEmbeddingClientTest.java`
- Modify: `src/main/resources/application-local.yml`

- [ ] **Step 1: 写客户端契约失败测试**

  用本地 HTTP stub 断言客户端向 `POST /api/embed` 发送模型 `qwen3-embedding:4b-q4_K_M`、文本数组、`truncate=false` 和 `dimensions=2560`；文档文本使用固定英文任务指令，查询文本使用固定英文查询指令；响应数量或维度不符必须抛出稳定异常。

- [ ] **Step 2: 验证 RED**

  Run: `mvn -Dtest=OllamaEmbeddingClientTest test`
  Expected: FAIL，原因是客户端不存在。

- [ ] **Step 3: 实现有界批量客户端**

  定义 `embedDocuments(List<String>)` 与 `embedQuery(String)`；默认单批最多 8 个 Chunk，JDK HttpClient 固定 HTTP/1.1，连接和读取超时由配置绑定；日志只写批量数、耗时和错误码。

- [ ] **Step 4: 验证 GREEN 并提交**

  Run: `mvn -Dtest=OllamaEmbeddingClientTest test`
  Expected: PASS。

  Commit: `feat: add qwen3 embedding client`

### Task 3: 实现 Elasticsearch 草稿与发布索引

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/model/IndexChunk.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/model/RecallCandidate.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/KeywordIndex.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/ElasticsearchProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/ElasticsearchKeywordIndex.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/index/ElasticsearchKeywordIndexTest.java`
- Modify: `src/main/resources/application-local.yml`

- [ ] **Step 1: 写 ES 契约失败测试**

  断言启动时创建 `knowledge_chunks_draft_v1` 与 `knowledge_chunks_published_v1`，正文/标题 mapping 使用 `ik_max_word`，查询使用 `ik_smart`；Bulk 文档主键严格为 `tenantId-documentId-versionId-chunkIndex`，所有查询都必须带 `tenantId`、可选知识库范围和逻辑层过滤。

- [ ] **Step 2: 验证 RED**

  Run: `mvn -Dtest=ElasticsearchKeywordIndexTest test`
  Expected: FAIL，原因是 ES 适配器不存在。

- [ ] **Step 3: 实现 REST 适配器**

  实现确保索引、幂等批量写入、按版本计数/清单校验、BM25 TopK、按版本删除。Bulk 响应任一 item 失败即整体失败，不允许把部分索引视为成功。

- [ ] **Step 4: 验证 GREEN 并提交**

  Run: `mvn -Dtest=ElasticsearchKeywordIndexTest test`
  Expected: PASS。

  Commit: `feat: add elasticsearch keyword index`

### Task 4: 实现 Milvus 2560 维向量索引

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/VectorIndex.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/MilvusProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/MilvusClientConfiguration.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/MilvusVectorIndex.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/index/MilvusVectorIndexTest.java`
- Modify: `src/main/resources/application-local.yml`

- [ ] **Step 1: 写 Milvus 领域契约失败测试**

  通过可替换网关断言两个 Collection 名称、VARCHAR 稳定主键、COSINE 指标、2560 维向量、租户/知识库/文档/版本标量字段、按租户与知识库过滤搜索、版本计数和删除表达式。

- [ ] **Step 2: 验证 RED**

  Run: `mvn -Dtest=MilvusVectorIndexTest test`
  Expected: FAIL，原因是 Milvus 适配器不存在。

- [ ] **Step 3: 添加 SDK 与适配器**

  使用 `io.milvus:milvus-sdk-java:3.0.5` 的 V2 client API；启动时核对 Collection schema，已存在但维度不为 2560 时 fail-fast，禁止混写不同语义空间。插入、搜索、计数与删除均显式指定数据库和 Collection。

- [ ] **Step 4: 验证 GREEN 并提交**

  Run: `mvn -Dtest=MilvusVectorIndexTest test`
  Expected: PASS。

  Commit: `feat: add milvus vector index`

### Task 5: 把 INDEX 阶段接入可靠入库任务

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/DraftIndexingService.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/ChunkIndexRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/index/ChunkIndexMapper.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/index/DraftIndexingServiceTest.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/IngestionArtifactRepository.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/IngestionArtifactMapper.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/IngestionWorker.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/service/DocumentCorrectionService.java`
- Modify: `src/test/java/com/xjjk/knowledge/document/task/IngestionPipelineIntegrationTest.java`

- [ ] **Step 1: 写 INDEX 状态机失败测试**

  断言 PARSE/CHUNK 成功后同事务登记稳定键 `INDEX:{tenantId}:{versionId}:{correctionRevision}`；INDEX Worker 分批生成向量，幂等覆盖草稿双索引，校验数量与 SHA-256 清单一致后把版本置为 READY；任一路失败保持非 READY 并进入现有重试/DEAD 链路。

- [ ] **Step 2: 验证 RED**

  Run: `mvn -Dtest=DraftIndexingServiceTest,IngestionPipelineIntegrationTest test`
  Expected: FAIL，原因是 INDEX 阶段未登记或未处理。

- [ ] **Step 3: 实现阶段衔接与索引服务**

  `ChunkIndexRepository` 按 `(tenantId, documentId, versionId)` 加载 Chunk 与标题/位置；manifest 按 chunkIndex 顺序拼接稳定 ID 与 content SHA-256 后再次 SHA-256。只有 ES 和 Milvus 的 count、manifest 都与 MySQL 一致才 `status='READY'`。

- [ ] **Step 4: 验证 GREEN 并提交**

  Run: `mvn -Dtest=DraftIndexingServiceTest,IngestionPipelineIntegrationTest,DocumentCorrectionServiceTest test`
  Expected: PASS。

  Commit: `feat: index draft versions through ingestion tasks`

### Task 6: 实现 RRF 与 BGE 精排

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/fusion/RrfFusion.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/rerank/Reranker.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/rerank/RerankerProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/rerank/BgeRerankerClient.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/model/RankedEvidence.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/fusion/RrfFusionTest.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/rerank/BgeRerankerClientTest.java`
- Modify: `src/main/resources/application-local.yml`

- [ ] **Step 1: 写 RRF 失败测试**

  断言按稳定 Chunk ID 去重，分数为各路 `weight/(60+rank)` 之和；同分按最佳原始名次和稳定 ID 排序；候选保留双路命中标记，供精排故障时严格门控。

- [ ] **Step 2: 验证 RED 并实现 RRF**

  Run: `mvn -Dtest=RrfFusionTest test`
  Expected: FAIL；完成实现后再次运行应 PASS。

- [ ] **Step 3: 写 BGE 契约失败测试**

  复用现有服务 `POST /rerank` 契约：`query/documents/topK`，响应 `results[index,relevanceScore]`；拒绝空响应、越界/重复下标和非 0..1 分数，日志不得输出 query/documents。

- [ ] **Step 4: 实现 BGE 客户端并验证 GREEN**

  Run: `mvn -Dtest=RrfFusionTest,BgeRerankerClientTest test`
  Expected: PASS。

  Commit: `feat: fuse and rerank retrieval candidates`

### Task 7: 实现在线检索、版本终审与受控降级

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/service/RetrievalProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/service/PublishedVersionValidator.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalService.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/model/RetrievalResult.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/model/DegradationMode.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalServiceTest.java`
- Modify: `src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java`

- [ ] **Step 1: 写正常与低置信度失败测试**

  断言正常流程为向量 Top30 + 关键词 Top30 → RRF Top20 → BGE → MySQL 批量校验 → Top5；所有分数低于 0.15 或终审后证据数为 0 时返回 `answerable=false`。

- [ ] **Step 2: 写故障矩阵失败测试**

  覆盖：ES 失败走 Milvus+BGE；Embedding/Milvus 失败走 ES+BGE；仅 Reranker 失败时只保留双路命中且 RRF 分数达到严格阈值的候选；Reranker 与任一路召回同时失败返回无可靠证据；双路召回失败返回 `KNOWLEDGE_SERVICE_UNAVAILABLE`。所有路径仍执行发布终审。

- [ ] **Step 3: 验证 RED**

  Run: `mvn -Dtest=HybridRetrievalServiceTest test`
  Expected: FAIL，原因是编排服务不存在。

- [ ] **Step 4: 实现编排和无正文检索日志**

  `PublishedVersionValidator` 一次 SQL 批量校验知识库启用、文档未删除和 `current_published_version_id=versionId`；检索日志仅记录 tenant、策略、候选数、answerable、结果码、降级模式和各阶段耗时。

- [ ] **Step 5: 验证 GREEN 并提交**

  Run: `mvn -Dtest=HybridRetrievalServiceTest test`
  Expected: PASS。

  Commit: `feat: add guarded hybrid retrieval`

### Task 8: 实现手动发布、回滚和停用

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/publication/PublicationRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/PublicationMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/PublicationService.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/PublicationAction.java`
- Create: `src/test/java/com/xjjk/knowledge/publication/PublicationServiceTest.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/web/DocumentController.java`
- Modify: `src/main/java/com/xjjk/knowledge/audit/AuditAction.java`
- Modify: `src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java`

- [ ] **Step 1: 写发布与回滚失败测试**

  断言只有当前 READY 草稿可首次发布；发布先复制草稿到线上双索引并校验，再通过带 row_version 的短事务切换指针；回滚允许目标 READY/PUBLISHED/ARCHIVED 历史版本，顺序相同；请求号重复不能生成第二条发布记录。

- [ ] **Step 2: 写可用性失败测试**

  模拟 ES/Milvus 写入或校验失败，断言旧指针不变；切换后清理旧索引失败不回滚指针，只登记清理任务/错误；停用先清理 MySQL 发布有效性，再异步删索引。

- [ ] **Step 3: 验证 RED**

  Run: `mvn -Dtest=PublicationServiceTest test`
  Expected: FAIL，原因是发布服务不存在。

- [ ] **Step 4: 实现服务与管理动作接口**

  增加 `POST .../versions/{versionId}/publish`、`POST .../versions/{versionId}/rollback` 和 `POST .../{documentId}/disable`；沿用 AdminPrincipal/TenantAccessGuard，审计记录操作者租户与目标租户。

- [ ] **Step 5: 验证 GREEN 并提交**

  Run: `mvn -Dtest=PublicationServiceTest,DocumentControllerTest test`
  Expected: PASS。

  Commit: `feat: publish rollback and disable knowledge versions`

### Task 9: 暴露稳定检索与管理诊断 API

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/web/InternalRetrievalController.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/web/AdminRetrievalController.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/web/dto/RetrieveKnowledgeRequest.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/web/dto/RetrieveKnowledgeResponse.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/web/InternalRequestVerifier.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/web/InternalApiProperties.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/web/InternalRetrievalControllerTest.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/web/AdminRetrievalControllerTest.java`
- Modify: `src/main/java/com/xjjk/knowledge/auth/web/SecurityConfiguration.java`
- Modify: `src/main/resources/application-local.yml`

- [ ] **Step 1: 写内部鉴权失败测试**

  请求必须携带时间戳、nonce、tenant/user 声明和 HMAC-SHA256 签名；拒绝过期、重放、伪造和空问题。响应只包含 `answerable/evidences/strategy/degradation/resultCode`，引用位置均来自索引元数据。

- [ ] **Step 2: 写管理检索失败测试**

  管理接口允许系统管理员检索本租户草稿或发布层，超级管理员可显式选租户；普通管理员伪造 tenantId 必须拒绝。

- [ ] **Step 3: 验证 RED**

  Run: `mvn -Dtest=InternalRetrievalControllerTest,AdminRetrievalControllerTest test`
  Expected: FAIL，原因是接口或鉴权器不存在。

- [ ] **Step 4: 实现接口并验证 GREEN**

  Run: `mvn -Dtest=InternalRetrievalControllerTest,AdminRetrievalControllerTest,AdminSecurityTest test`
  Expected: PASS。

  Commit: `feat: expose authenticated knowledge retrieval`

### Task 10: 补齐本地基础设施和运行文档

**Files:**
- Modify: `compose.knowledge.yml`
- Create: `infra/elasticsearch/Dockerfile`
- Create: `docs/hybrid-retrieval-publication-runbook.md`
- Modify: `docs/versioned-ingestion-runbook.md`
- Modify: `src/test/java/com/xjjk/knowledge/config/ConfigurationContractTest.java`

- [ ] **Step 1: 写配置契约失败测试**

  断言模型、维度、双索引名、双 Collection 名、Top30/20/5、RRF 常数 60、阈值、超时、内部签名密钥均可配置；敏感值只能引用环境变量。

- [ ] **Step 2: 验证 RED**

  Run: `mvn -Dtest=ConfigurationContractTest test`
  Expected: FAIL，原因是检索配置未声明。

- [ ] **Step 3: 扩展 Compose 与 Runbook**

  Compose 增加带匹配版本 IK 插件的 Elasticsearch、Milvus Standalone 及其 etcd/内部 MinIO；不复用文档 MinIO。Runbook 包含 `ollama pull qwen3-embedding:4b-q4_K_M`、BGE 服务地址、启动顺序、健康检查、建库/发布/回滚/检索验收和故障矩阵。

- [ ] **Step 4: 全量验证**

  Run: `mvn clean test`
  Expected: 全部测试 PASS。

  Run: `docker run --rm -v "${PWD}/ocr-service:/workspace" -w /workspace python:3.11-slim python -m unittest discover -s tests -v`
  Expected: 2 tests OK。

  Run: `docker compose -f compose.knowledge.yml config --quiet`
  Expected: exit 0。

  Run: `git diff --check`
  Expected: 无输出。

- [ ] **Step 5: 提交**

  Commit: `docs: add hybrid retrieval operations`

## 计划自检

- [x] 覆盖草稿/发布物理隔离、Qwen3 2560 维、ES IK、Milvus、RRF、BGE 和低置信度拒答。
- [x] 覆盖发布/回滚顺序、旧版本持续可用、最终 MySQL 版本校验和失效索引清理。
- [x] 覆盖全部设计降级路径，明确 Reranker 降级必须双路命中。
- [x] 覆盖管理检索和 Agent 内部接口边界、租户鉴权、重放防护与敏感日志约束。
- [x] 字段、类型和方法命名在前后任务中保持一致，无 TBD/TODO 占位。
