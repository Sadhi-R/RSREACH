package com.rsreach.contacts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContactImportModels {

    public static final String EVENT_CONTACT_IMPORT_PROCESS = "CONTACT_IMPORT_PROCESS";
    public static final String EVENT_CONTACT_EXPORT_PROCESS = "CONTACT_EXPORT_PROCESS";
    public static final String AGGREGATE_IMPORT_JOB = "contact_import_job";
    public static final String AGGREGATE_EXPORT_JOB = "contact_export_job";

    public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    public static final int MAX_ROWS = 10_000;
    public static final int PREVIEW_VALIDATE_ROWS = 100;
    public static final int WORKER_BATCH_SIZE = 200;

    public static final String POLICY_SKIP = "SKIP";
    public static final String POLICY_UPDATE = "UPDATE";
    public static final String POLICY_CREATE_ANYWAY = "CREATE_ANYWAY";

    private ContactImportModels() {
    }

    public record ColumnMapping(
            String displayName,
            String email,
            String phone,
            Map<String, String> customFields,
            String consentEmailMarketing,
            String consentWhatsappMarketing
    ) {
        public ColumnMapping {
            if (customFields == null) {
                customFields = Map.of();
            }
        }

        public ColumnMapping(String displayName, String email, String phone, Map<String, String> customFields) {
            this(displayName, email, phone, customFields, null, null);
        }

        public boolean hasConsentColumns() {
            return (consentEmailMarketing != null && !consentEmailMarketing.isBlank())
                    || (consentWhatsappMarketing != null && !consentWhatsappMarketing.isBlank());
        }
    }

    public record PreviewRequest(
            ColumnMapping mapping
    ) {
    }

    public record CommitRequest(
            String conflictPolicy
    ) {
    }

    public record RowPreview(
            int rowNumber,
            String result,
            String errorCode,
            String errorMessage,
            Map<String, Object> payload
    ) {
    }

    public record JobView(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String filename,
            String status,
            String conflictPolicy,
            int rowCount,
            int successCount,
            int failureCount,
            int skippedCount,
            Instant previewReadyAt,
            Instant startedAt,
            Instant completedAt,
            String errorSummary,
            ColumnMapping columnMapping,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<RowPreview> sampleRows
    ) {
    }

    public record ExportResult(
            String filename,
            String csvContent,
            int rowCount,
            boolean async,
            UUID jobId
    ) {
    }

    public record ExportJobStatus(
            UUID id,
            String status,
            int rowCount,
            boolean ready
    ) {
    }
}
