package com.agentplatform.hub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@ConfigurationProperties(prefix = "agent-platform.uploads.photos")
public record PhotoUploadProperties(
        Path storageDir,
        DataSize maxSize,
        Duration cacheMaxAge
) {
    public PhotoUploadProperties {
        if (storageDir == null) {
            storageDir = Paths.get(System.getProperty("java.io.tmpdir"),
                    "agent-platform", "uploads", "photos");
        }
        if (maxSize == null) {
            maxSize = DataSize.ofMegabytes(10);
        }
        if (cacheMaxAge == null) {
            cacheMaxAge = Duration.ofDays(30);
        }
    }
}
