package com.rsreach.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AutomationConditionEvaluator}.
 *
 * DB-backed ops (group_member, consent, channel) are not tested here because they require
 * JdbcTemplate. Those ops return {@code false} when jdbcTemplate is null (fail-closed),
 * which is verified by a dedicated test.
 */
class AutomationConditionEvaluatorTest {

    /** Evaluator with no JdbcTemplate — DB-backed ops are fail-closed. */
    private AutomationConditionEvaluator evaluator;

    private static final UUID TENANT_ID    = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID CONTACT_ID   = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        evaluator = new AutomationConditionEvaluator(null, new ObjectMapper());
    }

    // Convenience helper: Map<String,Object> from pairs
    @SafeVarargs
    private static Map<String, Object> cond(Object... pairs) {
        var m = new java.util.HashMap<String, Object>();
        for (int i = 0; i < pairs.length - 1; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    // -----------------------------------------------------------------------
    // Empty conditions
    // -----------------------------------------------------------------------

    @Test
    void null_conditions_passes() {
        assertThat(evaluator.evaluateAll(null, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isTrue();
    }

    @Test
    void empty_conditions_passes() {
        assertThat(evaluator.evaluateAll(List.of(), Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // equals op
    // -----------------------------------------------------------------------

    @Test
    void equals_matching_field_passes() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "contact.status", "op", "equals", "value", "ACTIVE"));
        assertThat(evaluator.evaluateAll(conditions, Map.of("contact.status", "ACTIVE"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isTrue();
    }

    @Test
    void equals_non_matching_field_fails() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "contact.status", "op", "equals", "value", "ACTIVE"));
        assertThat(evaluator.evaluateAll(conditions, Map.of("contact.status", "ARCHIVED"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void equals_missing_field_in_context_fails() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "contact.status", "op", "equals", "value", "ACTIVE"));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void equals_case_sensitive() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "contact.display_name", "op", "equals", "value", "Alice"));
        // lowercase should NOT match "Alice"
        assertThat(evaluator.evaluateAll(conditions, Map.of("contact.display_name", "alice"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void equals_missing_field_param_fails() {
        List<Map<String, Object>> conditions = List.of(cond("op", "equals", "value", "ACTIVE"));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void equals_missing_value_param_fails() {
        List<Map<String, Object>> conditions = List.of(cond("field", "contact.status", "op", "equals"));
        assertThat(evaluator.evaluateAll(conditions, Map.of("contact.status", "ACTIVE"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // contains op
    // -----------------------------------------------------------------------

    @Test
    void contains_substring_passes() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "event_type", "op", "contains", "value", "CREATED"));
        assertThat(evaluator.evaluateAll(conditions, Map.of("event_type", "CONTACT_CREATED"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isTrue();
    }

    @Test
    void contains_no_match_fails() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "event_type", "op", "contains", "value", "DELETED"));
        assertThat(evaluator.evaluateAll(conditions, Map.of("event_type", "CONTACT_CREATED"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void contains_missing_context_field_fails() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "event_type", "op", "contains", "value", "CREATED"));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Multiple conditions — AND semantics
    // -----------------------------------------------------------------------

    @Test
    void all_conditions_pass() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "contact.status", "op", "equals",   "value", "ACTIVE"),
                cond("field", "source",          "op", "contains", "value", "web")
        );
        assertThat(evaluator.evaluateAll(conditions, Map.of("contact.status", "ACTIVE", "source", "web_form"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isTrue();
    }

    @Test
    void second_condition_fails_whole_check_fails() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "contact.status", "op", "equals", "value", "ACTIVE"),
                cond("field", "source",          "op", "equals", "value", "MOBILE")
        );
        assertThat(evaluator.evaluateAll(conditions, Map.of("contact.status", "ACTIVE", "source", "web_form"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Unknown op — fail-closed
    // -----------------------------------------------------------------------

    @Test
    void unknown_op_fails_closed() {
        List<Map<String, Object>> conditions = List.of(cond("field", "x", "op", "regex_match", "value", ".*"));
        assertThat(evaluator.evaluateAll(conditions, Map.of("x", "anything"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void missing_op_fails_closed() {
        List<Map<String, Object>> conditions = List.of(cond("field", "x", "value", "y"));
        assertThat(evaluator.evaluateAll(conditions, Map.of("x", "y"), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // DB-backed ops with null JdbcTemplate — fail-closed
    // -----------------------------------------------------------------------

    @Test
    void group_member_with_null_jdbc_fails_closed() {
        List<Map<String, Object>> conditions = List.of(
                cond("op", "group_member", "value", UUID.randomUUID().toString()));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void consent_with_null_jdbc_fails_closed() {
        List<Map<String, Object>> conditions = List.of(
                cond("field", "WHATSAPP", "op", "consent", "value", "OPTED_IN"));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void channel_with_null_jdbc_fails_closed() {
        List<Map<String, Object>> conditions = List.of(cond("op", "channel", "value", "PHONE"));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Null contactId for DB-backed ops — fail-closed
    // -----------------------------------------------------------------------

    @Test
    void group_member_with_null_contact_fails_closed() {
        List<Map<String, Object>> conditions = List.of(
                cond("op", "group_member", "value", UUID.randomUUID().toString()));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, null))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // time_window op
    // -----------------------------------------------------------------------

    @Test
    void time_window_passes_when_current_utc_hour_is_inside_window() {
        int hour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        // Window: [0, 24) always contains the current hour
        List<Map<String, Object>> conditions = List.of(
                cond("op", "time_window", "value", Map.of("startHour", 0, "endHour", 24)));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isTrue();
    }

    @Test
    void time_window_fails_when_current_utc_hour_is_outside_window() {
        // Window [0, 0) is empty — no hour satisfies h >= 0 && h < 0
        List<Map<String, Object>> conditions = List.of(
                cond("op", "time_window", "value", Map.of("startHour", 0, "endHour", 0)));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void time_window_fails_closed_on_null_value() {
        List<Map<String, Object>> conditions = List.of(cond("op", "time_window"));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void time_window_fails_closed_on_missing_startHour() {
        List<Map<String, Object>> conditions = List.of(
                cond("op", "time_window", "value", Map.of("endHour", 17)));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isFalse();
    }

    @Test
    void time_window_accepts_json_string_value() {
        // value as a JSON string (as it would appear when stored in trigger_config text)
        List<Map<String, Object>> conditions = List.of(
                cond("op", "time_window", "value", "{\"startHour\":0,\"endHour\":24}"));
        assertThat(evaluator.evaluateAll(conditions, Map.of(), TENANT_ID, WORKSPACE_ID, CONTACT_ID))
                .isTrue();
    }
}
