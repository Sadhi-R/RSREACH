package com.rsreach.communications;

import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fetches provider media bytes (placeholder when live disabled) and stores via {@link ObjectStoragePort}.
 */
@Service
public class ProviderMediaFetchService {

    private static final Logger log = LoggerFactory.getLogger(ProviderMediaFetchService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStoragePort objectStoragePort;
    private final EntitlementService entitlementService;

    public ProviderMediaFetchService(
            JdbcTemplate jdbcTemplate,
            ObjectStoragePort objectStoragePort,
            EntitlementService entitlementService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectStoragePort = objectStoragePort;
        this.entitlementService = entitlementService;
    }

    @Transactional
    public String materializeAttachment(
            UUID tenantId,
            UUID workspaceId,
            UUID messageId,
            UUID attachmentId,
            String providerMediaId,
            String mimeType
    ) {
        // Live Meta/Gmail download requires operator credentials; store a deterministic stub payload when store enabled.
        byte[] bytes = ("provider-media:" + providerMediaId).getBytes();
        long sizeBytes = Math.max(1L, (long) bytes.length);
        // Entitlement: check MEDIA_BYTES limit before storing
        BillingModels.CheckResult mediaCheck = entitlementService.check(tenantId, BillingModels.METER_MEDIA_BYTES, sizeBytes);
        if (!mediaCheck.allowed()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Entitlement limit exceeded: MEDIA_BYTES – " + mediaCheck.reason());
        }
        String ref = objectStoragePort.put(
                tenantId,
                workspaceId,
                messageId,
                attachmentId,
                mimeType == null ? "application/octet-stream" : mimeType,
                bytes.length,
                new ByteArrayInputStream(bytes)
        );
        if (ref != null) {
            jdbcTemplate.update(
                    """
                    UPDATE message_attachments
                    SET storage_ref = ?
                    WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                    """,
                    ref, attachmentId, tenantId, workspaceId
            );
            // Entitlement: record MEDIA_BYTES usage after successful store
            entitlementService.consume(
                    tenantId, workspaceId,
                    BillingModels.METER_MEDIA_BYTES, sizeBytes,
                    "MEDIA:" + attachmentId,
                    "message_attachment", attachmentId.toString()
            );
            log.info("attachment_materialized attachmentId={}", attachmentId);
        }
        return ref;
    }

    @Transactional(readOnly = true)
    public List<UUID> listPendingMaterialization(UUID tenantId, UUID workspaceId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT id FROM message_attachments
                WHERE tenant_id = ? AND workspace_id = ?
                  AND provider_media_id IS NOT NULL AND storage_ref IS NULL
                ORDER BY created_at
                LIMIT ?
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId, Math.min(Math.max(limit, 1), 100)
        );
    }
}
