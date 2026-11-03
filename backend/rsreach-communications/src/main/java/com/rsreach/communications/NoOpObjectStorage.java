package com.rsreach.communications;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rsreach.media", name = "object-store-enabled", havingValue = "false", matchIfMissing = true)
public class NoOpObjectStorage implements ObjectStoragePort {

    @Override
    public String put(
            UUID tenantId,
            UUID workspaceId,
            UUID messageId,
            UUID attachmentId,
            String contentType,
            long sizeBytes,
            InputStream data
    ) {
        return null;
    }

    @Override
    public InputStream get(String storageRef) {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public String presignGet(String storageRef, int ttlSeconds) {
        throw new UnsupportedOperationException("Object store disabled (ADR 0009 flag off)");
    }
}
