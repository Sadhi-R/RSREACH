package com.rsreach.communications;

import java.io.InputStream;
import java.util.UUID;

/**
 * S3-compatible object storage port (MinIO per ADR 0009).
 */
public interface ObjectStoragePort {

    String put(
            UUID tenantId,
            UUID workspaceId,
            UUID messageId,
            UUID attachmentId,
            String contentType,
            long sizeBytes,
            InputStream data
    );

    InputStream get(String storageRef);

    String presignGet(String storageRef, int ttlSeconds);
}
