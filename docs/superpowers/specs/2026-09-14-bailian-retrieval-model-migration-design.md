# 百炼知识检索模型迁移与硬预算设计

## 1. 背景

当前 Knowledge Service 使用本机 Ollama 承载 `qwen3-embedding:4b-q4_K_M`，使用本机 `bizagent-reranker` 承载 `BAAI/bge-reranker-v2-m3`。本地模型会持续占用较多内存和计算资源，并且任一进程未启动都会导致知识检索降级或拒答。

本次将 Embedding 与 Reranker 全部迁移到阿里云百炼华北2（北京），迁移完成后不再依赖本机 Ollama 和本地 Reranker。新增模型费用按月硬限制，配置预算为 200 元，系统在累计预估达到 180 元时停止新的 Embedding 和 Reranker 调用。现有 `qwen-plus` 聊天、摘要和记忆抽取费用不纳入本额度。

本次先完成云模型迁移、预算控制和索引重建。PDF 知识库解析作为迁移后的专项验收阶段执行，不改变现有 MinIO、OCR、解析和分块职责。

## 2. 已确认决策

- Embedding：`qwen3.7-text-embedding`，2560维。
- Reranker：`qwen3.7-text-rerank`。
- 地域：华北2（北京）。
- Knowledge Service 使用独立的百炼 API Key 和业务空间。
- 月度配置预算：200元；系统硬停止线：180元。
- 预算只统计 Embedding 与 Reranker。
- 额度耗尽后失败关闭，不使用未经精排的候选回答。
- 索引迁移采用停机直接重建，允许迁移期间知识检索与跨会话向量召回暂时不可用。
- MySQL 原始业务数据和 MinIO 原始文件不得删除；只删除可重建的 ES/Milvus 派生索引。
- 用户记忆重建通过 Agent Outbox 重新投递，Knowledge Service 不直接读取 Agent 数据库。

## 3. 方案比较

### 方案一：质量优先（采用）

使用 `qwen3.7-text-embedding` 2560维与 `qwen3.7-text-rerank`。两者均面向多语言和长文本检索，当前北京地域公开原价均为每百万输入 Token 0.5元。2560维与当前 Milvus 维度一致，适合后续 PDF 长文本检索。

### 方案二：成本优先

使用 `qwen3.7-text-embedding-flash` 1024维与 `qwen3-rerank`。Embedding 单价更低，但需要调整 Milvus 维度，且本项目当前规模下节省金额有限，不值得以检索质量为代价。

### 方案三：兼容优先

使用 `text-embedding-v4` 1024维与 `qwen3-rerank`。接口成熟，但仍需修改维度并重建，价格也没有优于方案一，因此不采用。

官方接口和模型能力依据：

- https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/
- https://help.aliyun.com/zh/model-studio/text-rerank-api
- https://help.aliyun.com/zh/model-studio/model-pricing

## 4. 目标架构

```text
用户问题
  ├─ 百炼 qwen3.7-text-embedding（query，2560维）
  │    └─ Milvus 向量召回 Top30
  ├─ Elasticsearch IK BM25 召回 Top30
  └─ RRF 融合 Top10
       └─ 百炼 qwen3.7-text-rerank
            └─ MySQL 发布指针终审
                 └─ Top5 可靠证据
```

文档入库和用户记忆索引也复用同一个 `EmbeddingClient` 接口：

```text
文档分块 / 用户记忆正文
  → 百炼 qwen3.7-text-embedding（document，2560维）
  → Milvus
```

PDF 原文件继续保存在 MinIO，解析和 OCR 在本地 Knowledge Service 体系内完成。只有解析后的文本 Chunk 会发送到百炼。用户记忆正文也会发送到百炼生成向量。

## 5. 云端客户端边界

保留现有 `EmbeddingClient` 和 `Reranker` 领域接口，分别增加百炼适配器。适配器继续使用 Java `HttpClient`，不引入新的厂商 SDK，避免 SDK 类型渗入业务层。

### 5.1 Embedding

使用百炼 DashScope 原生同步接口，显式区分非对称检索的文本类型：

- 文档和用户记忆：`text_type=document`；
- 在线问题：`text_type=query`；
- query 使用固定英文 `instruct`；
- 模型固定为 `qwen3.7-text-embedding`；
- 维度固定为2560；
- 单批最多20条；
- 必须验证返回条数、输入索引、向量维度、有限浮点数、模型名称、请求ID和 `usage.total_tokens`。

### 5.2 Reranker

使用 `qwen3.7-text-rerank`：

- 输入为用户问题与 RRF Top10 候选正文；
- 输出 Top5；
- 必须验证返回索引不越界、不重复，相关性分数为有限值且符合接口范围；
- 保留可配置相关性阈值，不能直接把云模型分数当作可信结论；
- 必须读取百炼请求ID和实际 Token 用量。

### 5.3 密钥与配置

API Key 只从运行环境读取，不进入 Nacos 明文、源码、日志或响应：

```properties
knowledge.retrieval.cloud.provider=BAILIAN
knowledge.retrieval.cloud.api-key=${KNOWLEDGE_DASHSCOPE_API_KEY}
knowledge.retrieval.cloud.workspace-id=${KNOWLEDGE_DASHSCOPE_WORKSPACE_ID}
knowledge.retrieval.cloud.region=cn-beijing
knowledge.retrieval.embedding.model=qwen3.7-text-embedding
knowledge.retrieval.embedding.dimension=2560
knowledge.retrieval.reranker.model=qwen3.7-text-rerank
```

URL 由受支持地域与经过格式校验的 Workspace ID 构造，禁止将完整任意 URL 与凭据一起作为动态输入。生产启动时必须校验 Key、Workspace、模型、维度、价格和预算配置。

## 6. 月度硬预算

### 6.1 数据模型

MySQL 新增月度预算账户和物理调用流水。

月度账户以供应商和上海时区账期为唯一键，保存：配置预算微元数、硬停止线微元数、已确认费用、已预占费用和乐观锁版本。

调用流水保存：调用ID、账期、模型类型、模型、物理尝试序号、预占费用、实际Token、实际费用、状态、百炼请求ID、错误分类、创建时间和完成时间。金额统一使用整数微元，禁止使用浮点数计算费用。

流水状态：

- `RESERVED`：已预占，尚未发起或等待响应；
- `SETTLED`：获得明确响应并按实际 Token 结算；
- `RELEASED`：可证明百炼未执行，释放预占；
- `UNKNOWN`：超时、连接中断或进程异常，无法确认是否计费，保留预占。

### 6.2 预占与结算

每一次物理 HTTP 尝试都必须独立预占。条件更新必须保证：

```text
confirmed_cost + reserved_cost + current_reservation <= 180元
```

预占与流水创建在同一短事务完成。百炼调用不进入数据库事务。成功后使用官方响应中的实际 Token 结算并释放差额。HTTP 400、401、403等能确认未执行的请求可释放；网络超时或连接结果不确定时转为 `UNKNOWN`，继续占用预算。进程崩溃遗留的 `RESERVED` 不自动释放，必须保守转为 `UNKNOWN` 或经人工账单核对处理。

账期按 `Asia/Shanghai` 的 `yyyy-MM` 计算。次月创建新账户，旧账期不阻塞新调用。20元安全余量覆盖并发在途调用、Token估算和价格调整；系统硬额度以配置价格为准，实际账单仍以百炼控制台为准。

### 6.3 额度耗尽行为

- 在线知识检索返回 `KNOWLEDGE_MODEL_BUDGET_EXHAUSTED`；
- 文档仍可上传、保存和解析，但索引任务暂停，不进入可发布状态；
- 用户记忆继续保存在 Agent MySQL，索引 Outbox 保留并延迟重试；
- 不自动切回 Ollama、本地 Reranker或未经精排的 RRF 结果；
- 用户端显示“本月知识检索模型额度已用完，暂时无法查询知识库，请联系管理员”。

## 7. 重试、超时与错误

HTTP 400、401、403不重试；HTTP 429、502、503、504及瞬时网络错误最多重试3次并采用有上限的指数退避。每次重试都是新的物理调用和预算预占。Embedding 与 Reranker 继续使用独立连接、读取超时。

稳定错误分类：

- `KNOWLEDGE_MODEL_AUTH_FAILED`；
- `KNOWLEDGE_MODEL_RATE_LIMITED`；
- `KNOWLEDGE_MODEL_TIMEOUT`；
- `KNOWLEDGE_MODEL_RESPONSE_INVALID`；
- `KNOWLEDGE_MODEL_BUDGET_EXHAUSTED`；
- `KNOWLEDGE_INDEX_REBUILD_REQUIRED`。

Embedding 故障时允许 ES 关键词召回继续产生候选，并由云端 Reranker 判断证据。Reranker 故障或预算耗尽时失败关闭，不把未精排内容交给回答模型。

日志只记录模型、调用ID、百炼请求ID、Token、费用、耗时、状态和错误分类，不记录用户问题、知识正文、记忆正文或 API Key。

## 8. 停机直接重建

迁移期间停止 Agent 到 Knowledge Service 的检索流量，并关闭文档入库 Worker。删除动作只作用于可重建的索引，禁止删除 MySQL 原始文档、Chunk、发布指针、用户记忆事实和 MinIO 文件。

步骤：

1. 使用短文本完成百炼 Key、Workspace、Embedding、Reranker和预算预占/结算预检。
2. 备份 Knowledge MySQL 与 MinIO。
3. 停止正常 Knowledge Service。
4. 删除旧 ES 索引与 Milvus Collection。
5. 创建 `knowledge_chunks_draft_v2`、`knowledge_chunks_published_v2`、`agent-user-memory-v2` 以及对应的 Milvus v2 Collection；向量维度为2560。
6. 使用显式维护命令，从 Knowledge MySQL 当前草稿、当前发布版本和 Chunk 全量重建知识索引。
7. Agent 使用维护命令为所有当前有效记忆生成新的 UPSERT Outbox；Knowledge Service 按正常内部接口重建用户记忆索引。
8. 校验 MySQL、ES、Milvus 的数量、Chunk ID、正文摘要、模型、维度、指令版本和发布指针。
9. 正常启动 Knowledge Service并执行固定问答集。
10. 验证完成后关闭 Ollama和 `bizagent-reranker`，并从本地启动清单移除。

重建任一步失败都不能开放检索。预算不足时任务暂停，不能把部分索引投入使用。维护开关默认关闭，日常启动不得自动进行全库付费重建。

## 9. 测试与验收

### 9.1 自动化测试

- 云端客户端请求头、URL、query/document类型、维度、TopK和响应校验；
- 认证失败、限流、超时、重试、非法响应和敏感信息日志约束；
- MySQL 多实例并发预算预占，确保不能突破180元；
- 成功结算、明确失败释放、未知结果保留、进程遗留和上海时区跨月；
- 索引模型/维度不一致时拒绝查询并要求重建；
- Knowledge MySQL 到知识 ES/Milvus 的全量恢复；
- Agent 有效记忆到 Outbox，再到记忆 ES/Milvus 的全量恢复；
- 中途失败索引不能被发布或查询。

### 9.2 实际云端验收

使用专用低权限 API Key 完成 Embedding 与 Reranker冒烟测试，核对百炼 requestId、实际 Token、系统结算金额及控制台调用明细。固定问答集包含明确命中、同义表达、跨段落问题和无依据问题。

### 9.3 PDF专项验收

迁移稳定后准备文本型 PDF 和扫描型 PDF。验证：MinIO存储、文本解析/OCR、标题层级、段落、页码、表格、低置信度标记、分块、云端向量化、双索引、发布、Agent问答、参考来源卡片及会话历史恢复。

PDF验收问题不得只使用文档原句，还必须包含同义改写、跨段落组合问题和文档不存在的问题。

## 10. 完成标准

- Knowledge Service 正常运行时不再请求 `127.0.0.1:11434` 和 `127.0.0.1:8000`；
- Ollama 与本地 Reranker停止后，知识问答和用户记忆向量召回仍正常；
- 新旧模型向量不存在混用；
- 多实例并发与重试不能突破180元系统硬停止线；
- 额度耗尽、模型故障和索引不一致均返回稳定错误，不生成无证据答案；
- 全量自动化测试、云端冒烟测试和固定知识问答集通过；
- 后续PDF全链路验收通过后，才视为云端知识库能力最终完成。
