package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.PhotoAssetBatchRequest;
import com.agentplatform.api.chat.PhotoAssetBatchResponse;
import com.agentplatform.api.chat.PhotoAssetDto;
import com.agentplatform.api.chat.PhotoAssetReconcileRequest;
import com.agentplatform.api.chat.PhotoAssetReconcileResponse;
import com.agentplatform.chat.service.PhotoIndexService;
import com.agentplatform.security.Principal;
import com.agentplatform.security.PrincipalContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/photos")
public class PhotoIndexController {

    private final PhotoIndexService photoIndexService;

    public PhotoIndexController(PhotoIndexService photoIndexService) {
        this.photoIndexService = photoIndexService;
    }

    @PostMapping("/index/batch")
    public ResponseEntity<PhotoAssetBatchResponse> upsertBatch(@RequestBody PhotoAssetBatchRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        Principal principal = PrincipalContext.require();
        if (!principal.isDevice()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "device token required");
        }
        UUID userId = principal.userIdAsUuid();
        UUID deviceId = principal.subjectAsUuid();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(photoIndexService.upsertBatch(userId, deviceId, req.assets()));
    }

    @PostMapping("/index/reconcile")
    public PhotoAssetReconcileResponse reconcile(@RequestBody PhotoAssetReconcileRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        Principal principal = PrincipalContext.require();
        if (!principal.isDevice()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "device token required");
        }
        return photoIndexService.reconcileDeviceAssets(
                principal.userIdAsUuid(),
                principal.subjectAsUuid(),
                req.mediaStoreIds());
    }

    @GetMapping("/{id}")
    public PhotoAssetDto get(@PathVariable UUID id) {
        return photoIndexService.get(PrincipalContext.requireUserId(), id);
    }
}
