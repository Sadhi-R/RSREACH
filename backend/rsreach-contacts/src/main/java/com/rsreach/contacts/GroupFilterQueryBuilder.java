package com.rsreach.contacts;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds parameterized SQL fragments from an allow-listed filter definition.
 * Field names never come from unsanitized user input outside the allow-list switch.
 */
public final class GroupFilterQueryBuilder {

    private GroupFilterQueryBuilder() {
    }

    public record BuiltFilter(String whereSql, List<Object> params) {
    }

    public static BuiltFilter build(GroupFilterModels.FilterDefinition definition) {
        if (definition == null || definition.conditions().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Filter has no conditions");
        }
        String joiner = "ALL".equals(definition.match()) ? " AND " : " OR ";
        List<String> parts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (GroupFilterModels.Condition condition : definition.conditions()) {
            parts.add("(" + conditionSql(condition, params) + ")");
        }
        return new BuiltFilter(String.join(joiner, parts), params);
    }

    private static String conditionSql(GroupFilterModels.Condition condition, List<Object> params) {
        String field = condition.field();
        String op = condition.op();
        if (field.startsWith("consent.")) {
            return consentSql(field, op, condition.value(), params);
        }
        if (field.startsWith("custom.")) {
            String key = field.substring("custom.".length());
            return customSql(key, op, condition.value(), params);
        }
        String column = switch (field) {
            case "display_name" -> "c.display_name";
            case "status" -> "c.status";
            case "primary_email_normalized" -> "c.primary_email_normalized";
            case "primary_phone_e164" -> "c.primary_phone_e164";
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported filter field");
        };
        return builtinSql(column, op, condition.value(), params);
    }

    private static String consentSql(String field, String op, Object value, List<Object> params) {
        String[] parts = field.split("\\.");
        String channel = parts[1];
        String purpose = parts[2];
        params.add(channel);
        params.add(purpose);
        return switch (op) {
            case "eq" -> {
                params.add(String.valueOf(value));
                yield """
                        EXISTS (
                          SELECT 1 FROM contact_consents cc
                          WHERE cc.contact_id = c.id
                            AND cc.tenant_id = c.tenant_id
                            AND cc.workspace_id = c.home_workspace_id
                            AND cc.channel_type = ?
                            AND cc.purpose = ?
                            AND cc.status = ?
                        )
                        """;
            }
            case "neq" -> {
                params.add(String.valueOf(value));
                yield """
                        NOT EXISTS (
                          SELECT 1 FROM contact_consents cc
                          WHERE cc.contact_id = c.id
                            AND cc.tenant_id = c.tenant_id
                            AND cc.workspace_id = c.home_workspace_id
                            AND cc.channel_type = ?
                            AND cc.purpose = ?
                            AND cc.status = ?
                        )
                        """;
            }
            case "is_null" -> """
                    NOT EXISTS (
                      SELECT 1 FROM contact_consents cc
                      WHERE cc.contact_id = c.id
                        AND cc.tenant_id = c.tenant_id
                        AND cc.workspace_id = c.home_workspace_id
                        AND cc.channel_type = ?
                        AND cc.purpose = ?
                    )
                    """;
            case "is_not_null" -> """
                    EXISTS (
                      SELECT 1 FROM contact_consents cc
                      WHERE cc.contact_id = c.id
                        AND cc.tenant_id = c.tenant_id
                        AND cc.workspace_id = c.home_workspace_id
                        AND cc.channel_type = ?
                        AND cc.purpose = ?
                    )
                    """;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported operator: " + op);
        };
    }

    private static String builtinSql(String column, String op, Object value, List<Object> params) {
        return switch (op) {
            case "is_null" -> column + " IS NULL";
            case "is_not_null" -> column + " IS NOT NULL";
            case "eq" -> {
                params.add(value);
                yield column + " = ?";
            }
            case "neq" -> {
                params.add(value);
                yield column + " IS DISTINCT FROM ?";
            }
            case "contains" -> {
                params.add("%" + escapeLike(String.valueOf(value)).toLowerCase(Locale.ROOT) + "%");
                yield "lower(coalesce(" + column + ", '')) LIKE ? ESCAPE '\\'";
            }
            case "starts_with" -> {
                params.add(escapeLike(String.valueOf(value)).toLowerCase(Locale.ROOT) + "%");
                yield "lower(coalesce(" + column + ", '')) LIKE ? ESCAPE '\\'";
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported operator: " + op);
        };
    }

    private static String customSql(String key, String op, Object value, List<Object> params) {
        return switch (op) {
            case "is_null" -> {
                params.add(key);
                yield "NOT (c.custom_fields ? ?)";
            }
            case "is_not_null" -> {
                params.add(key);
                yield "(c.custom_fields ? ?)";
            }
            case "eq" -> {
                params.add(key);
                params.add(String.valueOf(value));
                yield "c.custom_fields ->> ? = ?";
            }
            case "neq" -> {
                params.add(key);
                params.add(String.valueOf(value));
                yield "c.custom_fields ->> ? IS DISTINCT FROM ?";
            }
            case "contains" -> {
                params.add(key);
                params.add("%" + escapeLike(String.valueOf(value)).toLowerCase(Locale.ROOT) + "%");
                yield "lower(coalesce(c.custom_fields ->> ?, '')) LIKE ? ESCAPE '\\'";
            }
            case "starts_with" -> {
                params.add(key);
                params.add(escapeLike(String.valueOf(value)).toLowerCase(Locale.ROOT) + "%");
                yield "lower(coalesce(c.custom_fields ->> ?, '')) LIKE ? ESCAPE '\\'";
            }
            case "gt", "gte", "lt", "lte" -> numericOrDateCompare(key, op, value, params);
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported operator: " + op);
        };
    }

    private static String numericOrDateCompare(String key, String op, Object value, List<Object> params) {
        String sqlOp = switch (op) {
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            case "lte" -> "<=";
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported operator: " + op);
        };
        params.add(key);
        params.add(String.valueOf(value));
        // Compare as text works for ISO dates and zero-padded numbers poorly;
        // prefer numeric when value is BigDecimal-like, else text (ISO dates sort lexicographically).
        if (value instanceof java.math.BigDecimal) {
            return "NULLIF(c.custom_fields ->> ?, '')::numeric " + sqlOp + " ?::numeric";
        }
        return "c.custom_fields ->> ? " + sqlOp + " ?";
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
