package com.agentplatform.hub.controller;

import com.agentplatform.api.hub.PhotoUploadResponse;
import com.agentplatform.hub.config.PhotoUploadProperties;
import com.agentplatform.hub.upload.PhotoUploadStorageService;
import com.agentplatform.hub.upload.PhotoUploadStorageService.StoredPhotoAsset;
import com.agentplatform.security.PrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/uploads/photos")
public class PhotoUploadController {

    private final PhotoUploadStorageService storage;
    private final PhotoUploadProperties props;

    public PhotoUploadController(PhotoUploadStorageService storage, PhotoUploadProperties props) {
        this.storage = storage;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<PhotoUploadResponse> upload(HttpServletRequest request) throws IOException {
        UUID userId = PrincipalContext.requireUserId();
        PhotoUploadResponse body;
        if (request instanceof MultipartHttpServletRequest multipartRequest) {
            body = uploadMultipart(userId, multipartRequest);
        } else {
            body = storage.store(
                    userId,
                    request.getInputStream(),
                    request.getContentLengthLong() >= 0 ? request.getContentLengthLong() : null,
                    request.getContentType());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{assetId}")
    public ResponseEntity<Resource> get(@PathVariable UUID assetId) {
        StoredPhotoAsset asset = storage.load(PrincipalContext.requireUserId(), assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .contentLength(asset.bytes())
                .cacheControl(CacheControl.maxAge(props.cacheMaxAge()).cachePrivate())
                .body(asset.resource());
    }

    private PhotoUploadResponse uploadMultipart(UUID userId, MultipartHttpServletRequest request) throws IOException {
        MultipartFile file = request.getFile("file");
        if (file == null) {
            file = request.getFile("image");
        }
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file part required");
        }
        return storage.store(userId, file.getInputStream(), file.getSize(), file.getContentType());
    }
}
