package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.MemoryFactDto;
import com.agentplatform.api.chat.PromoteRequest;
import com.agentplatform.api.chat.QueryFactRequest;
import com.agentplatform.api.chat.SaveFactRequest;
import com.agentplatform.chat.service.MemoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal API used by agent-service via {@code InternalChatFeignClient}.
 * agent-service computes embeddings (it has the OpenAI / sub2api creds) and
 * pushes raw {@code float[]} here; chat-service only persists & retrieves.
 */
@RestController
@RequestMapping("/internal/memory")
public class InternalMemoryController {

    private final MemoryService memoryService;

    public InternalMemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @PostMapping("/facts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> saveFact(@RequestBody SaveFactRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        UUID id = memoryService.saveFact(
                req.userId(),
                req.kind(),
                req.content(),
                req.sourceMessageId(),
                req.embedding());
        return Map.of("id", id);
    }

    @PostMapping("/facts/query")
    public List<MemoryFactDto> queryFacts(@RequestBody QueryFactRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        return memoryService.queryFacts(req.userId(), req.queryEmbedding(), req.topK());
    }

    /**
     * Promote raw facts (those above an access-count threshold) to the curated
     * tier. Triggered manually for now — agent-service will eventually call this
     * on a cron, but v0 leaves it as an opt-in operator endpoint.
     *
     * <p>Body defaults if unset: {@code minAccessCount=2}, {@code maxToPromote=20}.
     */
    @PostMapping("/promote")
    public Map<String, Integer> promote(@RequestBody PromoteRequest req) {
        if (req == null || req.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        }
        int min = req.minAccessCount() <= 0 ? 2 : req.minAccessCount();
        int cap = req.maxToPromote() <= 0 ? 20 : req.maxToPromote();
        int promoted = memoryService.promoteHotFacts(req.userId(), min, cap);
        return Map.of("promoted", promoted);
    }
}
