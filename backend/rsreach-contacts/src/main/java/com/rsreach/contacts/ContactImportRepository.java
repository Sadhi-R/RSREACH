package com.rsreach.contacts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContactImportRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ContactImportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID insertJob(
            UUID tenantId,
            UUID workspaceId,
            String filename,
            String conflictPolicy,
            int rowCount,
            String columnMappingJson,
            UUID actorId,
            String idempotencyKey
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_import_jobs (
                    id, tenant_id, workspace_id, filename, status, conflict_policy, row_count,
                    column_mapping, created_by, idempotency_key, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, CAST(? AS jsonb), ?, ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, filename, conflictPolicy, rowCount,
                columnMappingJson, actorId, idempotencyKey
        );
        return id;
    }

    public void insertRow(
            UUID jobId,
            UUID tenantId,
            UUID workspaceId,
            int rowNumber,
            String payloadJson,
            String result,
            String errorCode,
            String errorMessage
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO contact_import_rows (
                    id, job_id, tenant_id, workspace_id, row_number, payload, result,
                    error_code, error_message, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, NOW(), NOW())
                """,
                UUID.randomUUID(), jobId, tenantId, workspaceId, rowNumber, payloadJson,
                result, errorCode, errorMessage
        );
    }

    public void markPreviewReady(UUID jobId, UUID tenantId, UUID workspaceId) {
        jdbcTemplate.update(
                """
                UPDATE contact_import_jobs
                SET status = 'PREVIEW_READY', preview_ready_at = NOW(), updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                jobId, tenantId, workspaceId
        );
    }

    public Optional<ContactImportModels.JobView> findJob(UUID tenantId, UUID workspaceId, UUID jobId) {
        List<ContactImportModels.JobView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, filename, status, conflict_policy, row_count,
                       success_count, failure_count, skipped_count, preview_ready_at, started_at,
                       completed_at, error_summary, column_mapping::text, created_by, created_at, updated_at
                FROM contact_import_jobs
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> mapJob(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("filename"),
                        rs.getString("status"),
                        rs.getString("conflict_policy"),
                        rs.getInt("row_count"),
                        rs.getInt("success_count"),
                        rs.getInt("failure_count"),
                        rs.getInt("skipped_count"),
                        toInstant(rs.getTimestamp("preview_ready_at")),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("completed_at")),
                        rs.getString("error_summary"),
                        rs.getString("column_mapping"),
                        rs.getObject("created_by", UUID.class),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at")),
                        List.of()),
                jobId, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public Optional<ContactImportModels.JobView> findByIdempotencyKey(
            UUID tenantId, UUID workspaceId, String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        List<ContactImportModels.JobView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, filename, status, conflict_policy, row_count,
                       success_count, failure_count, skipped_count, preview_ready_at, started_at,
                       completed_at, error_summary, column_mapping::text, created_by, created_at, updated_at
                FROM contact_import_jobs
                WHERE tenant_id = ? AND workspace_id = ? AND idempotency_key = ?
                """,
                (rs, i) -> mapJob(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("filename"),
                        rs.getString("status"),
                        rs.getString("conflict_policy"),
                        rs.getInt("row_count"),
                        rs.getInt("success_count"),
                        rs.getInt("failure_count"),
                        rs.getInt("skipped_count"),
                        toInstant(rs.getTimestamp("preview_ready_at")),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("completed_at")),
                        rs.getString("error_summary"),
                        rs.getString("column_mapping"),
                        rs.getObject("created_by", UUID.class),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at")),
                        List.of()),
                tenantId, workspaceId, idempotencyKey
        );
        return rows.stream().findFirst();
    }

    public List<ContactImportModels.RowPreview> listSampleRows(UUID jobId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT row_number, result, error_code, error_message, payload::text
                FROM contact_import_rows
                WHERE job_id = ?
                ORDER BY row_number
                LIMIT ?
                """,
                (rs, i) -> new ContactImportModels.RowPreview(
                        rs.getInt("row_number"),
                        rs.getString("result"),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        readMap(rs.getString("payload"))
                ),
                jobId, limit
        );
    }

    public boolean markRunning(UUID jobId, UUID tenantId, UUID workspaceId, String conflictPolicy) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_import_jobs
                SET status = 'RUNNING',
                    conflict_policy = ?,
                    started_at = COALESCE(started_at, NOW()),
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                  AND status = 'PREVIEW_READY'
                """,
                conflictPolicy, jobId, tenantId, workspaceId
        );
        return updated == 1;
    }

    public List<RowWorkItem> claimPendingRows(UUID jobId, UUID tenantId, UUID workspaceId, int limit) {
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT id
                FROM contact_import_rows
                WHERE job_id = ? AND tenant_id = ? AND workspace_id = ?
                  AND result IN ('PENDING', 'OK', 'WARNING')
                ORDER BY row_number
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                jobId, tenantId, workspaceId, limit
        );
        if (ids.isEmpty()) {
            return List.of();
        }
        List<RowWorkItem> items = new ArrayList<>();
        for (UUID id : ids) {
            jdbcTemplate.update(
                    "UPDATE contact_import_rows SET result = 'PROCESSING', updated_at = NOW() WHERE id = ?",
                    id
            );
            jdbcTemplate.query(
                    """
                    SELECT id, row_number, payload::text, result
                    FROM contact_import_rows
                    WHERE id = ?
                    """,
                    rs -> {
                        items.add(new RowWorkItem(
                                rs.getObject("id", UUID.class),
                                rs.getInt("row_number"),
                                readStringMap(rs.getString("payload")),
                                rs.getString("result")
                        ));
                    },
                    id
            );
        }
        return items;
    }

    public void updateRowResult(
            UUID rowId,
            String result,
            String errorCode,
            String errorMessage,
            UUID contactId
    ) {
        jdbcTemplate.update(
                """
                UPDATE contact_import_rows
                SET result = ?, error_code = ?, error_message = ?, contact_id = ?, updated_at = NOW()
                WHERE id = ?
                """,
                result, errorCode, errorMessage, contactId, rowId
        );
    }

    public void incrementCounters(UUID jobId, int successDelta, int failureDelta, int skippedDelta) {
        jdbcTemplate.update(
                """
                UPDATE contact_import_jobs
                SET success_count = success_count + ?,
                    failure_count = failure_count + ?,
                    skipped_count = skipped_count + ?,
                    updated_at = NOW()
                WHERE id = ?
                """,
                successDelta, failureDelta, skippedDelta, jobId
        );
    }

    public boolean hasProcessableRows(UUID jobId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_import_rows
                WHERE job_id = ? AND result IN ('PENDING', 'OK', 'WARNING')
                """,
                Integer.class, jobId
        );
        return count != null && count > 0;
    }

    public void markCompleted(UUID jobId) {
        Integer previewErrors = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_import_rows
                WHERE job_id = ? AND result = 'ERROR'
                """,
                Integer.class, jobId
        );
        int errorCount = previewErrors == null ? 0 : previewErrors;
        jdbcTemplate.update(
                """
                UPDATE contact_import_jobs
                SET status = 'COMPLETED',
                    failure_count = failure_count + ?,
                    completed_at = NOW(),
                    updated_at = NOW()
                WHERE id = ?
                """,
                errorCount, jobId
        );
    }

    public void markFailed(UUID jobId, String summary) {
        jdbcTemplate.update(
                """
                UPDATE contact_import_jobs
                SET status = 'FAILED', error_summary = ?, completed_at = NOW(), updated_at = NOW()
                WHERE id = ?
                """,
                summary, jobId
        );
    }

    public void setIdempotencyKey(UUID jobId, UUID tenantId, UUID workspaceId, String key) {
        jdbcTemplate.update(
                """
                UPDATE contact_import_jobs
                SET idempotency_key = ?, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                key, jobId, tenantId, workspaceId
        );
    }

    public UUID insertExportJob(
            UUID tenantId,
            UUID workspaceId,
            String filterQuery,
            String filterStatus,
            UUID actorId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_export_jobs (
                    id, tenant_id, workspace_id, status, filter_query, filter_status, created_by, created_at
                ) VALUES (?, ?, ?, 'PENDING', ?, ?, ?, NOW())
                """,
                id, tenantId, workspaceId, filterQuery, filterStatus, actorId
        );
        return id;
    }

    public void completeExportJob(UUID jobId, String csv, int rowCount) {
        jdbcTemplate.update(
                """
                UPDATE contact_export_jobs
                SET status = 'COMPLETED', csv_content = ?, row_count = ?, completed_at = NOW()
                WHERE id = ?
                """,
                csv, rowCount, jobId
        );
    }

    public Optional<ExportJobRow> findExportJob(UUID tenantId, UUID workspaceId, UUID jobId) {
        List<ExportJobRow> rows = jdbcTemplate.query(
                """
                SELECT id, status, row_count, csv_content
                FROM contact_export_jobs
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> new ExportJobRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("row_count"),
                        rs.getString("csv_content")
                ),
                jobId, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Unable to serialize import payload");
        }
    }

    public ContactImportModels.ColumnMapping readMapping(String json) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json == null ? "{}" : json, MAP_TYPE);
            String displayName = stringOrNull(raw.get("displayName"));
            String email = stringOrNull(raw.get("email"));
            String phone = stringOrNull(raw.get("phone"));
            @SuppressWarnings("unchecked")
            Map<String, String> custom = raw.get("customFields") instanceof Map<?, ?> m
                    ? (Map<String, String>) m
                    : Map.of();
            return new ContactImportModels.ColumnMapping(
                    displayName,
                    email,
                    phone,
                    custom,
                    stringOrNull(raw.get("consentEmailMarketing")),
                    stringOrNull(raw.get("consentWhatsappMarketing"))
            );
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Unable to read column mapping");
        }
    }

    public void markExportRunning(UUID jobId) {
        jdbcTemplate.update(
                """
                UPDATE contact_export_jobs
                SET status = 'RUNNING'
                WHERE id = ? AND status = 'PENDING'
                """,
                jobId
        );
    }

    public void failExportJob(UUID jobId, String summary) {
        jdbcTemplate.update(
                """
                UPDATE contact_export_jobs
                SET status = 'FAILED', completed_at = NOW()
                WHERE id = ?
                """,
                jobId
        );
    }

    public Optional<ExportJobDetail> findExportJobDetail(UUID tenantId, UUID workspaceId, UUID jobId) {
        List<ExportJobDetail> rows = jdbcTemplate.query(
                """
                SELECT id, status, row_count, csv_content, filter_query, filter_status, created_by
                FROM contact_export_jobs
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> new ExportJobDetail(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("row_count"),
                        rs.getString("csv_content"),
                        rs.getString("filter_query"),
                        rs.getString("filter_status"),
                        rs.getObject("created_by", UUID.class)
                ),
                jobId, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    private ContactImportModels.JobView mapJob(
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
            String mappingJson,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<ContactImportModels.RowPreview> sampleRows
    ) {
        return new ContactImportModels.JobView(
                id, tenantId, workspaceId, filename, status, conflictPolicy, rowCount,
                successCount, failureCount, skippedCount, previewReadyAt, startedAt, completedAt,
                errorSummary, readMapping(mappingJson), createdBy, createdAt, updatedAt, sampleRows
        );
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private Map<String, String> readStringMap(String json) {
        Map<String, Object> raw = readMap(json);
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record RowWorkItem(UUID id, int rowNumber, Map<String, String> payload, String result) {
    }

    public record ExportJobRow(UUID id, String status, int rowCount, String csvContent) {
    }

    public record ExportJobDetail(
            UUID id,
            String status,
            int rowCount,
            String csvContent,
            String filterQuery,
            String filterStatus,
            UUID createdBy
    ) {
    }
}
