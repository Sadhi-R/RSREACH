package com.rsreach.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Evaluates the conditions_json array for an automation version against the current run context.
 *
 * <h3>Condition object shape</h3>
 * {@code {"field": "<name>", "op": "<op>", "value": "<value>"}}
 *
 * <h3>Supported ops</h3>
 * <ul>
 *   <li>{@code equals} – {@code context[field]} must equal {@code value} (case-sensitive).</li>
 *   <li>{@code contains} – {@code context[field]} must contain {@code value} as a substring.</li>
 *   <li>{@code group_member} – contact must be an active member of the group whose UUID is {@code value}.</li>
 *   <li>{@code consent} – contact must have consent status equal to {@code value} for the channel named in {@code field}.</li>
 *   <li>{@code channel} – contact must have an active channel of type {@code value}.</li>
 * </ul>
 *
 * <p>An empty conditions list passes unconditionally. Any unknown op returns {@code false} (fail-closed).
 * If {@code jdbcTemplate} is {@code null}, DB-backed ops ({@code group_member}, {@code consent},
 * {@code channel}) return {@code false}.
 */
class AutomationConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AutomationConditionEvaluator.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AutomationConditionEvaluator(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates all conditions. Returns {@code true} when the list is empty or all conditions pass.
     *
     * @param conditions parsed condition objects from conditions_json
     * @param context    flat string map of trigger-payload fields and contact fields
     * @param tenantId   isolation scope
     * @param workspaceId isolation scope
     * @param contactId  nullable; DB-backed ops return false when null
     */
    boolean evaluateAll(
            List<Map<String, Object>> conditions,
            Map<String, String> context,
            UUID tenantId,
            UUID workspaceId,
            UUID contactId
    ) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Map<String, Object> cond : conditions) {
            if (!evaluateOne(cond, context, tenantId, workspaceId, contactId)) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private boolean evaluateOne(
            Map<String, Object> cond,
            Map<String, String> context,
            UUID tenantId,
            UUID workspaceId,
            UUID contactId
    ) {
        String op    = str(cond, "op");
        String field = str(cond, "field");
        String value = str(cond, "value");

        if (op == null) {
            log.warn("automation_condition_missing_op cond={}", cond);
            return false;
        }

        return switch (op) {
            case "equals" -> {
                if (field == null || value == null) {
                    yield false;
                }
                String ctxVal = context.get(field);
                yield value.equals(ctxVal);
            }
            case "contains" -> {
                if (field == null || value == null) {
                    yield false;
                }
                String ctxVal = context.get(field);
                yield ctxVal != null && ctxVal.contains(value);
            }
            case "group_member" -> {
                if (contactId == null || jdbcTemplate == null || value == null) {
                    yield false;
                }
                try {
                    UUID groupId = UUID.fromString(value);
                    yield isGroupMember(tenantId, workspaceId, groupId, contactId);
                } catch (IllegalArgumentException e) {
                    log.warn("automation_condition_invalid_group_id value={}", value);
                    yield false;
                }
            }
            case "consent" -> {
                if (contactId == null || jdbcTemplate == null || field == null || value == null) {
                    yield false;
                }
                yield hasConsentStatus(tenantId, workspaceId, contactId, field, value);
            }
            case "channel" -> {
                if (contactId == null || jdbcTemplate == null || value == null) {
                    yield false;
                }
                yield hasActiveChannel(tenantId, workspaceId, contactId, value);
            }
            case "time_window" -> {
                // value may be either a JSON string or a Map (when deserialized from conditions_json).
                // Fail closed on any parse error.
                Object rawValue = cond.get("value");
                if (rawValue == null) {
                    yield false;
                }
                try {
                    int startHour;
                    int endHour;
                    if (rawValue instanceof Map<?, ?> w) {
                        Number s = (Number) w.get("startHour");
                        Number e = (Number) w.get("endHour");
                        if (s == null || e == null) {
                            yield false;
                        }
                        startHour = s.intValue();
                        endHour   = e.intValue();
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> w = objectMapper.readValue(rawValue.toString(), Map.class);
                        Number s = (Number) w.get("startHour");
                        Number e = (Number) w.get("endHour");
                        if (s == null || e == null) {
                            yield false;
                        }
                        startHour = s.intValue();
                        endHour   = e.intValue();
                    }
                    int currentHour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
                    yield currentHour >= startHour && currentHour < endHour;
                } catch (Exception ex) {
                    log.warn("automation_condition_time_window_parse_error value={}", rawValue);
                    yield false; // fail closed
                }
            }
            default -> {
                log.warn("automation_condition_unknown_op op={}", op);
                yield false;
            }
        };
    }

    private boolean isGroupMember(UUID tenantId, UUID workspaceId, UUID groupId, UUID contactId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_group_members
                WHERE tenant_id = ? AND workspace_id = ? AND group_id = ? AND contact_id = ?
                """,
                Integer.class,
                tenantId, workspaceId, groupId, contactId
        );
        return count != null && count > 0;
    }

    private boolean hasConsentStatus(
            UUID tenantId, UUID workspaceId, UUID contactId,
            String channelType, String expectedStatus
    ) {
        List<String> rows = jdbcTemplate.query(
                """
                SELECT consent_status FROM contact_consent
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                  AND channel_type = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """,
                (rs, i) -> rs.getString("consent_status"),
                tenantId, workspaceId, contactId, channelType.toUpperCase()
        );
        return !rows.isEmpty() && expectedStatus.equalsIgnoreCase(rows.get(0));
    }

    private boolean hasActiveChannel(UUID tenantId, UUID workspaceId, UUID contactId, String channelType) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_channels
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                  AND channel_type = ? AND status = 'ACTIVE'
                """,
                Integer.class,
                tenantId, workspaceId, contactId, channelType.toUpperCase()
        );
        return count != null && count > 0;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }
}
