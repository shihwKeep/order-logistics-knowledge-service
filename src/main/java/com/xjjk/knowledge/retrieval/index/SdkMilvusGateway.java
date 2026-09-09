package com.xjjk.knowledge.retrieval.index;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Milvus Java SDK 3.x 适配层。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.retrieval.milvus", name = "enabled", havingValue = "true")
public class SdkMilvusGateway implements MilvusGateway {
    private static final String CHUNK_ID = "chunk_id";
    private static final String TENANT_ID = "tenant_id";
    private static final String KNOWLEDGE_BASE_ID = "knowledge_base_id";
    private static final String DOCUMENT_ID = "document_id";
    private static final String VERSION_ID = "version_id";
    private static final String CHUNK_INDEX = "chunk_index";
    private static final String DOCUMENT_TITLE = "document_title";
    private static final String TITLE_PATH = "title_path";
    private static final String CONTENT = "content";
    private static final String CONTENT_SHA256 = "content_sha256";
    private static final String LOCATION_JSON = "location_json";
    private static final String EMBEDDING = "embedding";

    private final MilvusClientV2 client;
    private final MilvusProperties properties;

    public SdkMilvusGateway(MilvusClientV2 client, MilvusProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public MilvusCollectionSpec describe(String collection) {
        try {
            Boolean exists = client.hasCollection(HasCollectionReq.builder()
                    .databaseName(properties.getDatabaseName()).collectionName(collection).build());
            if (!Boolean.TRUE.equals(exists)) {
                return null;
            }
            DescribeCollectionResp response = client.describeCollection(DescribeCollectionReq.builder()
                    .databaseName(properties.getDatabaseName()).collectionName(collection).build());
            CreateCollectionReq.CollectionSchema schema = response.getCollectionSchema();
            if (schema == null || schema.getField(EMBEDDING) == null) {
                throw new IllegalStateException("Milvus Collection 缺少向量字段");
            }
            return new MilvusCollectionSpec(collection, schema.getField(EMBEDDING).getDimension(),
                    "COSINE", response.getPrimaryFieldName());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("检查 Milvus Collection 失败", exception);
        }
    }

    @Override
    public void create(MilvusCollectionSpec spec) {
        try {
            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .enableDynamicField(false)
                    .fieldSchemaList(List.of(
                            varchar(CHUNK_ID, 256, true),
                            int64(TENANT_ID), int64(KNOWLEDGE_BASE_ID), int64(DOCUMENT_ID), int64(VERSION_ID),
                            int64(CHUNK_INDEX), varchar(DOCUMENT_TITLE, 1024, false),
                            varchar(TITLE_PATH, 2048, false), varchar(CONTENT, 8192, false),
                            varchar(CONTENT_SHA256, 64, false), varchar(LOCATION_JSON, 4096, false),
                            CreateCollectionReq.FieldSchema.builder().name(EMBEDDING)
                                    .dataType(DataType.FloatVector).dimension(spec.dimension())
                                    .isNullable(false).build()))
                    .build();
            IndexParam index = IndexParam.builder().fieldName(EMBEDDING).indexName("idx_embedding_hnsw")
                    .indexType(IndexParam.IndexType.HNSW).metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("M", 16, "efConstruction", 256)).build();
            client.createCollection(CreateCollectionReq.builder()
                    .databaseName(properties.getDatabaseName()).collectionName(spec.name())
                    .description("Order Logistics Knowledge 2560-dimensional chunks")
                    .collectionSchema(schema).indexParams(List.of(index))
                    .consistencyLevel(ConsistencyLevel.STRONG).build());
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("创建 Milvus Collection 失败", exception);
        }
    }

    @Override
    public void load(String collection) {
        try {
            client.loadCollection(LoadCollectionReq.builder()
                    .databaseName(properties.getDatabaseName()).collectionName(collection)
                    .sync(true).timeout(properties.getRequestTimeout().toMillis()).build());
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("加载 Milvus Collection 失败", exception);
        }
    }

    @Override
    public long upsert(String collection, List<MilvusVectorRow> rows) {
        try {
            List<JsonObject> data = rows.stream().map(this::toRow).toList();
            return client.upsert(UpsertReq.builder()
                    .databaseName(properties.getDatabaseName()).collectionName(collection)
                    .data(data).partialUpdate(false).build()).getUpsertCnt();
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("写入 Milvus 向量失败", exception);
        }
    }

    @Override
    public List<MilvusMatch> search(String collection, String filter, List<Float> vector, int topK) {
        try {
            SearchResp response = client.search(SearchReq.builder()
                    .databaseName(properties.getDatabaseName()).collectionName(collection)
                    .annsField(EMBEDDING).limit(topK).filter(filter)
                    .data(List.of(new FloatVec(vector)))
                    .outputFields(outputFields()).searchParams(Map.of("ef", Math.max(64, topK * 4))).build());
            List<MilvusMatch> matches = new ArrayList<>();
            for (List<SearchResp.SearchResult> group : response.getSearchResults()) {
                for (SearchResp.SearchResult result : group) {
                    matches.add(new MilvusMatch(toChunk(result.getEntity()), result.getScore()));
                }
            }
            return List.copyOf(matches);
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("查询 Milvus 向量失败", exception);
        }
    }

    @Override
    public Map<String, String> fingerprints(String collection, String filter, int limit) {
        try {
            QueryResp response = client.query(QueryReq.builder()
                    .databaseName(properties.getDatabaseName()).collectionName(collection)
                    .filter(filter).outputFields(List.of(CHUNK_ID, CONTENT_SHA256)).limit(limit).build());
            Map<String, String> result = new LinkedHashMap<>();
            for (QueryResp.QueryResult row : response.getQueryResults()) {
                Map<String, Object> entity = row.getEntity();
                result.put(text(entity, CHUNK_ID), text(entity, CONTENT_SHA256));
            }
            return result;
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("校验 Milvus 向量失败", exception);
        }
    }

    @Override
    public void delete(String collection, String filter) {
        try {
            client.delete(DeleteReq.builder().databaseName(properties.getDatabaseName())
                    .collectionName(collection).filter(filter).build());
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("删除 Milvus 版本向量失败", exception);
        }
    }

    private CreateCollectionReq.FieldSchema varchar(String name, int maxLength, boolean primary) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(DataType.VarChar)
                .maxLength(maxLength).isPrimaryKey(primary).autoID(false).isNullable(false).build();
    }

    private CreateCollectionReq.FieldSchema int64(String name) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(DataType.Int64)
                .isNullable(false).build();
    }

    private JsonObject toRow(MilvusVectorRow row) {
        IndexChunk chunk = row.chunk();
        JsonObject json = new JsonObject();
        json.addProperty(CHUNK_ID, chunk.chunkId());
        json.addProperty(TENANT_ID, chunk.tenantId());
        json.addProperty(KNOWLEDGE_BASE_ID, chunk.knowledgeBaseId());
        json.addProperty(DOCUMENT_ID, chunk.documentId());
        json.addProperty(VERSION_ID, chunk.versionId());
        json.addProperty(CHUNK_INDEX, chunk.chunkIndex());
        json.addProperty(DOCUMENT_TITLE, safe(chunk.documentTitle()));
        json.addProperty(TITLE_PATH, safe(chunk.titlePath()));
        json.addProperty(CONTENT, chunk.content());
        json.addProperty(CONTENT_SHA256, chunk.contentSha256());
        json.addProperty(LOCATION_JSON, safe(chunk.locationJson()));
        JsonArray embedding = new JsonArray();
        row.vector().forEach(embedding::add);
        json.add(EMBEDDING, embedding);
        return json;
    }

    private IndexChunk toChunk(Map<String, Object> entity) {
        return new IndexChunk(text(entity, CHUNK_ID), number(entity, TENANT_ID),
                number(entity, KNOWLEDGE_BASE_ID), number(entity, DOCUMENT_ID), number(entity, VERSION_ID),
                Math.toIntExact(number(entity, CHUNK_INDEX)), text(entity, DOCUMENT_TITLE),
                text(entity, TITLE_PATH), text(entity, CONTENT), text(entity, CONTENT_SHA256),
                text(entity, LOCATION_JSON));
    }

    private List<String> outputFields() {
        return List.of(CHUNK_ID, TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, VERSION_ID,
                CHUNK_INDEX, DOCUMENT_TITLE, TITLE_PATH, CONTENT, CONTENT_SHA256, LOCATION_JSON);
    }

    private String text(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        return value == null ? "" : value.toString();
    }

    private long number(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
