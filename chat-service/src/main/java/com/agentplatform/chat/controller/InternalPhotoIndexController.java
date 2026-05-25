package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.PendingPhotoAssetDto;
import com.agentplatform.api.chat.PhotoAssetEmbeddingRequest;
import com.agentplatform.api.chat.PhotoAssetSearchRequest;
import com.agentplatform.api.chat.PhotoAssetSearchResult;
import com.agentplatform.chat.service.PhotoIndexService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/photos")
public class InternalPhotoIndexController {

    private final PhotoIndexService photoIndexService;

    public InternalPhotoIndexController(PhotoIndexService photoIndexService) {
        this.photoIndexService = photoIndexService;
    }

    @PostMapping("/search")
    public List<PhotoAssetSearchResult> search(@RequestBody PhotoAssetSearchRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        return photoIndexService.search(req);
    }

    @GetMapping("/pending")
    public List<PendingPhotoAssetDto> pending(@RequestParam(defaultValue = "50") int limit) {
        return photoIndexService.listPending(limit);
    }

    @PostMapping("/embedding")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveEmbedding(@RequestBody PhotoAssetEmbeddingRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        photoIndexService.saveEmbedding(req.assetId(), req.embedding(), req.embeddingModel(), req.embeddingDim());
    }
}
