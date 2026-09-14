# Bailian Retrieval Model Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace local Ollama Embedding and local BGE reranking with Alibaba Cloud Model Studio `qwen3.7-text-embedding` and `qwen3.7-text-rerank`, enforce a MySQL-backed CNY 180 hard stop within a CNY 200 monthly budget, and rebuild all derived ES/Milvus indexes without losing source data.

**Architecture:** The Knowledge service remains the only owner of retrieval-model calls. Each physical HTTP attempt first reserves a conservative maximum charge in MySQL, then settles from the provider's `usage.total_tokens`; ambiguous network outcomes remain reserved. Knowledge documents and Agent memories remain source-of-truth in their own MySQL databases, while Elasticsearch and Milvus are recreated as versioned derived indexes and repopulated through existing recovery/outbox paths.

**Tech Stack:** Java 21, Spring Boot 3.5, Java `HttpClient`, MyBatis-Plus, Flyway, MySQL 8.4, Elasticsearch, Milvus, JUnit 5, AssertJ, Testcontainers, Alibaba Cloud Model Studio DashScope HTTP APIs.

---

## Scope and non-goals

This plan covers cloud Embedding, cloud Reranker, hard-budget accounting, stable failure semantics, derived-index rebuild, Agent memory reindex, and removal of Ollama/reranker runtime dependencies. It does not change document parsing/OCR behavior and does not perform the later PDF quality evaluation; parsed PDF chunks automatically use the new cloud Embedding after this migration.

Provider contracts used by this plan:

- Embedding: `POST https://{workspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding`
- Reranker: `POST https://{workspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank`
- Authentication: `Authorization: Bearer <KNOWLEDGE_DASHSCOPE_API_KEY>`
- Embedding output dimension: 2560
- Both successful responses must include `usage.total_tokens`; missing usage is treated as an ambiguous billed call, not as zero cost.

## File map

Knowledge service (`D:\GitCode\order-logistics-knowledge-service`):

- Create `src/main/resources/db/migration/V5__create_cloud_model_budget.sql`: monthly account and per-attempt ledger.
- Create `src/main/java/com/xjjk/knowledge/cloud/config/BailianModelProperties.java`: endpoint, credentials, models, retry and budget configuration.
- Create `src/main/java/com/xjjk/knowledge/cloud/budget/*`: reservation, settlement, atomic mapper, cost estimator, stable exception.
- Create `src/main/java/com/xjjk/knowledge/cloud/client/BailianCallExecutor.java`: one-attempt budget lifecycle and bounded retry orchestration.
- Create `src/main/java/com/xjjk/knowledge/retrieval/embedding/BailianEmbeddingClient.java`: DashScope embedding adapter.
- Create `src/main/java/com/xjjk/knowledge/retrieval/rerank/BailianRerankerClient.java`: DashScope rerank adapter.
- Delete `src/main/java/com/xjjk/knowledge/retrieval/embedding/OllamaEmbeddingClient.java` and its test after replacement.
- Delete `src/main/java/com/xjjk/knowledge/retrieval/rerank/BgeRerankerClient.java` and its test after replacement.
- Modify `src/main/resources/application-local.yml`: cloud and v2 index property names only; preserve unrelated local edits.
- Modify `src/main/resources/application.yml`: v2 user-memory index defaults and maintenance controls.
- Modify `src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java`: stable budget error.
- Modify `src/main/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalService.java`: do not hide budget exhaustion as ordinary vector degradation.
- Create `src/main/java/com/xjjk/knowledge/retrieval/indexing/DerivedIndexResetRunner.java`: explicitly gated exact-name deletion before recovery.
- Create tests beside each component and update retrieval/index tests for v2 metadata.
- Modify `docs/runbooks/index-recovery.md`: exact cutover, rollback, and validation commands.

Agent service (`D:\GitCode\order-logistics-agent-server`):

- Create `src/main/java/com/xjjk/agent/memory/service/UserMemoryReindexService.java`: enqueue all active current-generation memories.
- Create `src/main/java/com/xjjk/agent/memory/service/UserMemoryReindexRunner.java`: explicitly gated maintenance runner.
- Modify `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`: paged active-memory scan.
- Modify `src/main/java/com/xjjk/agent/knowledge/client/KnowledgeServiceGateway.java`: preserve the budget error code.
- Add focused unit/integration tests and document Nacos settings.

No Electron code change is required: the Agent continues returning its existing safe user message while logs and service codes retain `KNOWLEDGE_MODEL_BUDGET_EXHAUSTED` for diagnosis.

### Task 1: Add validated cloud configuration and stable budget error

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/cloud/config/BailianModelProperties.java`
- Modify: `src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java`
- Test: `src/test/java/com/xjjk/knowledge/cloud/config/BailianModelPropertiesTest.java`

- [ ] **Step 1: Write failing property-validation tests**

Test exact defaults and reject blank workspace/API key, non-Beijing host, a hard limit greater than the configured budget, nonpositive retry count, and nonpositive price:

```java
class BailianModelPropertiesTest {
    @Test
    void acceptsProductionDefaults() {
        BailianModelProperties p = valid();
        assertThatNoException().isThrownBy(p::validate);
        assertThat(p.embeddingEndpoint().toString()).endsWith(
                "/api/v1/services/embeddings/text-embedding/text-embedding");
        assertThat(p.rerankEndpoint().toString()).endsWith(
                "/api/v1/services/rerank/text-rerank/text-rerank");
        assertThat(p.getMonthlyBudgetMicros()).isEqualTo(200_000_000L);
        assertThat(p.getHardLimitMicros()).isEqualTo(180_000_000L);
    }

    @Test
    void rejectsUnsafeBudgetAndMissingCredentials() {
        BailianModelProperties p = valid();
        p.setApiKey(" ");
        assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
        p = valid();
        p.setHardLimitMicros(200_000_001L);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -Dtest=BailianModelPropertiesTest test`

Expected: compilation fails because `BailianModelProperties` does not exist.

- [ ] **Step 3: Implement one immutable-endpoint configuration owner**

Use prefix `knowledge.cloud-model` and these fields/defaults:

```java
@Component
@ConfigurationProperties(prefix = "knowledge.cloud-model")
public class BailianModelProperties {
    private boolean enabled = true;
    private String region = "cn-beijing";
    private String workspaceId;
    private String apiKey;
    private String embeddingModel = "qwen3.7-text-embedding";
    private String rerankerModel = "qwen3.7-text-rerank";
    private int embeddingDimension = 2560;
    private int embeddingBatchSize = 20;
    private int maxAttempts = 3;
    private Duration initialBackoff = Duration.ofMillis(200);
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(30);
    private long monthlyBudgetMicros = 200_000_000L;
    private long hardLimitMicros = 180_000_000L;
    private long embeddingPriceMicrosPerMillionTokens = 500_000L;
    private long rerankerPriceMicrosPerMillionTokens = 500_000L;

    public URI embeddingEndpoint() { return endpoint("embeddings/text-embedding/text-embedding"); }
    public URI rerankEndpoint() { return endpoint("rerank/text-rerank/text-rerank"); }
    private URI endpoint(String suffix) {
        return URI.create("https://" + workspaceId + "." + region
                + ".maas.aliyuncs.com/api/v1/services/" + suffix);
    }

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (workspaceId == null || workspaceId.isBlank()
                || apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("百炼 Workspace 和 API Key 不能为空");
        if (!"cn-beijing".equals(region) || embeddingDimension != 2560
                || embeddingBatchSize < 1 || embeddingBatchSize > 20
                || maxAttempts < 1 || maxAttempts > 5
                || monthlyBudgetMicros <= 0 || hardLimitMicros <= 0
                || hardLimitMicros > monthlyBudgetMicros
                || embeddingPriceMicrosPerMillionTokens <= 0
                || rerankerPriceMicrosPerMillionTokens <= 0)
            throw new IllegalArgumentException("百炼模型配置不合法");
    }
}
```

Add `KNOWLEDGE_MODEL_BUDGET_EXHAUSTED` with HTTP 503 and user-safe Chinese message to `ApiErrorCode`.

- [ ] **Step 4: Run focused tests**

Run: `mvn -Dtest=BailianModelPropertiesTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/cloud/config/BailianModelProperties.java src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java src/test/java/com/xjjk/knowledge/cloud/config/BailianModelPropertiesTest.java
git commit -m "feat: add Bailian retrieval model configuration"
```

### Task 2: Create durable monthly budget account and per-attempt ledger

**Files:**
- Create: `src/main/resources/db/migration/V5__create_cloud_model_budget.sql`
- Create: `src/main/java/com/xjjk/knowledge/cloud/budget/CloudModelCallType.java`
- Create: `src/main/java/com/xjjk/knowledge/cloud/budget/CloudModelCallStatus.java`
- Create: `src/main/java/com/xjjk/knowledge/cloud/budget/CloudModelBudgetMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/cloud/budget/BudgetReservation.java`
- Test: `src/test/java/com/xjjk/knowledge/cloud/budget/CloudModelBudgetMigrationTest.java`

- [ ] **Step 1: Write a Testcontainers migration test**

Start MySQL 8.4, run Flyway, and assert both tables, their unique keys, and integer money columns exist. Also assert no `FLOAT`, `DOUBLE`, or `DECIMAL` money column is used.

- [ ] **Step 2: Run the migration test and verify it fails**

Run: `mvn -Dtest=CloudModelBudgetMigrationTest test`

Expected: table `knowledge_cloud_model_budget` is missing.

- [ ] **Step 3: Add the V5 schema**

```sql
CREATE TABLE knowledge_cloud_model_budget (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    billing_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hard_limit_micros BIGINT UNSIGNED NOT NULL,
    settled_micros BIGINT UNSIGNED NOT NULL DEFAULT 0,
    reserved_micros BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cloud_budget_month (billing_month),
    CONSTRAINT chk_cloud_budget_total CHECK
      (settled_micros + reserved_micros <= hard_limit_micros)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_cloud_model_call (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    billing_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    logical_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT UNSIGNED NOT NULL,
    call_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_micros BIGINT UNSIGNED NOT NULL,
    settled_micros BIGINT UNSIGNED NULL,
    total_tokens BIGINT UNSIGNED NULL,
    provider_request_id VARCHAR(128) NULL,
    error_code VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cloud_call_id (call_id),
    UNIQUE KEY uk_cloud_logical_attempt
      (logical_request_id, call_type, attempt_no),
    KEY idx_cloud_call_month_status (billing_month, status, id),
    CONSTRAINT chk_cloud_call_attempt CHECK (attempt_no > 0),
    CONSTRAINT chk_cloud_call_status CHECK
      (status IN ('RESERVED','SETTLED','RELEASED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Define `CloudModelCallType { EMBEDDING, RERANK }`, matching statuses, and a reservation record containing callId, billingMonth, reservedMicros, type, and attempt number.

- [ ] **Step 4: Add mapper primitives with guarded updates**

The mapper must expose:

```java
int insertMonthIfAbsent(String month, long hardLimitMicros, LocalDateTime now);
int reserve(String month, long micros, LocalDateTime now);
int insertCall(String callId, String month, String logicalRequestId, int attemptNo,
               String callType, String model, long reservedMicros, LocalDateTime now);
int settleAccount(String month, long reservedMicros, long settledMicros, LocalDateTime now);
int releaseAccount(String month, long reservedMicros, LocalDateTime now);
int markSettled(String callId, long settledMicros, long totalTokens,
                String providerRequestId, LocalDateTime now);
int markReleased(String callId, String errorCode, LocalDateTime now);
int markUnknown(String callId, String errorCode, LocalDateTime now);
```

`reserve` must be one atomic SQL statement:

```sql
UPDATE knowledge_cloud_model_budget
SET reserved_micros = reserved_micros + #{micros},
    version = version + 1,
    updated_at = #{now}
WHERE billing_month = #{month}
  AND settled_micros + reserved_micros + #{micros} <= hard_limit_micros
```

- [ ] **Step 5: Run migration tests**

Run: `mvn -Dtest=CloudModelBudgetMigrationTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add src/main/resources/db/migration/V5__create_cloud_model_budget.sql src/main/java/com/xjjk/knowledge/cloud/budget src/test/java/com/xjjk/knowledge/cloud/budget/CloudModelBudgetMigrationTest.java
git commit -m "feat: add durable cloud model budget ledger"
```

### Task 3: Implement atomic reserve, settle, release, and unknown outcomes

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/cloud/budget/CloudModelBudgetService.java`
- Create: `src/main/java/com/xjjk/knowledge/cloud/budget/CloudModelCostEstimator.java`
- Create: `src/main/java/com/xjjk/knowledge/cloud/budget/CloudModelBudgetExceededException.java`
- Test: `src/test/java/com/xjjk/knowledge/cloud/budget/CloudModelBudgetServiceTest.java`
- Test: `src/test/java/com/xjjk/knowledge/cloud/budget/CloudModelBudgetConcurrencyIT.java`

- [ ] **Step 1: Write failing lifecycle and concurrency tests**

Cover these exact invariants:

```java
@Test void reserveFailsBeforeProviderCallWhenHardLimitWouldBeExceeded() {}
@Test void settleMovesReservationToActualChargeAndReleasesSurplus() {}
@Test void knownNoCallFailureReleasesAllReservedMoney() {}
@Test void ambiguousTimeoutKeepsReservedMoneyAndMarksCallUnknown() {}
@Test void shanghaiMonthKeyChangesAtLocalMonthBoundary() {}
@Test void parallelReservationsNeverExceedHardLimit() {}
```

In the concurrency integration test, run 40 threads reserving 10,000,000 micro-yuan against a 180,000,000 limit; assert exactly 18 succeed and stored `settled_micros + reserved_micros` equals 180,000,000.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -Dtest=CloudModelBudgetServiceTest,CloudModelBudgetConcurrencyIT test`

Expected: compilation fails because the service and estimator do not exist.

- [ ] **Step 3: Implement conservative payload estimation**

Use UTF-8 byte count as a safe upper bound for text token count. For Embedding, sum all input bytes plus instruction bytes per query item. For Reranker use the provider formula: `queryBytes * documentCount + sum(documentBytes) + instructionBytes`. Convert tokens to micro-yuan with ceiling division:

```java
long chargeMicros(long tokenUpperBound, long priceMicrosPerMillionTokens) {
    return Math.max(1L, Math.addExact(
            Math.multiplyExact(tokenUpperBound, priceMicrosPerMillionTokens),
            999_999L) / 1_000_000L);
}
```

Reject payloads whose upper bound exceeds provider limits before reserving money.

- [ ] **Step 4: Implement transactional state transitions**

`reserve` creates the Shanghai `yyyy-MM` account if absent, performs the guarded account increment, throws `CloudModelBudgetExceededException` if zero rows changed, then inserts the unique physical-attempt ledger row in the same transaction. `settle` calculates actual cost from `total_tokens`, asserts it is not greater than the conservative reservation, atomically moves reserved money to settled money, and marks the call `SETTLED`. `release` decrements reserved money and marks `RELEASED`. `markUnknown` changes only the call status; it intentionally leaves account reservation unchanged.

- [ ] **Step 5: Run tests**

Run: `mvn -Dtest=CloudModelBudgetServiceTest,CloudModelBudgetConcurrencyIT test`

Expected: all lifecycle and concurrency tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/cloud/budget src/test/java/com/xjjk/knowledge/cloud/budget
git commit -m "feat: enforce cloud model hard budget"
```

### Task 4: Add budget-aware bounded HTTP retry execution

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/cloud/client/BailianCallExecutor.java`
- Create: `src/main/java/com/xjjk/knowledge/cloud/client/BailianHttpResult.java`
- Create: `src/main/java/com/xjjk/knowledge/cloud/client/BailianProviderException.java`
- Test: `src/test/java/com/xjjk/knowledge/cloud/client/BailianCallExecutorTest.java`

- [ ] **Step 1: Write failing retry classification tests**

Assert the executor:

- reserves separately for attempts 1, 2, and 3;
- retries only HTTP 429, 502, 503, 504 and connection/timeout failures;
- never retries HTTP 400, 401, or 403;
- releases reservation for a provider response that proves the request was rejected without billing;
- marks connection reset/read timeout as `UNKNOWN` because billing cannot be disproved;
- uses delays 200 ms then 400 ms, with a deterministic sleeper injected in tests;
- never logs authorization headers, input text, or document text.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=BailianCallExecutorTest test`

Expected: compilation fails because `BailianCallExecutor` does not exist.

- [ ] **Step 3: Implement the execution algorithm**

```java
for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
    BudgetReservation reservation = budget.reserve(
            logicalRequestId, callType, attempt, model, maximumChargeMicros);
    try {
        BailianHttpResult result = sender.send();
        if (result.success()) {
            budget.settle(reservation, result.totalTokens(), result.providerRequestId());
            return result;
        }
        budget.release(reservation, result.errorCode());
        if (!result.retryable() || attempt == properties.getMaxAttempts())
            throw new BailianProviderException(result.errorCode(), result.statusCode());
    } catch (HttpTimeoutException | ConnectException exception) {
        budget.markUnknown(reservation, exception.getClass().getSimpleName());
        if (attempt == properties.getMaxAttempts()) throw exception;
    }
    sleeper.sleep(properties.getInitialBackoff().multipliedBy(1L << (attempt - 1)));
}
```

If a successful HTTP body is malformed or lacks `usage.total_tokens`, mark the attempt `UNKNOWN` and fail closed. Do not release it.

- [ ] **Step 4: Run tests**

Run: `mvn -Dtest=BailianCallExecutorTest test`

Expected: all retry and accounting assertions pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/cloud/client src/test/java/com/xjjk/knowledge/cloud/client
git commit -m "feat: add budget aware Bailian call executor"
```

### Task 5: Replace Ollama with qwen3.7 cloud Embedding

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/embedding/BailianEmbeddingClient.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/embedding/BailianEmbeddingClientTest.java`
- Delete: `src/main/java/com/xjjk/knowledge/retrieval/embedding/OllamaEmbeddingClient.java`
- Delete: `src/test/java/com/xjjk/knowledge/retrieval/embedding/OllamaEmbeddingClientTest.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/embedding/EmbeddingProperties.java`

- [ ] **Step 1: Write failing HTTP-contract tests**

Use `HttpServer` and assert:

```json
{
  "model": "qwen3.7-text-embedding",
  "input": {"texts": ["退款规则", "物流规范"]},
  "parameters": {"text_type": "document", "dimension": 2560, "output_type": "dense"}
}
```

For queries assert `text_type=query` and:

```json
"instruct": "Given a Chinese customer-service question, retrieve passages that directly answer it."
```

Assert `Authorization: Bearer test-key`, batch splitting at 20, response index ordering, 2560 dimensions, finite floats, `usage.total_tokens` settlement, and rejection of duplicate/missing indexes.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=BailianEmbeddingClientTest test`

Expected: compilation fails because the cloud adapter does not exist.

- [ ] **Step 3: Implement the native DashScope adapter**

Map the response shape exactly:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
record EmbedResponse(Output output, Usage usage, @JsonProperty("request_id") String requestId) {}
record Output(List<EmbeddingItem> embeddings) {}
record EmbeddingItem(List<Float> embedding, int index) {}
record Usage(@JsonProperty("total_tokens") long totalTokens) {}
```

Generate one logical request ID per batch and delegate every physical attempt to `BailianCallExecutor`. Sort by response `index` before returning vectors. Keep the `EmbeddingClient` interface unchanged so document indexing, online recall, and user-memory indexing all migrate together.

- [ ] **Step 4: Remove Ollama-specific production types**

Move only batching and dimension into `EmbeddingProperties`; remove base URL and local model fields. Delete the Ollama implementation/test so Spring has exactly one `EmbeddingClient` bean.

- [ ] **Step 5: Run embedding and indexing tests**

Run: `mvn -Dtest=BailianEmbeddingClientTest,DraftIndexingServiceTest,UserMemoryIndexServiceTest test`

Expected: `BUILD SUCCESS` and no test refers to `/api/embed` or `qwen3-embedding:4b-q4_K_M`.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/retrieval/embedding src/test/java/com/xjjk/knowledge/retrieval/embedding
git commit -m "feat: migrate embeddings to Bailian"
```

### Task 6: Replace local BGE with qwen3.7 cloud Reranker

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/rerank/BailianRerankerClient.java`
- Create: `src/test/java/com/xjjk/knowledge/retrieval/rerank/BailianRerankerClientTest.java`
- Delete: `src/main/java/com/xjjk/knowledge/retrieval/rerank/BgeRerankerClient.java`
- Delete: `src/test/java/com/xjjk/knowledge/retrieval/rerank/BgeRerankerClientTest.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/rerank/RerankerProperties.java`

- [ ] **Step 1: Write failing native-contract tests**

Assert this request structure:

```json
{
  "model": "qwen3.7-text-rerank",
  "input": {
    "query": "怎么退款",
    "documents": ["正文-a", "正文-b"]
  },
  "parameters": {
    "top_n": 2,
    "instruct": "Given a Chinese customer-service question, retrieve passages that directly answer it."
  }
}
```

Return the documented shape `output.results[].index`, `output.results[].relevance_score`, `usage.total_tokens`, and `request_id`. Test sorted mapping, duplicate/out-of-range indexes, invalid scores, missing usage, and hard-budget exhaustion before HTTP transmission.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=BailianRerankerClientTest test`

Expected: compilation fails because the cloud adapter does not exist.

- [ ] **Step 3: Implement and remove local BGE**

Keep the `Reranker` interface and score threshold behavior unchanged. Change `RerankerProperties` to retain only `enabled`, `topK`, `scoreThreshold`, and the instruction; cloud endpoint/model/timeouts come from `BailianModelProperties`. Delete BGE-specific code so Spring has exactly one `Reranker` bean.

- [ ] **Step 4: Run retrieval tests**

Run: `mvn -Dtest=BailianRerankerClientTest,HybridRetrievalServiceTest test`

Expected: `BUILD SUCCESS` and no test calls local port 8000.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/retrieval/rerank src/test/java/com/xjjk/knowledge/retrieval/rerank
git commit -m "feat: migrate reranking to Bailian"
```

### Task 7: Propagate hard-budget exhaustion without unsafe degradation

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalService.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/*`
- Test: `src/test/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalServiceTest.java`
- Test: `src/test/java/com/xjjk/knowledge/document/task/IngestionTaskWorkerTest.java`
- Modify: `D:\GitCode\order-logistics-agent-server\src/main/java/com/xjjk/agent/knowledge/client/KnowledgeServiceGateway.java`
- Test: `D:\GitCode\order-logistics-agent-server\src/test/java/com/xjjk/agent/knowledge/client/KnowledgeServiceGatewayTest.java`

- [ ] **Step 1: Add failing Knowledge behavior tests**

Assert `CloudModelBudgetExceededException` from query Embedding or reranking becomes `BusinessException(KNOWLEDGE_MODEL_BUDGET_EXHAUSTED)` and is not swallowed into `KEYWORD_ONLY` or `NO_RELIABLE_EVIDENCE`. Assert ingestion retains parsed chunks and schedules retry with code `KNOWLEDGE_MODEL_BUDGET_EXHAUSTED` instead of marking the document permanently failed.

- [ ] **Step 2: Implement explicit exception branches**

Before broad `RuntimeException` catches, add:

```java
} catch (CloudModelBudgetExceededException exception) {
    throw new BusinessException(ApiErrorCode.KNOWLEDGE_MODEL_BUDGET_EXHAUSTED);
} catch (RuntimeException exception) {
```

For ingestion, map exhaustion to the existing retryable task state with delayed `next_run_at`; never discard parsed units/chunks or original MinIO objects.

- [ ] **Step 3: Add failing Agent gateway test**

Given a Knowledge response code `KNOWLEDGE_MODEL_BUDGET_EXHAUSTED`, assert the gateway throws a dedicated `KnowledgeModelBudgetExceededException`, retaining the code but not provider details.

- [ ] **Step 4: Implement Agent mapping and verify both repositories**

Run in Knowledge: `mvn -Dtest=HybridRetrievalServiceTest,IngestionTaskWorkerTest test`

Run in Agent: `mvn -Dtest=KnowledgeServiceGatewayTest,KnowledgeQueryToolsTest test`

Expected: both builds succeed; online retrieval fails closed and ingestion remains retryable.

- [ ] **Step 5: Commit in each repository**

Knowledge commit: `git commit -m "feat: fail closed when retrieval budget is exhausted"`

Agent commit: `git commit -m "feat: preserve knowledge budget failures"`

### Task 8: Switch to v2 index namespaces and add a guarded exact-target reset

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/indexing/DerivedIndexResetRunner.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/indexing/DerivedIndexResetService.java`
- Test: `src/test/java/com/xjjk/knowledge/retrieval/indexing/DerivedIndexResetServiceTest.java`
- Modify: `docs/runbooks/index-recovery.md`

- [ ] **Step 1: Write exact-target safety tests**

Allow deletion only for these eight old/new derived targets:

```text
knowledge_chunks_draft_v1
knowledge_chunks_published_v1
knowledge_chunks_draft_v2
knowledge_chunks_published_v2
agent-user-memory-v1
agent-user-memory-v2
agent_user_memory_v1
agent_user_memory_v2
```

Assert blank names, wildcards, aliases, unknown names, `default`, and database-wide operations are rejected. Assert the runner requires both `knowledge.maintenance.reset-derived-indexes-on-startup=true` and `knowledge.maintenance.reset-confirmation=RESET_BAILIAN_V2_DERIVED_INDEXES`.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=DerivedIndexResetServiceTest test`

Expected: compilation fails because reset service is absent.

- [ ] **Step 3: Implement exact deletion and v2 defaults**

Set:

```yaml
knowledge:
  user-memory:
    elasticsearch:
      index-alias: agent-user-memory-active
      index-name: agent-user-memory-v2
    milvus:
      collection: agent_user_memory_v2
      dimension: 2560
  maintenance:
    reset-derived-indexes-on-startup: false
    reset-confirmation: ""
  retrieval:
    elasticsearch:
      draft-index: knowledge_chunks_draft_v2
      published-index: knowledge_chunks_published_v2
    milvus:
      draft-collection: knowledge_chunks_draft_v2
      published-collection: knowledge_chunks_published_v2
      dimension: 2560
    strategy:
      version: qwen37-es-milvus-rrf60-rerank-v3
```

Reset v2 and stale v1 targets only after logging exact resolved names. Run reset before `IndexRecoveryRunner`; do not touch MySQL document tables, Agent memory tables, or MinIO.

- [ ] **Step 4: Preserve unrelated local configuration changes**

Before editing `application-local.yml`, inspect `git diff -- src/main/resources/application-local.yml`, apply only the cloud/v2 hunks, and stage that path only after reviewing the resulting diff.

- [ ] **Step 5: Run safety tests**

Run: `mvn -Dtest=DerivedIndexResetServiceTest,IndexRecoveryServiceTest test`

Expected: only allowlisted exact targets can be deleted and rebuild tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/resources/application.yml src/main/resources/application-local.yml src/main/java/com/xjjk/knowledge/retrieval/indexing src/test/java/com/xjjk/knowledge/retrieval/indexing docs/runbooks/index-recovery.md
git diff --cached
git commit -m "feat: add Bailian v2 index cutover"
```

### Task 9: Re-enqueue every active Agent memory for v2 indexing

**Files:**
- Modify: `D:\GitCode\order-logistics-agent-server\src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`
- Create: `D:\GitCode\order-logistics-agent-server\src/main/java/com/xjjk/agent/memory/service/UserMemoryReindexService.java`
- Create: `D:\GitCode\order-logistics-agent-server\src/main/java/com/xjjk/agent/memory/service/UserMemoryReindexRunner.java`
- Test: `D:\GitCode\order-logistics-agent-server\src/test/java/com/xjjk/agent/memory/service/UserMemoryReindexServiceTest.java`

- [ ] **Step 1: Write failing reindex tests**

Assert the service pages active, unexpired memories ordered by numeric `id`; creates one fresh `UPSERT/PENDING` outbox event per memory using its current generation/version; never mutates memory content; skips inactive/expired rows; and can be rerun without duplicate pending events for the same maintenance run ID.

- [ ] **Step 2: Add the paged scan and idempotent outbox insert**

Add mapper methods with this selection contract:

```sql
SELECT * FROM agent_user_memory
WHERE id > #{afterId}
  AND status = 'ACTIVE'
  AND (expires_at IS NULL OR expires_at > #{now})
ORDER BY id
LIMIT #{limit}
```

Create event IDs deterministically as UUIDv5-compatible hashes of `BAILIAN_V2_REINDEX|memory_id|version`, so the existing unique `event_id` constraint makes reruns idempotent. Do not read or write the Knowledge database directly.

- [ ] **Step 3: Gate the runner**

Enable only with:

```yaml
agent:
  memory:
    maintenance:
      reindex-on-startup: false
      reindex-confirmation: ""
      batch-size: 500
```

Require confirmation `REINDEX_BAILIAN_V2_USER_MEMORY`. The runner exits after enqueueing; the existing `MemoryIndexOutboxWorker` performs normal signed calls and retries budget exhaustion.

- [ ] **Step 4: Run Agent tests**

Run: `mvn -Dtest=UserMemoryReindexServiceTest,MemoryIndexOutboxWorkerTest,MemoryIndexOutboxStateServiceTest test`

Expected: reindex is idempotent and existing Outbox semantics remain intact.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/agent/memory src/test/java/com/xjjk/agent/memory
git commit -m "feat: add user memory v2 reindex maintenance"
```

### Task 10: Configure Nacos and verify without leaking secrets

**Files:**
- Modify: Knowledge Nacos data ID `order-logistics-knowledge.yml` in namespace `order-logistics-agent-dev`
- Modify: Agent Nacos data ID used by `order-logistics-agent-server`
- Modify: `docs/runbooks/index-recovery.md`

- [ ] **Step 1: Add production-style Knowledge Nacos properties**

Use environment indirection for the secret and workspace, never commit their values:

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
```

Do not place the real API key in Git, logs, screenshots, test fixtures, or chat output. Configure the two environment variables for the Knowledge process account before restart.

- [ ] **Step 2: Run configuration and full unit tests**

Knowledge: `mvn test`

Agent: `mvn test`

Expected: both `BUILD SUCCESS`; no test or source reference remains to `127.0.0.1:11434`, `/api/embed`, `127.0.0.1:8000`, or `BgeRerankerClient`.

- [ ] **Step 3: Verify secrets and local-service references**

Run in both repositories:

```powershell
rg -n "sk-[A-Za-z0-9]|11434|qwen3-embedding:4b-q4_K_M|BgeRerankerClient|KNOWLEDGE_RERANKER_BASE_URL" .
```

Expected: no secret match; remaining local-model text occurs only in migration design/history documents, not runtime code/config.

- [ ] **Step 4: Commit runbook updates only**

```powershell
git add docs/runbooks/index-recovery.md
git commit -m "docs: add Bailian cutover runbook"
```

### Task 11: Execute the destructive cutover and rebuild

**Files:**
- No source edits; this is an operational checkpoint.

- [ ] **Step 1: Record source-of-truth counts before deletion**

Capture MySQL counts for knowledge bases, document versions, chunks, active publication pointers, active Agent memories, and pending/retry Outbox events. Capture exact ES index and Milvus collection names. Abort if any resolved target is outside the allowlist in Task 8.

- [ ] **Step 2: Stop Agent and Knowledge processes**

Keep MySQL, MinIO, Elasticsearch, Milvus, Redis, RabbitMQ, Gateway, authentication, and business services running. Stop only the two application processes so no writer races with reset.

- [ ] **Step 3: Start Knowledge once in guarded reset/rebuild mode**

Temporarily set:

```yaml
knowledge:
  maintenance:
    reset-derived-indexes-on-startup: true
    reset-confirmation: RESET_BAILIAN_V2_DERIVED_INDEXES
    rebuild-indexes-on-startup: true
```

Expected logs: exact v1/v2 target deletion, v2 schema creation, draft/published version counts, and completed rebuild. Any budget-exhaustion event stops the cutover; do not enable traffic with a partial published index.

- [ ] **Step 4: Return Knowledge to normal mode and restart**

Set both reset and rebuild switches to `false`, clear the confirmation string, then restart Knowledge. Verify `/actuator/health`, ES document counts, Milvus entity counts, and three known knowledge questions with published evidence.

- [ ] **Step 5: Run Agent memory reindex once**

Set `agent.memory.maintenance.reindex-on-startup=true` and confirmation `REINDEX_BAILIAN_V2_USER_MEMORY`, start Agent until all events are enqueued, then restore switch `false` and restart normally. Wait until new Outbox events are `DONE`; verify ES/Milvus memory counts and cross-session recall of known explicit and automatic memories.

- [ ] **Step 6: Confirm source data did not change**

Compare pre/post MySQL source counts. Document/version/chunk/publication pointer counts and active Agent memory counts must match. Differences in Outbox rows are expected and must equal the reindex events created.

- [ ] **Step 7: Stop obsolete local model services**

Stop and disable Ollama and the local BGE reranker container/service. Restart Agent and Knowledge once more and repeat one knowledge query plus one cross-session memory query. Both must succeed without ports 11434 or 8000 listening.

### Task 12: Final regression and budget acceptance

**Files:**
- Create: `src/test/java/com/xjjk/knowledge/cloud/budget/CloudModelBudgetAcceptanceIT.java`
- Modify: `docs/runbooks/index-recovery.md`

- [ ] **Step 1: Add a hard-stop acceptance test**

Against MySQL Testcontainers and a fake provider, seed 179,999,999 micro-yuan as settled, issue a request needing at least 2 micro-yuan, and assert:

- HTTP sender invocation count remains zero;
- ledger contains no invalid settled row;
- API returns `KNOWLEDGE_MODEL_BUDGET_EXHAUSTED`;
- query does not return keyword-only evidence;
- ingestion stays retryable and retains parsed chunks.

- [ ] **Step 2: Run the complete Knowledge suite**

Run: `mvn test`

Expected: `BUILD SUCCESS` with all cloud, budget, retrieval, ingestion, reset, and existing document tests passing.

- [ ] **Step 3: Run the complete Agent suite**

Run in `D:\GitCode\order-logistics-agent-server`: `mvn test`

Expected: `BUILD SUCCESS`, including memory write, recall, Outbox, knowledge gateway, SSE, and conversation-history tests.

- [ ] **Step 4: Review repository state and merge directly to local main**

Run in both repositories:

```powershell
git status --short --branch
git log --oneline -12
```

Expected: branch is local `main`; only pre-existing user-owned changes may remain unstaged. Do not reset, overwrite, or commit unrelated changes.

- [ ] **Step 5: Record acceptance evidence**

Append to the runbook: model names, dimension, v2 target names, pre/post counts, test totals, three knowledge queries, two cross-session memory checks, current month `settled_micros`, `reserved_micros`, `UNKNOWN` call count, and confirmation that local ports 11434/8000 are unused. Do not record credentials or user content.

## Self-review result

- Spec coverage: cloud Embedding, cloud Reranker, dedicated credentials, Beijing endpoints, 2560 dimensions, physical-attempt accounting, CNY 180 hard stop, CNY 200 configured budget, Shanghai monthly boundary, fail-closed behavior, resumable ingestion, full derived-index rebuild, Agent memory replay, local-service removal, and later PDF compatibility are all mapped to tasks.
- Safety: destructive work is restricted to exact ES/Milvus names and double-gated; MySQL/MinIO source data is never deleted.
- Type consistency: call types/statuses, property keys, error code, v2 index names, and maintenance confirmation tokens are identical across tasks.
- Placeholder scan: implementation steps name exact files, contracts, commands, expected outcomes, and state transitions; no unspecified implementation action remains.
