package com.agentplatform.api.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingRequestRecordsTest {

    @Test
    void saveFactRequestDefensivelyCopiesEmbeddingAndComparesByContent() {
        UUID userId = UUID.randomUUID();
        float[] embedding = new float[]{1.0f, 2.0f};

        SaveFactRequest request = new SaveFactRequest(userId, "fact", "content", null, embedding, true);
        embedding[0] = 99.0f;

        assertThat(request.embedding()).containsExactly(1.0f, 2.0f);
        assertThat(request).isEqualTo(new SaveFactRequest(userId, "fact", "content", null,
                new float[]{1.0f, 2.0f}, true));
        assertThat(request.hashCode()).isEqualTo(new SaveFactRequest(userId, "fact", "content", null,
                new float[]{1.0f, 2.0f}, true).hashCode());
    }

    @Test
    void photoAssetEmbeddingRequestDefensivelyCopiesEmbeddingAndComparesByContent() {
        UUID assetId = UUID.randomUUID();
        float[] embedding = new float[]{0.1f, 0.2f};

        PhotoAssetEmbeddingRequest request = new PhotoAssetEmbeddingRequest(assetId, embedding, "model", 2);
        embedding[1] = 99.0f;

        assertThat(request.embedding()).containsExactly(0.1f, 0.2f);
        assertThat(request).isEqualTo(new PhotoAssetEmbeddingRequest(assetId, new float[]{0.1f, 0.2f}, "model", 2));
    }

    @Test
    void photoAssetSearchRequestDefensivelyCopiesEmbeddingAndComparesByContent() {
        UUID userId = UUID.randomUUID();
        float[] embedding = new float[]{0.3f, 0.4f};

        PhotoAssetSearchRequest request = new PhotoAssetSearchRequest(userId, embedding, 5, "bucket",
                "name", 1L, 2L, 0.7, 3, "rank", "date", "desc");
        embedding[0] = 99.0f;

        assertThat(request.queryEmbedding()).containsExactly(0.3f, 0.4f);
        assertThat(request).isEqualTo(new PhotoAssetSearchRequest(userId, new float[]{0.3f, 0.4f}, 5, "bucket",
                "name", 1L, 2L, 0.7, 3, "rank", "date", "desc"));
    }

    @Test
    void queryFactRequestDefensivelyCopiesEmbeddingAndComparesByContent() {
        UUID userId = UUID.randomUUID();
        float[] embedding = new float[]{0.5f, 0.6f};

        QueryFactRequest request = new QueryFactRequest(userId, embedding, 4);
        embedding[1] = 99.0f;

        assertThat(request.queryEmbedding()).containsExactly(0.5f, 0.6f);
        assertThat(request).isEqualTo(new QueryFactRequest(userId, new float[]{0.5f, 0.6f}, 4));
    }
}
