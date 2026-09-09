package com.xjjk.knowledge.retrieval.index;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SdkMilvusGatewayTest {
    @Test
    void describesActualVectorIndexMetricInsteadOfAssumingCosine() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        when(client.hasCollection(any())).thenReturn(true);
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(List.of(
                        CreateCollectionReq.FieldSchema.builder().name("chunk_id")
                                .dataType(DataType.VarChar).isPrimaryKey(true).maxLength(64).build(),
                        CreateCollectionReq.FieldSchema.builder().name("embedding")
                                .dataType(DataType.FloatVector).dimension(2560).build()))
                .build();
        DescribeCollectionResp collection = mock(DescribeCollectionResp.class);
        when(collection.getCollectionSchema()).thenReturn(schema);
        when(collection.getPrimaryFieldName()).thenReturn("chunk_id");
        when(client.describeCollection(any())).thenReturn(collection);
        DescribeIndexResp.IndexDesc index = DescribeIndexResp.IndexDesc.builder()
                .fieldName("embedding").metricType(IndexParam.MetricType.L2).build();
        DescribeIndexResp indexes = mock(DescribeIndexResp.class);
        when(indexes.getIndexDescByFieldName("embedding")).thenReturn(index);
        when(client.describeIndex(any())).thenReturn(indexes);

        MilvusCollectionSpec spec = new SdkMilvusGateway(client, new MilvusProperties())
                .describe("knowledge_chunks_draft_v1");

        assertThat(spec.metric()).isEqualTo("L2");
    }
}
