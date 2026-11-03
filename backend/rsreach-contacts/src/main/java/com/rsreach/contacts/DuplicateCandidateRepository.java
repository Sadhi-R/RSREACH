package com.rsreach.contacts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DuplicateCandidateRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public DuplicateCandidateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DuplicateModels.CandidateSummary> findById(
            UUID tenantId,
            UUID workspaceId,
            UUID candidateId
    ) {
        List<DuplicateModels.CandidateSummary> rows = jdbcTemplate.query(
                """
                SELECT id, contact_id_a, contact_id_b, match_types::text, confidence, score,
                       merge_allowed, status, detected_at, last_detected_at, evidence::text
                FROM contact_duplicate_candidates
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> mapSummary(rs, false),
                candidateId, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public Optional<CandidateRow> findRow(UUID tenantId, UUID workspaceId, UUID candidateId) {
        return findRow(tenantId, workspaceId, candidateId, false);
    }

    public Optional<CandidateRow> findRowForUpdate(UUID tenantId, UUID workspaceId, UUID candidateId) {
        return findRow(tenantId, workspaceId, candidateId, true);
    }

    private Optional<CandidateRow> findRow(
            UUID tenantId,
            UUID workspaceId,
            UUID candidateId,
            boolean forUpdate
    ) {
        String sql = """
                SELECT id, contact_id_a, contact_id_b, match_types::text, confidence, score,
                       merge_allowed, status, evidence::text, detected_at, last_detected_at,
                       dismissed_at, dismiss_reason
                FROM contact_duplicate_candidates
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        List<CandidateRow> rows = jdbcTemplate.query(
                sql,
                (rs, i) -> new CandidateRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("contact_id_a"),
                        (UUID) rs.getObject("contact_id_b"),
                        parseStringList(rs.getString("match_types")),
                        rs.getString("confidence"),
                        rs.getInt("score"),
                        rs.getBoolean("merge_allowed"),
                        rs.getString("status"),
                        parseEvidence(rs.getString("evidence")),
                        rs.getTimestamp("detected_at").toInstant(),
                        rs.getTimestamp("last_detected_at").toInstant(),
                        optionalInstant(rs.getTimestamp("dismissed_at")),
                        rs.getString("dismiss_reason")
                ),
                candidateId, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public DuplicateModels.CandidatePage list(
            UUID tenantId,
            UUID workspaceId,
            String status,
            UUID contactId,
            int page,
            int size
    ) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        String statusClause = status == null || status.isBlank() ? " AND status = 'OPEN'" : " AND status = ?";
        String contactClause = contactId == null
                ? ""
                : " AND (contact_id_a = ? OR contact_id_b = ?)";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(workspaceId);
        if (status != null && !status.isBlank()) {
            params.add(status.trim().toUpperCase());
        }
        if (contactId != null) {
            params.add(contactId);
            params.add(contactId);
        }
        params.add(limit + 1);
        params.add(offset);

        String sql = """
                SELECT id, contact_id_a, contact_id_b, CAST(match_types AS text), confidence, score,
                       merge_allowed, status, detected_at, last_detected_at, CAST(evidence AS text)
                FROM contact_duplicate_candidates
                WHERE tenant_id = ? AND workspace_id = ?
                """
                + statusClause
                + contactClause
                + """
                 ORDER BY score DESC, last_detected_at DESC
                LIMIT ? OFFSET ?
                """;
        List<DuplicateModels.CandidateSummary> items = jdbcTemplate.query(
                sql,
                (rs, i) -> mapSummary(rs, false),
                params.toArray()
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new DuplicateModels.CandidatePage(items, page, limit, hasNext);
    }

    public void upsertOpenOrRefresh(
            UUID tenantId,
            UUID workspaceId,
            UUID contactLow,
            UUID contactHigh,
            List<String> matchTypes,
            String confidence,
            int score,
            String evidenceJson
    ) {
        // DISMISSED: bump detection timestamp only — never reopen via rescan.
        int dismissedTouched = jdbcTemplate.update(
                """
                UPDATE contact_duplicate_candidates
                SET last_detected_at = NOW(), updated_at = NOW()
                WHERE tenant_id = ? AND workspace_id = ?
                  AND contact_id_a = ? AND contact_id_b = ?
                  AND status = 'DISMISSED'
                """,
                tenantId, workspaceId, contactLow, contactHigh
        );
        if (dismissedTouched > 0) {
            return;
        }
        int mergedTouched = jdbcTemplate.update(
                """
                UPDATE contact_duplicate_candidates
                SET last_detected_at = NOW(), updated_at = NOW()
                WHERE tenant_id = ? AND workspace_id = ?
                  AND contact_id_a = ? AND contact_id_b = ?
                  AND status = 'MERGED'
                """,
                tenantId, workspaceId, contactLow, contactHigh
        );
        if (mergedTouched > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO contact_duplicate_candidates (
                    id, tenant_id, workspace_id, contact_id_a, contact_id_b,
                    match_types, confidence, score, merge_allowed, evidence, status,
                    detected_at, updated_at, last_detected_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, TRUE, CAST(? AS jsonb), 'OPEN',
                          NOW(), NOW(), NOW())
                ON CONFLICT (tenant_id, workspace_id, contact_id_a, contact_id_b) DO UPDATE
                SET match_types = EXCLUDED.match_types,
                    confidence = EXCLUDED.confidence,
                    score = EXCLUDED.score,
                    evidence = EXCLUDED.evidence,
                    merge_allowed = TRUE,
                    last_detected_at = NOW(),
                    updated_at = NOW(),
                    status = CASE
                        WHEN contact_duplicate_candidates.status IN ('DISMISSED', 'MERGED')
                            THEN contact_duplicate_candidates.status
                        ELSE 'OPEN'
                    END
                """,
                UUID.randomUUID(), tenantId, workspaceId, contactLow, contactHigh,
                toJson(matchTypes), confidence, score, evidenceJson
        );
    }

    public void markSuperseded(UUID tenantId, UUID workspaceId, UUID candidateId) {
        jdbcTemplate.update(
                """
                UPDATE contact_duplicate_candidates
                SET status = 'SUPERSEDED', updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'OPEN'
                """,
                candidateId, tenantId, workspaceId
        );
    }

    public void markSupersededFromDismissed(UUID tenantId, UUID workspaceId, UUID candidateId) {
        jdbcTemplate.update(
                """
                UPDATE contact_duplicate_candidates
                SET status = 'SUPERSEDED',
                    dismissed_by = NULL,
                    dismissed_at = NULL,
                    dismiss_reason = NULL,
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'DISMISSED'
                """,
                candidateId, tenantId, workspaceId
        );
    }

    public void dismiss(
            UUID tenantId,
            UUID workspaceId,
            UUID candidateId,
            UUID actorId,
            String reason
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_duplicate_candidates
                SET status = 'DISMISSED',
                    dismissed_by = ?,
                    dismissed_at = NOW(),
                    dismiss_reason = ?,
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                  AND status IN ('OPEN', 'SUPERSEDED')
                """,
                actorId, reason, candidateId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Candidate cannot be dismissed in its current status");
        }
    }

    public void reopen(UUID tenantId, UUID workspaceId, UUID candidateId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_duplicate_candidates
                SET status = 'OPEN',
                    dismissed_by = NULL,
                    dismissed_at = NULL,
                    dismiss_reason = NULL,
                    updated_at = NOW(),
                    last_detected_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'DISMISSED'
                """,
                candidateId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Only DISMISSED candidates can be reopened");
        }
    }

    public List<UUID> listOpenCandidateIds(UUID tenantId, UUID workspaceId) {
        return jdbcTemplate.query(
                """
                SELECT id FROM contact_duplicate_candidates
                WHERE tenant_id = ? AND workspace_id = ? AND status = 'OPEN'
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId
        );
    }

    public Optional<UUID> findPendingScanOutboxId(UUID tenantId, UUID workspaceId) {
        List<UUID> rows = jdbcTemplate.query(
                """
                SELECT id FROM outbox_events
                WHERE tenant_id = ? AND workspace_id = ?
                  AND event_type = ?
                  AND status = 'PENDING'
                ORDER BY created_at ASC
                LIMIT 1
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId, DuplicateModels.EVENT_CONTACT_DUPLICATE_SCAN
        );
        return rows.stream().findFirst();
    }

    public List<ChannelMatchRow> findExactMatchesForContact(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId
    ) {
        return jdbcTemplate.query(
                """
                SELECT c1.id AS channel_id_self,
                       c2.id AS channel_id_other,
                       c2.contact_id AS other_contact_id,
                       c1.channel_type,
                       c1.value_normalized
                FROM contact_channels c1
                JOIN contact_channels c2
                  ON c2.tenant_id = c1.tenant_id
                 AND c2.workspace_id = c1.workspace_id
                 AND c2.channel_type = c1.channel_type
                 AND c2.value_normalized = c1.value_normalized
                 AND c2.contact_id <> c1.contact_id
                 AND c2.status IN ('ACTIVE', 'ARCHIVED')
                JOIN contacts other
                  ON other.id = c2.contact_id
                 AND other.tenant_id = c1.tenant_id
                 AND other.home_workspace_id = c1.workspace_id
                 AND other.merged_into_contact_id IS NULL
                JOIN contacts self
                  ON self.id = c1.contact_id
                 AND self.tenant_id = c1.tenant_id
                 AND self.home_workspace_id = c1.workspace_id
                 AND self.merged_into_contact_id IS NULL
                WHERE c1.tenant_id = ?
                  AND c1.workspace_id = ?
                  AND c1.contact_id = ?
                  AND c1.channel_type IN ('EMAIL', 'PHONE')
                  AND c1.status IN ('ACTIVE', 'ARCHIVED')
                """,
                (rs, i) -> new ChannelMatchRow(
                        (UUID) rs.getObject("channel_id_self"),
                        (UUID) rs.getObject("channel_id_other"),
                        (UUID) rs.getObject("other_contact_id"),
                        rs.getString("channel_type"),
                        rs.getString("value_normalized")
                ),
                tenantId, workspaceId, contactId
        );
    }

    public List<UUID> listContactIdsForScan(UUID tenantId, UUID workspaceId, int limit, UUID afterId) {
        if (afterId == null) {
            return jdbcTemplate.query(
                    """
                    SELECT id FROM contacts
                    WHERE tenant_id = ? AND home_workspace_id = ?
                      AND merged_into_contact_id IS NULL
                    ORDER BY id
                    LIMIT ?
                    """,
                    (rs, i) -> (UUID) rs.getObject("id"),
                    tenantId, workspaceId, limit
            );
        }
        return jdbcTemplate.query(
                """
                SELECT id FROM contacts
                WHERE tenant_id = ? AND home_workspace_id = ?
                  AND merged_into_contact_id IS NULL
                  AND id > ?
                ORDER BY id
                LIMIT ?
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId, afterId, limit
        );
    }

    public Optional<ChannelLookup> findChannel(
            UUID tenantId,
            UUID workspaceId,
            UUID channelId
    ) {
        List<ChannelLookup> rows = jdbcTemplate.query(
                """
                SELECT id, contact_id, channel_type, value_raw, value_normalized, status
                FROM contact_channels
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> new ChannelLookup(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("contact_id"),
                        rs.getString("channel_type"),
                        rs.getString("value_raw"),
                        rs.getString("value_normalized"),
                        rs.getString("status")
                ),
                channelId, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public static String buildEvidenceJson(List<DuplicateModels.MatchEvidence> matches, int score) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            List<Map<String, Object>> matchMaps = new ArrayList<>();
            Map<String, Object> breakdown = new LinkedHashMap<>();
            boolean email = false;
            boolean phone = false;
            for (DuplicateModels.MatchEvidence match : matches) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("matchType", match.matchType());
                m.put("channelType", match.channelType());
                m.put("channelIdA", match.channelIdA().toString());
                m.put("channelIdB", match.channelIdB().toString());
                matchMaps.add(m);
                if ("EMAIL".equals(match.channelType())) {
                    email = true;
                    breakdown.put("exactEmail", 80);
                }
                if ("PHONE".equals(match.channelType())) {
                    phone = true;
                    breakdown.put("exactPhone", 80);
                }
            }
            if (email && phone) {
                breakdown.put("combined", 100);
            }
            root.put("matches", matchMaps);
            root.put("scoreBreakdown", breakdown);
            root.put("score", score);
            return MAPPER.writeValueAsString(root);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to serialize candidate evidence");
        }
    }

    public List<DuplicateModels.MatchEvidence> parseEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> root = MAPPER.readValue(json, new TypeReference<>() {
            });
            Object matches = root.get("matches");
            if (!(matches instanceof List<?> list)) {
                return List.of();
            }
            List<DuplicateModels.MatchEvidence> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                result.add(new DuplicateModels.MatchEvidence(
                        String.valueOf(map.get("matchType")),
                        String.valueOf(map.get("channelType")),
                        UUID.fromString(String.valueOf(map.get("channelIdA"))),
                        UUID.fromString(String.valueOf(map.get("channelIdB")))
                ));
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private DuplicateModels.CandidateSummary mapSummary(java.sql.ResultSet rs, boolean stale)
            throws java.sql.SQLException {
        return new DuplicateModels.CandidateSummary(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("contact_id_a"),
                (UUID) rs.getObject("contact_id_b"),
                parseStringList(rs.getString("match_types")),
                rs.getString("confidence"),
                rs.getInt("score"),
                rs.getBoolean("merge_allowed"),
                rs.getString("status"),
                rs.getTimestamp("detected_at").toInstant(),
                rs.getTimestamp("last_detected_at").toInstant(),
                stale
        );
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JSON serialization failed");
        }
    }

    private static java.time.Instant optionalInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record ChannelMatchRow(
            UUID channelIdSelf,
            UUID channelIdOther,
            UUID otherContactId,
            String channelType,
            String valueNormalized
    ) {
    }

    public record ChannelLookup(
            UUID id,
            UUID contactId,
            String channelType,
            String valueRaw,
            String valueNormalized,
            String status
    ) {
    }

    public record CandidateRow(
            UUID id,
            UUID contactIdA,
            UUID contactIdB,
            List<String> matchTypes,
            String confidence,
            int score,
            boolean mergeAllowed,
            String status,
            List<DuplicateModels.MatchEvidence> evidence,
            java.time.Instant detectedAt,
            java.time.Instant lastDetectedAt,
            java.time.Instant dismissedAt,
            String dismissReason
    ) {
    }
}
