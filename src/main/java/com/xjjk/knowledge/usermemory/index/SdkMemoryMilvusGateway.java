package com.xjjk.knowledge.usermemory.index;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xjjk.knowledge.retrieval.index.MilvusProperties;
import com.xjjk.knowledge.retrieval.index.SearchIndexUnavailableException;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Milvus Java SDK 的用户记忆专用 schema 适配器。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.retrieval.milvus", name = "enabled", havingValue = "true")
public class SdkMemoryMilvusGateway implements MemoryMilvusGateway {
    private static final String MEMORY_ID = "memory_id";
    private static final String TENANT_ID = "tenant_id";
    private static final String USER_ID = "user_id";
    private static final String GENERATION = "memory_generation";
    private static final String VERSION = "memory_version";
    private static final String SOURCE_TYPE = "source_type";
    private static final String CATEGORY = "category";
    private static final String CANONICAL_KEY = "canonical_key";
    private static final String CONTENT = "content";
    private static final String CONFIDENCE = "confidence";
    private static final String EXPIRES_AT = "expires_at";
    private static final String EMBEDDING = "embedding";

    private final MilvusClientV2 client;
    private final MilvusProperties properties;

    public SdkMemoryMilvusGateway(MilvusClientV2 client, MilvusProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public MemoryMilvusSpec describe(String collection) {
        try {
            Boolean exists = client.hasCollection(HasCollectionReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(collection).build());
            if (!Boolean.TRUE.equals(exists)) {
                return null;
            }
            DescribeCollectionResp response = client.describeCollection(
                    DescribeCollectionReq.builder()
                            .databaseName(properties.getDatabaseName())
                            .collectionName(collection).build());
            CreateCollectionReq.CollectionSchema schema = response.getCollectionSchema();
            if (schema == null || schema.getField(EMBEDDING) == null) {
                throw new IllegalStateException("用户记忆 Milvus Collection 缺少向量字段");
            }
            DescribeIndexResp indexes = client.describeIndex(DescribeIndexReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(collection).fieldName(EMBEDDING).build());
            DescribeIndexResp.IndexDesc vectorIndex =
                    indexes.getIndexDescByFieldName(EMBEDDING);
            if (vectorIndex == null || vectorIndex.getMetricType() == null) {
                throw new IllegalStateException("用户记忆 Milvus Collection 缺少向量索引");
            }
            return new MemoryMilvusSpec(
                    collection, schema.getField(EMBEDDING).getDimension(),
                    vectorIndex.getMetricType().name(), response.getPrimaryFieldName());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException(
                    "检查用户记忆 Milvus Collection 失败", exception);
        }
    }

    @Override
    public void create(MemoryMilvusSpec spec) {
        try {
            CreateCollectionReq.CollectionSchema schema =
                    CreateCollectionReq.CollectionSchema.builder()
                            .enableDynamicField(false)
                            .fieldSchemaList(List.of(
                                    varchar(MEMORY_ID, 64, true),
                                    int64(TENANT_ID), int64(USER_ID), int64(GENERATION),
                                    int64(VERSION), varchar(SOURCE_TYPE, 24, false),
                                    varchar(CATEGORY, 48, false),
                                    varchar(CANONICAL_KEY, 128, false),
                                    varchar(CONTENT, 512, false),
                                    scalar(CONFIDENCE, DataType.Double),
                                    int64(EXPIRES_AT),
                                    CreateCollectionReq.FieldSchema.builder()
                                            .name(EMBEDDING).dataType(DataType.FloatVector)
                                            .dimension(spec.dimension()).isNullable(false).build()))
                            .build();
            IndexParam index = IndexParam.builder()
                    .fieldName(EMBEDDING).indexName("idx_memory_embedding_hnsw")
                    .indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("M", 16, "efConstruction", 256)).build();
            client.createCollection(CreateCollectionReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(spec.name())
                    .description("Scoped cross-conversation user memories")
                    .collectionSchema(schema).indexParams(List.of(index))
                    .consistencyLevel(ConsistencyLevel.STRONG).build());
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException(
                    "创建用户记忆 Milvus Collection 失败", exception);
        }
    }

    @Override
    public void load(String collection) {
        try {
            client.loadCollection(LoadCollectionReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(collection).sync(true)
                    .timeout(properties.getRequestTimeout().toMillis()).build());
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException(
                    "加载用户记忆 Milvus Collection 失败", exception);
        }
    }

    @Override
    public long upsert(String collection, MemoryVectorRow row) {
        try {
            return client.upsert(UpsertReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(collection).data(List.of(toRow(row)))
                    .partialUpdate(false).build()).getUpsertCnt();
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("写入用户记忆 Milvus 失败", exception);
        }
    }

    @Override
    public List<MemorySearchHit> search(
            String collection, String filter, List<Float> vector, int topK) {
        try {
            SearchResp response = client.search(SearchReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(collection).annsField(EMBEDDING)
                    .limit(topK).filter(filter).data(List.of(new FloatVec(vector)))
                    .outputFields(outputFields())
                    .searchParams(Map.of("ef", Math.max(64, topK * 4))).build());
            List<MemorySearchHit> hits = new ArrayList<>();
            for (List<SearchResp.SearchResult> group : response.getSearchResults()) {
                for (SearchResp.SearchResult result : group) {
                    hits.add(new MemorySearchHit(
                            toDocument(result.getEntity()), result.getScore(), "VECTOR"));
                }
            }
            return List.copyOf(hits);
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("查询用户记忆 Milvus 失败", exception);
        }
    }

    @Override
    public void delete(String collection, String filter) {
        try {
            client.delete(DeleteReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(collection).filter(filter).build());
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("删除用户记忆 Milvus 数据失败", exception);
        }
    }

    private JsonObject toRow(MemoryVectorRow row) {
        MemoryIndexDocument document = row.document();
        JsonObject json = new JsonObject();
        json.addProperty(MEMORY_ID, document.memoryId());
        json.addProperty(TENANT_ID, document.tenantId());
        json.addProperty(USER_ID, document.userId());
        json.addProperty(GENERATION, document.memoryGeneration());
        json.addProperty(VERSION, document.memoryVersion());
        json.addProperty(SOURCE_TYPE, document.sourceType());
        json.addProperty(CATEGORY, document.category());
        json.addProperty(CANONICAL_KEY, document.canonicalKey());
        json.addProperty(CONTENT, document.content());
        json.addProperty(CONFIDENCE, document.confidence());
        json.addProperty(EXPIRES_AT,
                document.expiresAt() == null ? 0L : document.expiresAt().toEpochMilli());
        JsonArray embedding = new JsonArray();
        row.vector().forEach(embedding::add);
        json.add(EMBEDDING, embedding);
        return json;
    }

    private MemoryIndexDocument toDocument(Map<String, Object> value) {
        long expiresAt = number(value, EXPIRES_AT);
        return new MemoryIndexDocument(
                text(value, MEMORY_ID), number(value, TENANT_ID), number(value, USER_ID),
                number(value, GENERATION), number(value, VERSION),
                text(value, SOURCE_TYPE), text(value, CATEGORY),
                text(value, CANONICAL_KEY), text(value, CONTENT),
                decimal(value, CONFIDENCE),
                expiresAt == 0 ? null : Instant.ofEpochMilli(expiresAt));
    }

    private List<String> outputFields() {
        return List.of(MEMORY_ID, TENANT_ID, USER_ID, GENERATION, VERSION,
                SOURCE_TYPE, CATEGORY, CANONICAL_KEY, CONTENT, CONFIDENCE, EXPIRES_AT);
    }

    private CreateCollectionReq.FieldSchema varchar(
            String name, int maxLength, boolean primary) {
        return CreateCollectionReq.FieldSchema.builder().name(name)
                .dataType(DataType.VarChar).maxLength(maxLength)
                .isPrimaryKey(primary).autoID(false).isNullable(false).build();
    }

    private CreateCollectionReq.FieldSchema int64(String name) {
        return scalar(name, DataType.Int64);
    }

    private CreateCollectionReq.FieldSchema scalar(String name, DataType type) {
        return CreateCollectionReq.FieldSchema.builder().name(name)
                .dataType(type).isNullable(false).build();
    }

    private String text(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw == null ? "" : raw.toString();
    }

    private long number(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw instanceof Number number
                ? number.longValue() : Long.parseLong(String.valueOf(raw));
    }

    private double decimal(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw instanceof Number number
                ? number.doubleValue() : Double.parseDouble(String.valueOf(raw));
    }
}
