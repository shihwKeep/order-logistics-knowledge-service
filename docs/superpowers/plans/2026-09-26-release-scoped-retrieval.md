# Release-Scoped Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在线检索在访问 Elasticsearch 和 Milvus 前使用当前 ACTIVE Release 的文档版本清单过滤候选，并在并发发布时最多按最新 Release 重试一次。

**Architecture:** MySQL 单次查询产生不可变的 `ActiveReleaseScope`，两路索引适配器按 `knowledgeBaseId + documentId + versionId` 精确过滤并对大清单分批合并。返回证据前继续由 MySQL 校验 Release 指针和候选归属；指针变化时复用查询向量完整重试一次，连续变化则失败关闭。

**Tech Stack:** Java 21、Spring Boot、MyBatis、MySQL 8、Elasticsearch HTTP API、Milvus Java SDK、JUnit 5、AssertJ、Mockito、Testcontainers、Micrometer。

---

## 文件结构

**新增：**

- `src/main/java/com/xjjk/knowledge/retrieval/model/DocumentVersionRef.java`：知识库、文档、版本三元组。
- `src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScope.java`：一次线上检索使用的不可变 Release 快照。
- `src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeMapper.java`：批量读取当前 ACTIVE Release 及清单。
- `src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeLoader.java`：组装范围快照。
- `src/main/java/com/xjjk/knowledge/retrieval/service/PublishedScopeValidation.java`：最终门禁结果。
- `src/test/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeIntegrationTest.java`：MySQL 范围集成测试。

**修改：**

- `RetrievalProperties`：增加版本过滤批量大小；Release切换重试固定为一次，不开放容易误配的动态次数。
- `KeywordIndex`、`VectorIndex`：搜索接口接收允许的版本范围。
- `ElasticsearchKeywordIndex`、`MilvusVectorIndex`：版本过滤、分批搜索、去重和全局 TopK。
- `PublishedVersionMapper`、`PublishedVersionValidator`：按期望 Release 执行最终门禁。
- `HybridRetrievalService`：加载范围、下推过滤和单次重试。
- `ApiErrorCode`、`KnowledgeMetrics`：连续切换错误码与低基数指标。
- 对应单元测试、集成测试和 `application.yml` 默认配置。

### Task 1: ACTIVE Release 范围模型与批量查询

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/model/DocumentVersionRef.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScope.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeLoader.java`
- Test: `src/test/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeIntegrationTest.java`

- [ ] **Step 1: 编写多知识库范围查询失败测试**

通过 Flyway 创建 MySQL，插入两个启用知识库、两个 ACTIVE Release 及各自清单，然后断言：

```java
ActiveReleaseScope scope = loader.load(1L, List.of(2L, 3L));
assertThat(scope.releaseIds()).containsExactlyInAnyOrderEntriesOf(Map.of(2L, 20L, 3L, 30L));
assertThat(scope.versions()).containsExactlyInAnyOrder(
        new DocumentVersionRef(2L, 8L, 11L),
        new DocumentVersionRef(3L, 9L, 12L));
```

再断言未指定知识库时只返回当前租户下 `ENABLED + ACTIVE` 的范围，不返回其他租户、禁用知识库或 SUPERSEDED Release。

- [ ] **Step 2: 运行测试并确认因类型不存在而失败**

```powershell
mvn "-Dtest=ActiveReleaseScopeIntegrationTest" test
```

Expected: 编译失败，提示 `ActiveReleaseScopeLoader` 或 `DocumentVersionRef` 不存在。

- [ ] **Step 3: 实现最小范围模型和单条SQL查询**

```java
public record DocumentVersionRef(long knowledgeBaseId, long documentId, long versionId) {
    public String key() {
        return knowledgeBaseId + ":" + documentId + ":" + versionId;
    }
}

public record ActiveReleaseScope(
        Map<Long, Long> releaseIds,
        List<DocumentVersionRef> versions) {
    public ActiveReleaseScope {
        releaseIds = Map.copyOf(releaseIds);
        versions = List.copyOf(versions);
    }
    public boolean isEmpty() {
        return releaseIds.isEmpty() || versions.isEmpty();
    }
}
```

Mapper 用一个 `<script>` 查询连接 `kb_knowledge_base`、`kb_release` 和 `kb_release_item`；指定知识库时增加 `kb.id IN (...)`。Loader 校验正数租户ID，对结果按知识库组装 Release ID，并对版本三元组去重和稳定排序。

- [ ] **Step 4: 运行集成测试并提交**

```powershell
mvn "-Dtest=ActiveReleaseScopeIntegrationTest" test
git add src/main/java/com/xjjk/knowledge/retrieval/model/DocumentVersionRef.java src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScope.java src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeMapper.java src/main/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeLoader.java src/test/java/com/xjjk/knowledge/retrieval/service/ActiveReleaseScopeIntegrationTest.java
git commit -m "feat: load active release retrieval scope"
```

Expected: PASS，0 failures，0 errors，然后提交。

### Task 2: 检索范围配置契约

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/service/RetrievalProperties.java`
- Modify: `src/test/java/com/xjjk/knowledge/config/ConfigurationContractTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 编写配置失败测试**

```java
@Test
void releaseScopeFilterBatchMustBePositive() {
    RetrievalProperties properties = new RetrievalProperties();
    assertThat(properties.getReleaseFilterBatchSize()).isEqualTo(200);
    properties.setReleaseFilterBatchSize(0);
    assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: 运行并确认缺少属性**

```powershell
mvn "-Dtest=ConfigurationContractTest" test
```

Expected: 编译失败，提示缺少 `getReleaseFilterBatchSize`。

- [ ] **Step 3: 实现并验证配置**

在 `RetrievalProperties` 增加默认值：

```java
private int releaseFilterBatchSize = 200;
```

把 `validate()` 改为 `public`，校验批量大小大于0，并在 `application.yml` 的 `knowledge.retrieval.strategy` 下加入 `release-filter-batch-size: 200`。随后运行：

```powershell
mvn "-Dtest=ConfigurationContractTest" test
git add src/main/java/com/xjjk/knowledge/retrieval/service/RetrievalProperties.java src/test/java/com/xjjk/knowledge/config/ConfigurationContractTest.java src/main/resources/application.yml
git commit -m "feat: configure release scope filtering"
```

Expected: PASS 后提交。

### Task 3: Elasticsearch 前置版本过滤与批量合并

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/index/KeywordIndex.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/index/ElasticsearchKeywordIndex.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/index/ElasticsearchKeywordIndexTest.java`

- [ ] **Step 1: 编写精确组合过滤与分批失败测试**

```java
List<DocumentVersionRef> versions = List.of(
        new DocumentVersionRef(2L, 3L, 11L),
        new DocumentVersionRef(2L, 4L, 12L),
        new DocumentVersionRef(2L, 5L, 13L));
List<RecallCandidate> hits = index.search(
        IndexLayer.PUBLISHED, 1L, List.of(2L), versions, "退款期限", 30);
```

批量大小设为2，断言HTTP服务器收到两次 `_search`。每批必须包含租户、知识库和成对的文档版本条件，禁止生成 `documentId in (...) AND versionId in (...)` 这种交叉匹配。两个批次返回相同Chunk时保留最高分并限制全局TopK。

- [ ] **Step 2: 运行并确认接口参数不匹配**

```powershell
mvn "-Dtest=ElasticsearchKeywordIndexTest" test
```

Expected: 编译失败，提示 `search` 不接受版本范围。

- [ ] **Step 3: 扩展接口并实现ES过滤**

```java
List<RecallCandidate> search(
        IndexLayer layer,
        long tenantId,
        List<Long> knowledgeBaseIds,
        List<DocumentVersionRef> allowedVersions,
        String query,
        int topK);
```

`ElasticsearchKeywordIndex` 注入 `RetrievalProperties`，按批量大小拆分。每个版本使用包含 `knowledgeBaseId/documentId/versionId` 三个 `term` 的 `bool.filter`；所有版本放入外层 `bool.should` 并设置 `minimum_should_match=1`。合并时按Chunk ID保留最高分，最后按分数降序、Chunk ID升序形成稳定TopK。草稿层传空版本集合，线上空范围由服务层提前返回。

- [ ] **Step 4: 运行测试并提交**

```powershell
mvn "-Dtest=ElasticsearchKeywordIndexTest" test
git add src/main/java/com/xjjk/knowledge/retrieval/index/KeywordIndex.java src/main/java/com/xjjk/knowledge/retrieval/index/ElasticsearchKeywordIndex.java src/test/java/com/xjjk/knowledge/retrieval/index/ElasticsearchKeywordIndexTest.java
git commit -m "feat: filter elasticsearch by release versions"
```

Expected: PASS 后提交。

### Task 4: Milvus 前置版本过滤与批量合并

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/index/VectorIndex.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/index/MilvusVectorIndex.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/index/MilvusVectorIndexTest.java`

- [ ] **Step 1: 编写表达式与分批失败测试**

使用Task 3相同三条版本和批量大小2，断言执行两次向量搜索，并包含：

```text
tenant_id == 1 && knowledge_base_id in [2]
&& ((knowledge_base_id == 2 && document_id == 3 && version_id == 11)
 || (knowledge_base_id == 2 && document_id == 4 && version_id == 12))
```

两个批次返回相同Chunk的不同分数时，断言只保留最高分并限制全局TopK。

- [ ] **Step 2: 运行并确认接口参数不匹配**

```powershell
mvn "-Dtest=MilvusVectorIndexTest" test
```

Expected: 编译失败，提示 `VectorIndex.search` 不接受版本范围。

- [ ] **Step 3: 实现Milvus过滤并验证**

扩展 `VectorIndex.search` 接收 `List<DocumentVersionRef>`；`MilvusVectorIndex` 注入 `RetrievalProperties`，按相同批量大小拆分表达式。每项包含完整三元组，合并时Chunk ID去重、最高分优先、稳定排序并限制TopK。

```powershell
mvn "-Dtest=MilvusVectorIndexTest" test
git add src/main/java/com/xjjk/knowledge/retrieval/index/VectorIndex.java src/main/java/com/xjjk/knowledge/retrieval/index/MilvusVectorIndex.java src/test/java/com/xjjk/knowledge/retrieval/index/MilvusVectorIndexTest.java
git commit -m "feat: filter milvus by release versions"
```

Expected: PASS 后提交。

### Task 5: 带期望Release的最终门禁

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/retrieval/service/PublishedScopeValidation.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/service/PublishedVersionMapper.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/service/PublishedVersionValidator.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/service/PublishedVersionValidatorIntegrationTest.java`

- [ ] **Step 1: 编写稳定与切换状态失败测试**

```java
PublishedScopeValidation stable = validator.validate(
        1L, scope20, List.of(evidence(2L, 3L, 11L), evidence(2L, 3L, 9L)));
assertThat(stable.releaseChanged()).isFalse();
assertThat(stable.evidences()).extracting(item -> item.chunk().versionId()).containsExactly(11L);

PublishedScopeValidation changed = validator.validate(
        1L, scope20, List.of(evidence(2L, 3L, 11L)));
assertThat(changed.releaseChanged()).isTrue();
assertThat(changed.evidences()).isEmpty();
```

第二次断言前把 `current_release_id` 从20切换到21。

- [ ] **Step 2: 运行并确认返回类型不匹配**

```powershell
mvn "-Dtest=PublishedVersionValidatorIntegrationTest" test
```

Expected: 编译失败，提示缺少带 `ActiveReleaseScope` 的 `validate`。

- [ ] **Step 3: 实现门禁结果和单次批量校验**

```java
public record PublishedScopeValidation(
        boolean releaseChanged,
        List<RankedEvidence> evidences) {
    public PublishedScopeValidation {
        evidences = List.copyOf(evidences);
    }
}
```

Mapper一次返回当前ACTIVE Release ID和匹配的版本键。任一知识库指针与范围不一致时返回 `releaseChanged=true` 和空证据；稳定时按三元组键过滤候选，同时保留租户校验。

- [ ] **Step 4: 运行测试并提交**

```powershell
mvn "-Dtest=PublishedVersionValidatorIntegrationTest" test
git add src/main/java/com/xjjk/knowledge/retrieval/service/PublishedScopeValidation.java src/main/java/com/xjjk/knowledge/retrieval/service/PublishedVersionMapper.java src/main/java/com/xjjk/knowledge/retrieval/service/PublishedVersionValidator.java src/test/java/com/xjjk/knowledge/retrieval/service/PublishedVersionValidatorIntegrationTest.java
git commit -m "feat: validate expected active release"
```

Expected: PASS 后提交。

### Task 6: Hybrid检索接入范围并重试一次

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalService.java`
- Modify: `src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalServiceTest.java`

- [ ] **Step 1: 编写前置范围与切换重试失败测试**

```java
when(scopeLoader.load(1L, List.of(2L))).thenReturn(scope20, scope21);
when(validator.validate(eq(1L), eq(scope20), anyList()))
        .thenReturn(new PublishedScopeValidation(true, List.of()));
when(validator.validate(eq(1L), eq(scope21), anyList()))
        .thenAnswer(invocation -> new PublishedScopeValidation(false, invocation.getArgument(2)));

RetrievalResult result = service.retrieve(1L, 10567L, "request-switch", "问题", List.of(2L));
assertThat(result.answerable()).isTrue();
assertThat(keyword.scopes).containsExactly(scope20.versions(), scope21.versions());
assertThat(vector.scopes).containsExactly(scope20.versions(), scope21.versions());
assertThat(embeddingCalls).hasValue(1);
```

再增加：连续两次切换抛出 `KNOWLEDGE_RELEASE_CHANGING`；空范围不调用ES、Milvus和BGE；草稿检索不加载Release范围。

- [ ] **Step 2: 运行并确认构造器和接口失败**

```powershell
mvn "-Dtest=HybridRetrievalServiceTest" test
```

Expected: 编译失败，提示缺少范围加载依赖或接口签名不匹配。

- [ ] **Step 3: 实现最多一次完整重试**

在线入口生成一次查询向量并加载范围，将向量结果传给内部attempt方法。attempt完成双路召回、RRF、BGE和带范围门禁。第一次 `releaseChanged=true` 时重新加载范围并调用第二次attempt；第二次仍变化则抛出：

```java
KNOWLEDGE_RELEASE_CHANGING(
        "KNOWLEDGE_RELEASE_CHANGING",
        "知识库正在发布，请稍后重试",
        HttpStatus.CONFLICT)
```

搜索日志只记录最终结果；Embedding预算和单路降级语义保持不变。

- [ ] **Step 4: 运行测试并提交**

```powershell
mvn "-Dtest=HybridRetrievalServiceTest" test
git add src/main/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalService.java src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java src/test/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalServiceTest.java
git commit -m "feat: retry retrieval on release change"
```

Expected: PASS 后提交。

### Task 7: 可观测性

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/observation/KnowledgeMetrics.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalService.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalServiceTest.java`

- [ ] **Step 1: 编写指标失败测试**

使用 `SimpleMeterRegistry` 执行一次发生Release切换并成功重试的检索，然后断言：

```java
assertThat(registry.get("knowledge.retrieval.release.scope.versions").summary().count())
        .isEqualTo(2);
assertThat(registry.get("knowledge.retrieval.release.retry").counter().count())
        .isEqualTo(1D);
```

指标不得使用租户、知识库、Release或请求号作为tag。

- [ ] **Step 2: 运行并确认指标不存在**

```powershell
mvn "-Dtest=HybridRetrievalServiceTest" test
```

Expected: FAIL，MeterRegistry提示找不到Release范围指标。

- [ ] **Step 3: 实现指标和结构化日志**

```java
public void recordReleaseScopeSize(int count) {
    DistributionSummary.builder("knowledge.retrieval.release.scope.versions")
            .register(registry).record(Math.max(0, count));
}

public void recordReleaseRetry() {
    registry.counter("knowledge.retrieval.release.retry").increment();
}
```

加载范围后记录版本数，发生重试时增加计数。日志只记录请求ID、范围大小、知识库数量和尝试次数，不记录问题或正文。

- [ ] **Step 4: 运行测试并提交**

```powershell
mvn "-Dtest=HybridRetrievalServiceTest" test
git add src/main/java/com/xjjk/knowledge/observation/KnowledgeMetrics.java src/main/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalService.java src/test/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalServiceTest.java
git commit -m "feat: observe release scoped retrieval"
```

Expected: PASS 后提交。

### Task 8: 全量回归与真实验收

**Files:**
- Modify only if verification exposes a defect in files already listed above.

- [ ] **Step 1: 运行检索与发布相关测试**

```powershell
mvn "-Dtest=ActiveReleaseScopeIntegrationTest,PublishedVersionValidatorIntegrationTest,HybridRetrievalServiceTest,ElasticsearchKeywordIndexTest,MilvusVectorIndexTest,ReleaseServiceTest,ReleaseWorkerTest" test
```

Expected: 全部PASS，0 failures，0 errors。

- [ ] **Step 2: 运行后端全量验证**

```powershell
mvn test
mvn spotless:check
git diff --check
```

Expected: BUILD SUCCESS，0 failures，0 errors，格式检查通过。

- [ ] **Step 3: 检查工作树范围**

```powershell
git status --short
```

Expected: `application-local.yml`、`output/`、`tmp/`保持用户本地状态且不进入提交。

- [ ] **Step 4: 本地真实链路验收**

在同一知识库保留得分较高的旧版本Chunk和当前版本Chunk，通过检索诊断确认线上结果全部属于当前ACTIVE Release。发布新Release并在检索期间触发切换，确认最多重试一次且只返回新Release证据；连续切换时返回 `KNOWLEDGE_RELEASE_CHANGING`。

- [ ] **Step 5: 最终提交检查**

```powershell
git log --oneline -8
git status --short
```

Expected: 所有功能提交位于本地 `main`，工作树只剩用户拥有的本地配置和验收产物。

