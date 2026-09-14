# 百炼检索模型迁移与派生索引重建手册

## 安全边界

- MySQL 中的知识文档、版本、Chunk 与 Agent 用户记忆是源数据，迁移不得删除。
- MinIO 原始文件不得删除。
- Elasticsearch 和 Milvus 仅允许删除代码白名单中的 v1/v2 派生索引。
- 真实 API Key 只进入 Knowledge 服务进程环境变量，不写入 Git、Nacos 明文、日志或截图。
- 月预算配置为 200 元，系统硬停止线为 180 元；金额统一使用微元整数。

## Knowledge Nacos 配置

在 `order-logistics-agent-dev` 命名空间的 Knowledge Data ID 中合并以下配置：

```yaml
knowledge:
  cloud-model:
    enabled: true
    region: cn-beijing
    workspace-id: ${KNOWLEDGE_DASHSCOPE_WORKSPACE_ID}
    api-key: ${KNOWLEDGE_DASHSCOPE_API_KEY}
    embedding-model: qwen3.7-text-embedding
    reranker-model: qwen3.7-text-rerank
    embedding-dimension: 2560
    embedding-batch-size: 20
    max-attempts: 3
    initial-backoff: 200ms
    connect-timeout: 3s
    read-timeout: 30s
    monthly-budget-micros: 200000000
    hard-limit-micros: 180000000
    embedding-price-micros-per-million-tokens: 500000
    reranker-price-micros-per-million-tokens: 500000
  document:
    ingestion:
      budget-retry-delay: 1h
  retrieval:
    elasticsearch:
      draft-index: knowledge_chunks_draft_v2
      published-index: knowledge_chunks_published_v2
    milvus:
      draft-collection: knowledge_chunks_draft_v2
      published-collection: knowledge_chunks_published_v2
      dimension: 2560
    embedding:
      model: qwen3.7-text-embedding
      dimension: 2560
      batch-size: 20
      instruction-version: qwen37-customer-service-v2
    reranker:
      enabled: true
    strategy:
      version: qwen37-es-milvus-rrf60-rerank-v3
  user-memory:
    enabled: true
    elasticsearch:
      index-alias: agent-user-memory-active
      index-name: agent-user-memory-v2
    milvus:
      collection: agent_user_memory_v2
      dimension: 2560
  maintenance:
    reset-derived-indexes-on-startup: false
    reset-confirmation: ""
    rebuild-indexes-on-startup: false
```

Knowledge 服务进程账户必须另外设置：

```text
KNOWLEDGE_DASHSCOPE_WORKSPACE_ID=<百炼业务空间 ID>
KNOWLEDGE_DASHSCOPE_API_KEY=<该业务空间 API Key>
```

Agent Nacos 正常运行时保持：

```properties
agent.memory.maintenance.reindex-on-startup=false
agent.memory.maintenance.reindex-confirmation=
agent.memory.maintenance.batch-size=500
```

## 重建前检查

1. 记录知识库、文档版本、Chunk、当前发布版本、ACTIVE 用户记忆及 Outbox `PENDING/RETRY` 数量。
2. 确认解析源数据和 MinIO 原件可读取。
3. 确认百炼凭据可用、额度账本未达到 180 元硬停止线。
4. 停止 Agent 与 Knowledge 应用进程；保留 MySQL、MinIO、Elasticsearch、Milvus、Redis、RabbitMQ、网关和认证服务。
5. 核对待删目标只能是：
   - ES：`knowledge_chunks_draft_v1`、`knowledge_chunks_published_v1`、`knowledge_chunks_draft_v2`、`knowledge_chunks_published_v2`、`agent-user-memory-v1`、`agent-user-memory-v2`
   - Milvus：`knowledge_chunks_draft_v1`、`knowledge_chunks_published_v1`、`knowledge_chunks_draft_v2`、`knowledge_chunks_published_v2`、`agent_user_memory_v1`、`agent_user_memory_v2`

## 一次性知识索引重建

临时设置并只启动一次 Knowledge：

```yaml
knowledge:
  maintenance:
    reset-derived-indexes-on-startup: true
    reset-confirmation: RESET_BAILIAN_V2_DERIVED_INDEXES
    rebuild-indexes-on-startup: true
```

确认日志中只有上述精确目标，并等待草稿、已发布索引均完成。若出现
`KNOWLEDGE_MODEL_BUDGET_EXHAUSTED`，保持业务流量关闭，待下月额度恢复或管理员调整预算后继续。

完成后立即恢复三个安全值并正常重启 Knowledge：

```yaml
knowledge:
  maintenance:
    reset-derived-indexes-on-startup: false
    reset-confirmation: ""
    rebuild-indexes-on-startup: false
```

## 一次性用户记忆重建

临时设置 Agent：

```properties
agent.memory.maintenance.reindex-on-startup=true
agent.memory.maintenance.reindex-confirmation=REINDEX_BAILIAN_V2_USER_MEMORY
agent.memory.maintenance.batch-size=500
```

启动 Agent，等待 ACTIVE 且未过期的记忆全部生成幂等 `UPSERT/PENDING` Outbox 事件。随后恢复前述正常配置并重启；等待新事件全部变为 `DONE`。

## 验收

- Knowledge `/actuator/health` 正常。
- ES/Milvus v2 数量与 MySQL 有效源数据一致。
- 三个已知知识问题返回已发布依据。
- 新会话可召回一条显式记忆和一条自动抽取记忆。
- 迁移前后 MySQL 文档/版本/Chunk/发布指针/ACTIVE 记忆数量不变；仅 Outbox 增加重建事件。
- `knowledge_cloud_model_budget` 中 `settled_micros + reserved_micros <= 180000000`。
- `knowledge_cloud_model_call` 中 `UNKNOWN` 数量已核对；未知调用的预留金额不会被自动释放。
- 端口 11434 与本地 reranker 端口 8000 均不再是运行依赖。

## 回滚

如重建失败，保持流量关闭并关闭所有一次性维护开关。MySQL 和 MinIO 源数据不受影响，可修复百炼配置或依赖服务后重新执行幂等重建。不要把 v1 与 v2 的 2560 维向量混用，也不要手工修改额度账本。
