package com.agentplatform.agent.client;

import com.agentplatform.api.chat.CreateSessionRequest;
import com.agentplatform.api.chat.MemoryFactDto;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.PendingPhotoAssetDto;
import com.agentplatform.api.chat.PhotoAssetEmbeddingRequest;
import com.agentplatform.api.chat.PhotoAssetSearchRequest;
import com.agentplatform.api.chat.PhotoAssetSearchResult;
import com.agentplatform.api.chat.PromoteRequest;
import com.agentplatform.api.chat.QueryFactRequest;
import com.agentplatform.api.chat.RuntimeSkillDto;
import com.agentplatform.api.chat.SaveFactRequest;
import com.agentplatform.api.chat.SessionArtifactDto;
import com.agentplatform.api.chat.SessionContextSummaryDto;
import com.agentplatform.api.chat.SessionDto;
import com.agentplatform.api.chat.UpsertRuntimeSkillRequest;
import com.agentplatform.api.chat.UpsertSessionArtifactRequest;
import com.agentplatform.api.chat.UpsertSessionContextSummaryRequest;
import com.agentplatform.api.chat.WriteMessageRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Feign client to chat-service's internal endpoints. Resolved through Nacos
 * discovery + Spring Cloud LoadBalancer ({@code lb://chat-service} via the
 * {@code @FeignClient} name).
 */
@FeignClient(name = "chat-service", contextId = "internalChat", path = "/internal")
public interface InternalChatFeignClient {

    @PostMapping("/sessions")
    SessionDto createSession(@RequestBody CreateSessionRequest req);

    @PostMapping("/messages")
    MessageDto writeMessage(@RequestBody WriteMessageRequest req);

    @GetMapping("/sessions/{id}/messages")
    List<MessageDto> listMessages(@PathVariable("id") UUID sessionId, @RequestParam("userId") UUID userId);

    @PostMapping("/session-artifacts")
    SessionArtifactDto upsertArtifact(@RequestBody UpsertSessionArtifactRequest req);

    @GetMapping("/sessions/{id}/artifacts")
    List<SessionArtifactDto> listArtifacts(@PathVariable("id") UUID sessionId,
                                           @RequestParam("userId") UUID userId,
                                           @RequestParam("limit") int limit);

    @GetMapping("/sessions/{id}/context-summary")
    SessionContextSummaryDto getContextSummary(@PathVariable("id") UUID sessionId,
                                              @RequestParam("userId") UUID userId);

    @PostMapping("/session-context-summaries")
    SessionContextSummaryDto upsertContextSummary(@RequestBody UpsertSessionContextSummaryRequest req);

    /**
     * Persist a long-term memory fact (with embedding) for a user.
     * Returns {@code {"id": <uuid>}}.
     */
    @PostMapping("/memory/facts")
    Map<String, UUID> saveFact(@RequestBody SaveFactRequest req);

    /**
     * Top-K nearest memory facts for a user under cosine distance.
     */
    @PostMapping("/memory/facts/query")
    List<MemoryFactDto> queryFacts(@RequestBody QueryFactRequest req);

    @PostMapping("/memory/facts/list")
    List<MemoryFactDto> listFacts(@RequestBody Map<String, Object> req);

    @PostMapping("/memory/facts/delete")
    Map<String, Boolean> deleteFact(@RequestParam("userId") UUID userId,
                                    @RequestParam("factId") UUID factId);

    /**
     * Promote frequently-recalled raw facts into the curated tier.
     * Returns {@code {"promoted": <int>}}.
     */
    @PostMapping("/memory/promote")
    Map<String, Integer> promote(@RequestBody PromoteRequest req);

    @PostMapping("/photos/search")
    List<PhotoAssetSearchResult> searchPhotos(@RequestBody PhotoAssetSearchRequest req);

    @GetMapping("/photos/pending")
    List<PendingPhotoAssetDto> listPendingPhotos(@RequestParam("limit") int limit);

    @PostMapping("/photos/embedding")
    void savePhotoEmbedding(@RequestBody PhotoAssetEmbeddingRequest req);

    @GetMapping("/runtime-skills")
    List<RuntimeSkillDto> listRuntimeSkills(@RequestParam("userId") UUID userId,
                                            @RequestParam("includeDisabled") boolean includeDisabled);

    @GetMapping("/runtime-skills/{name}")
    RuntimeSkillDto getRuntimeSkill(@PathVariable("name") String name,
                                    @RequestParam("userId") UUID userId);

    @PostMapping("/runtime-skills")
    RuntimeSkillDto upsertRuntimeSkill(@RequestBody UpsertRuntimeSkillRequest req);

    @DeleteMapping("/runtime-skills/{name}")
    void deleteRuntimeSkill(@PathVariable("name") String name,
                            @RequestParam("userId") UUID userId);
}
