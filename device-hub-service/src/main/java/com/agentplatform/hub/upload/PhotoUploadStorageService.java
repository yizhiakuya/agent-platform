package com.agentplatform.hub.upload;

import com.agentplatform.api.hub.PhotoUploadResponse;
import com.agentplatform.hub.config.PhotoUploadProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PhotoUploadStorageService {

    private static final String URL_PREFIX = "/api/uploads/photos/";
    private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    private static final Map<String, String> EXTENSION_TO_CONTENT_TYPE = Map.of(
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    private final PhotoUploadProperties props;
    private final Path root;

    public PhotoUploadStorageService(PhotoUploadProperties props) {
        this.props = props;
        this.root = props.storageDir().toAbsolutePath().normalize();
    }

    public PhotoUploadResponse store(UUID userId,
                                     InputStream input,
                                     Long declaredBytes,
                                     String declaredContentType) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        }
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image required");
        }

        validateDeclaredSize(declaredBytes);
        byte[] bytes = readBounded(input);
        if (bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image is empty");
        }

        String contentType = resolveContentType(bytes, declaredContentType);
        String extension = CONTENT_TYPE_TO_EXTENSION.get(contentType);
        UUID assetId = UUID.randomUUID();
        Path target = assetPath(userId, assetId, extension);

        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), assetId + "-", ".upload");
            try {
                Files.write(tmp, bytes);
                moveIntoPlace(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to store image", e);
        }

        return new PhotoUploadResponse(assetId, URL_PREFIX + assetId, bytes.length, contentType);
    }

    public StoredPhotoAsset load(UUID userId, UUID assetId) {
        if (userId == null || assetId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and assetId are required");
        }

        for (Map.Entry<String, String> entry : EXTENSION_TO_CONTENT_TYPE.entrySet()) {
            Path path = assetPath(userId, assetId, entry.getKey());
            if (Files.isRegularFile(path)) {
                try {
                    return new StoredPhotoAsset(
                            new FileSystemResource(path),
                            Files.size(path),
                            entry.getValue());
                } catch (IOException e) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read image", e);
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "photo asset not found");
    }

    public long maxBytes() {
        return props.maxSize().toBytes();
    }

    private void validateDeclaredSize(Long declaredBytes) {
        if (declaredBytes != null && declaredBytes > maxBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds max size");
        }
    }

    private byte[] readBounded(InputStream input) {
        long maxBytes = maxBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds max size");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to read image", e);
        }
        return out.toByteArray();
    }

    private String resolveContentType(byte[] bytes, String declaredContentType) {
        String declared = normalizeContentType(declaredContentType);
        if (declared != null
                && !declared.equals("application/octet-stream")
                && !CONTENT_TYPE_TO_EXTENSION.containsKey(declared)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported content type");
        }

        String sniffed = sniffContentType(bytes);
        if (sniffed == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported image format");
        }
        if (declared != null
                && CONTENT_TYPE_TO_EXTENSION.containsKey(declared)
                && !declared.equals(sniffed)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "content type does not match image data");
        }
        return sniffed;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int separator = contentType.indexOf(';');
        String value = separator >= 0 ? contentType.substring(0, separator) : contentType;
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.equals("image/jpg") || value.equals("image/pjpeg")) {
            return "image/jpeg";
        }
        return value;
    }

    private String sniffContentType(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 12
                && bytes[0] == 0x52
                && bytes[1] == 0x49
                && bytes[2] == 0x46
                && bytes[3] == 0x46
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50) {
            return "image/webp";
        }
        return null;
    }

    private Path assetPath(UUID userId, UUID assetId, String extension) {
        Path userDir = root.resolve(userId.toString()).normalize();
        Path path = userDir.resolve(assetId + "." + extension).normalize();
        if (!userDir.startsWith(root) || !path.startsWith(userDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid asset path");
        }
        return path;
    }

    private void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record StoredPhotoAsset(Resource resource, long bytes, String contentType) {}
}
