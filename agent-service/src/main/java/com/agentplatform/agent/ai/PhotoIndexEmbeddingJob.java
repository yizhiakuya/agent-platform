package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.PendingPhotoAssetDto;
import com.agentplatform.api.chat.PhotoAssetEmbeddingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulls thumbnail-only photo assets from chat-service and writes multimodal
 * embeddings back. Search no longer needs the phone online once this job has
 * embedded uploaded rows.
 */
@Component
@ConditionalOnProperty(name = "agent-platform.agent.photos.indexWorkerEnabled", havingValue = "true")
public class PhotoIndexEmbeddingJob {

    private static final Logger log = LoggerFactory.getLogger(PhotoIndexEmbeddingJob.class);

    private final InternalChatFeignClient chat;
    private final PhotoEmbeddingService photoEmbeddingService;
    private final int batchSize;

    public PhotoIndexEmbeddingJob(InternalChatFeignClient chat,
                                  PhotoEmbeddingService photoEmbeddingService,
                                  AgentProperties props) {
        this.chat = chat;
        this.photoEmbeddingService = photoEmbeddingService;
        AgentProperties.Photos photos = props == null || props.agent() == null ? null : props.agent().photos();
        this.batchSize = photos == null ? 25 : Math.max(1, Math.min(photos.indexBatchSize(), 100));
    }

    @Scheduled(fixedDelayString = "${PHOTO_INDEX_EMBED_DELAY_MS:15000}",
            initialDelayString = "${PHOTO_INDEX_EMBED_INITIAL_DELAY_MS:10000}")
    public void runOnce() {
        if (!photoEmbeddingService.enabled()) {
            return;
        }
        List<PendingPhotoAssetDto> pending;
        try {
            pending = chat.listPendingPhotos(batchSize);
        } catch (Exception e) {
            log.debug("[photo-index] pending fetch failed: {}", e.getMessage());
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }

        List<PendingPhotoAssetDto> batch = new ArrayList<>(pending.size());
        List<String> thumbs = new ArrayList<>(pending.size());
        for (PendingPhotoAssetDto asset : pending) {
            if (asset != null && asset.id() != null && asset.thumbB64() != null && !asset.thumbB64().isBlank()) {
                batch.add(asset);
                thumbs.add(asset.thumbB64());
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        List<float[]> embeddings;
        try {
            embeddings = photoEmbeddingService.embedImages(thumbs);
        } catch (Exception e) {
            log.warn("[photo-index] embedding batch failed count={} err={}", batch.size(), e.getMessage());
            return;
        }
        if (embeddings == null || embeddings.size() != batch.size()) {
            log.warn("[photo-index] embedding batch size mismatch requested={} returned={}",
                    batch.size(), embeddings == null ? 0 : embeddings.size());
            return;
        }

        int ok = 0;
        for (int i = 0; i < batch.size(); i++) {
            PendingPhotoAssetDto asset = batch.get(i);
            try {
                chat.savePhotoEmbedding(new PhotoAssetEmbeddingRequest(
                        asset.id(),
                        embeddings.get(i),
                        photoEmbeddingService.model(),
                        photoEmbeddingService.dim()));
                ok++;
            } catch (Exception e) {
                log.warn("[photo-index] embedding failed asset={} name={} err={}",
                        asset.id(), asset.name(), e.getMessage());
            }
        }
        if (ok > 0) {
            log.info("[photo-index] embedded {}/{} pending photo asset(s)", ok, pending.size());
        }
    }
}
