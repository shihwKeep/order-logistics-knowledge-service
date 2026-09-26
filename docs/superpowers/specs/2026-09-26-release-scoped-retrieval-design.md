# ACTIVE Release 前置过滤检索设计

## 1. 背景

当前线上检索先按租户、知识库范围和 `PUBLISHED` 索引层查询 Elasticsearch 与 Milvus，经过 RRF 融合和 BGE 重排后，再由 MySQL 当前 `ACTIVE Release` 清单做最终门禁。

该实现能够阻止旧版本证据返回给调用方，但旧 Release 的文档版本仍可能先进入两路召回并占用 TopK，随后才被 MySQL 门禁删除。有效版本因此可能没有进入候选集，造成结果数量减少或者无证据。

## 2. 目标

- 在线召回前读取当前 `ACTIVE Release` 的不可变文档版本清单。
- Elasticsearch 与 Milvus 只召回清单中的 `documentId + versionId`。
- 保留 MySQL 最终门禁，继续作为发布一致性的最终正确性边界。
- 检索期间发生 Release 切换时，自动基于最新 Release 完整重试一次。
- 避免为每个业务 Release 新建独立 ES Index 或 Milvus Collection。
- 草稿诊断检索保持现有行为，不受此次调整影响。

## 3. 非目标

- 不调整文档解析、切片、Embedding、RRF 或 BGE 算法。
- 不改变知识库 Release 的创建、准备、发布和回滚语义。
- 不在本次改动中启用历史索引清理 Worker。
- 不实现每个 Release 独立物理索引，也不处理 Embedding 维度或索引 Schema 的代次迁移。

## 4. 方案选择

采用“共享物理索引 + ACTIVE Release 范围前置过滤 + MySQL 后置门禁”。

日常文档发布继续复用草稿层和发布层 ES Index、Milvus Collection。只有 Embedding 模型维度、Milvus Schema 或 ES Mapping 发生不兼容变化时，才新建一代物理索引并执行蓝绿迁移。

不选择每个 Release 独立物理索引，因为它会重复存储大量未变化 Chunk，并使发布耗时、存储成本和索引生命周期管理随 Release 数量持续增长。

## 5. 核心模型

新增不可变的线上检索范围对象，至少包含：

- 租户 ID；
- 被查询的知识库 ID；
- 每个知识库在请求开始时的 `releaseId`；
- 每个 Release 允许的 `documentId + versionId` 集合。

范围对象由 MySQL 单条批量查询构建。查询通过：

```text
kb_knowledge_base.current_release_id
    -> kb_release（状态必须为 ACTIVE）
    -> kb_release_item
```

一次性返回知识库、Release 和文档版本清单，避免先读取指针、再读取清单时出现非一致快照。

调用方未指定知识库时，范围加载器读取该租户下所有已启用且存在 ACTIVE Release 的知识库。不存在有效 Release 或清单为空时直接返回无证据，不执行无边界召回。

## 6. 检索流程

线上 `PUBLISHED` 检索调整为：

1. 校验租户、用户、请求号、问题和知识库范围。
2. 生成查询向量；发布切换重试时复用同一个向量，避免重复产生云模型费用。
3. 从 MySQL 批量加载 ACTIVE Release 范围快照。
4. 将 `documentId + versionId` 范围下推到 ES 和 Milvus。
5. 两路候选执行 RRF 融合。
6. 执行 BGE 重排和分数门槛。
7. 在返回前检查本轮范围中的各个 `releaseId` 是否仍是对应知识库的当前 ACTIVE Release，并校验候选仍属于该清单。
8. Release 未变化时返回最终证据；发生变化时重新加载最新范围并完整重试一次。
9. 第二次尝试仍发生切换时返回可重试的“知识库正在发布”业务错误，不返回可能不一致的证据。

草稿层继续使用 `current_draft_version_id` 校验，不加载 Release 范围。

## 7. ES 与 Milvus 范围下推

本次复用索引中现有的 `documentId/document_id` 和 `versionId/version_id` 字段，不新增 Milvus Schema 字段，也不要求重建现有索引。

ES 为每个文档版本生成一组精确过滤条件：

```text
(documentId = D1 AND versionId = V1)
OR (documentId = D2 AND versionId = V2)
```

Milvus使用语义等价的标量过滤表达式：

```text
(document_id == D1 && version_id == V1)
|| (document_id == D2 && version_id == V2)
```

租户和知识库过滤仍然保留，文档版本条件是附加约束，不能代替租户隔离。

## 8. 大清单与批量检索

ES 布尔条件和 Milvus 标量表达式都存在长度与复杂度上限，因此不能把任意大的 Release 清单拼成一次请求。

- 文档版本清单按照可配置批量大小拆分。
- 每一批分别执行 ES 和 Milvus TopK 查询。
- 适配器内部按 Chunk ID 去重，并按原始召回分数合并为全局 TopK。
- 批量大小必须有启动时配置校验，非法值立即失败。
- 记录范围大小、批次数量和查询耗时，支持后续根据实际知识库规模调优。

批量查询只解决查询表达式规模，不改变最终 RRF、重排和门禁语义。

## 9. 发布并发语义

采用“最新 Release 优先，最多自动重试一次”。

例如第一次检索加载 Release A，检索过程中知识库切换为 Release B：

- 第一次候选不会直接返回；
- 系统复用查询向量，加载 Release B 的清单并重新执行双路召回、融合、重排和门禁；
- 第二次范围稳定则返回 B 的证据；
- 第二次仍发生切换则返回可重试业务错误。

多知识库查询中，只要任意一个知识库的 Release 指针发生变化，本轮范围即视为失效，全部重试，避免一次回答混用不同时间点的清单。

## 10. 最终门禁

最终门禁继续保留，但需要接收本轮期望的 `releaseId` 集合：

- 确认每个知识库的 `current_release_id` 与本轮快照一致；
- 确认 Release 状态仍为 `ACTIVE`；
- 确认每条候选的 `documentId + versionId` 属于对应 Release 清单；
- 继续校验候选租户，防止索引脏数据或调用错误造成跨租户泄漏。

前置过滤负责召回质量，后置门禁负责最终正确性，两者不能互相替代。

## 11. 故障与降级

- ACTIVE Release 范围读取失败：不允许退化为无版本范围检索，返回知识服务不可用。
- Release 清单为空：返回无可靠证据，不访问 ES 和 Milvus。
- ES 或 Milvus 单路失败：继续沿用现有单路降级，但剩余召回必须带 Release 范围。
- 两路均失败：继续返回知识服务不可用。
- Release 连续两次变化：返回可重试的“知识库正在发布”错误。
- BGE 不可用：继续保守拒答，不把仅有 RRF 排名的结果直接交给模型。

## 12. 缓存边界

第一阶段不缓存 Release 清单，以 MySQL 单次批量查询保证实现正确、便于验证。后续如果观测到数据库压力，再增加以 `tenantId + knowledgeBaseId + releaseId` 为键的不可变清单缓存。

缓存只能作为加速层；知识库当前 `releaseId` 仍必须来自 MySQL，最终门禁也不能依赖缓存。

## 13. 可观测性

新增或补充以下指标和结构化日志：

- 本次查询使用的知识库及 Release 编号；
- Release 文档版本数量；
- ES、Milvus范围批次数量；
- 因 Release 切换触发的自动重试次数；
- 连续切换失败次数；
- 门禁过滤前后候选数量。

日志不记录完整问题正文、Chunk正文或敏感业务数据。

## 14. 测试策略

按照测试驱动方式实现，至少覆盖：

1. MySQL 能一次加载多个知识库的 ACTIVE Release 和版本清单。
2. ES 查询只包含允许的文档版本组合。
3. Milvus 查询只包含允许的文档版本组合。
4. 大清单分批查询后能够去重并合并全局 TopK。
5. 旧版本即使分数更高，也不会进入召回候选。
6. 线上检索使用前置范围，草稿检索保持原行为。
7. 第一次检索发生 Release 切换时自动重试一次，并复用查询向量。
8. 第二次仍切换时返回明确可重试错误。
9. 多知识库中任一 Release 变化都会使整个范围失效。
10. 最终门禁继续阻止跨租户、旧版本和脏索引证据。
11. ES、Milvus单路降级仍携带同一Release范围。
12. 现有检索、发布、回滚和索引恢复测试继续通过。

## 15. 验收标准

- 在旧版本Chunk得分高于当前版本的测试数据中，最终召回阶段不再出现旧版本候选。
- 发布切换前后，返回结果只属于一个稳定的 ACTIVE Release。
- 发布并发最多触发一次自动重试，不产生无限循环。
- 不为业务Release创建新的ES Index或Milvus Collection。
- 现有草稿诊断、RRF、BGE、引用和故障降级行为不回归。

