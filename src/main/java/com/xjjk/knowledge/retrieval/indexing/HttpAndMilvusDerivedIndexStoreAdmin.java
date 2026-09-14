package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.retrieval.index.ElasticsearchProperties;
import com.xjjk.knowledge.retrieval.index.MilvusProperties;
import com.xjjk.knowledge.retrieval.index.SearchIndexUnavailableException;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class HttpAndMilvusDerivedIndexStoreAdmin implements DerivedIndexStoreAdmin {
    private final ElasticsearchProperties elasticsearch;
    private final MilvusProperties milvus;
    private final MilvusClientV2 milvusClient;
    private final HttpClient httpClient;

    public HttpAndMilvusDerivedIndexStoreAdmin(ElasticsearchProperties elasticsearch,
                                               MilvusProperties milvus,
                                               ObjectProvider<MilvusClientV2> clients) {
        this.elasticsearch = elasticsearch;
        this.milvus = milvus;
        this.milvusClient = clients.getIfAvailable();
        this.httpClient = HttpClient.newBuilder().connectTimeout(elasticsearch.getConnectTimeout()).build();
    }

    @Override
    public void deleteElasticsearchIndexIfExists(String indexName) {
        try {
            String base = elasticsearch.getBaseUrl().endsWith("/")
                    ? elasticsearch.getBaseUrl().substring(0, elasticsearch.getBaseUrl().length() - 1)
                    : elasticsearch.getBaseUrl();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/" + indexName))
                    .timeout(elasticsearch.getReadTimeout()).DELETE();
            addBasicAuth(builder);
            int status = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200 && status != 404) {
                throw new SearchIndexUnavailableException("删除 Elasticsearch 派生索引失败: HTTP " + status);
            }
        } catch (SearchIndexUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new SearchIndexUnavailableException("删除 Elasticsearch 派生索引失败", exception);
        }
    }

    @Override
    public void dropMilvusCollectionIfExists(String collectionName) {
        if (milvusClient == null) throw new SearchIndexUnavailableException("Milvus 未启用，不能执行派生索引重置");
        try {
            Boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                    .databaseName(milvus.getDatabaseName()).collectionName(collectionName).build());
            if (Boolean.TRUE.equals(exists)) {
                milvusClient.dropCollection(DropCollectionReq.builder()
                        .databaseName(milvus.getDatabaseName()).collectionName(collectionName).build());
            }
        } catch (RuntimeException exception) {
            throw new SearchIndexUnavailableException("删除 Milvus 派生集合失败", exception);
        }
    }

    private void addBasicAuth(HttpRequest.Builder builder) {
        if (elasticsearch.getUsername() == null || elasticsearch.getUsername().isBlank()) return;
        String value = elasticsearch.getUsername() + ":"
                + (elasticsearch.getPassword() == null ? "" : elasticsearch.getPassword());
        builder.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }
}
