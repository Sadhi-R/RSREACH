package com.rsreach.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Allow-listed dynamic group filter DSL (version 1). Never accepts SQL or expressions.
 * Consent filters use allow-listed {@code consent.CHANNEL.PURPOSE} fields (read-only; never mutate consent).
 */
public final class GroupFilterValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> MATCH = Set.of("ALL", "ANY");
    private static final Set<String> BUILTIN_FIELDS = Set.of(
            "display_name",
            "status",
            "primary_email_normalized",
            "primary_phone_e164"
    );
    private static final Set<String> NULL_OPS = Set.of("is_null", "is_not_null");
    private static final Set<String> STRING_OPS = Set.of("eq", "neq", "contains", "starts_with");
    private static final Set<String> STATUS_OPS = Set.of("eq", "neq");
    private static final Set<String> NUMBER_OPS = Set.of("eq", "neq", "gt", "gte", "lt", "lte");
    private static final Set<String> BOOL_OPS = Set.of("eq", "neq");
    private static final Set<String> DATE_OPS = Set.of("eq", "neq", "gt", "gte", "lt", "lte");

    private GroupFilterValidator() {
    }

    public static GroupFilterModels.FilterDefinition parseAndValidate(
            Object raw,
            List<ContactModels.CustomFieldDef> defs
    ) {
        if (raw == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "filterDefinition is required for DYNAMIC groups");
        }
        JsonNode root;
        try {
            if (raw instanceof String s) {
                root = MAPPER.readTree(s);
            } else {
                root = MAPPER.valueToTree(raw);
            }
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid filterDefinition JSON");
        }
        if (!root.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "filterDefinition must be an object");
        }
        int version = root.path("version").asInt(-1);
        if (version != 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported filterDefinition version");
        }
        String match = root.path("match").asText("").trim().toUpperCase(Locale.ROOT);
        if (!MATCH.contains(match)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "match must be ALL or ANY");
        }
        JsonNode conditionsNode = root.get("conditions");
        if (conditionsNode == null || !conditionsNode.isArray() || conditionsNode.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "conditions must be a non-empty array");
        }
        if (conditionsNode.size() > 25) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Too many filter conditions");
        }

        // Caller supplies the allowed defs: active-only for create/update, all known for stored eval.
        Map<String, ContactModels.CustomFieldDef> knownDefs = new java.util.LinkedHashMap<>();
        for (ContactModels.CustomFieldDef def : defs) {
            knownDefs.put(def.fieldKey(), def);
        }

        List<GroupFilterModels.Condition> conditions = new ArrayList<>();
        for (JsonNode node : conditionsNode) {
            conditions.add(validateCondition(node, knownDefs));
        }
        return new GroupFilterModels.FilterDefinition(version, match, List.copyOf(conditions));
    }

    public static String toJson(GroupFilterModels.FilterDefinition definition) {
        try {
            return MAPPER.writeValueAsString(definition);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid filterDefinition");
        }
    }

    public static GroupFilterModels.FilterDefinition fromJson(
            String json,
            List<ContactModels.CustomFieldDef> defs
    ) {
        return parseAndValidate(json, defs);
    }

    private static GroupFilterModels.Condition validateCondition(
            JsonNode node,
            Map<String, ContactModels.CustomFieldDef> knownDefs
    ) {
        if (node == null || !node.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each condition must be an object");
        }
        String field = node.path("field").asText("").trim();
        String op = node.path("op").asText("").trim().toLowerCase(Locale.ROOT);
        if (field.isBlank() || op.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "condition field and op are required");
        }
        if (NULL_OPS.contains(op)) {
            if (node.hasNonNull("value")) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, op + " must not include a value");
            }
            assertKnownField(field, knownDefs);
            return new GroupFilterModels.Condition(field, op, null);
        }
        if (!node.has("value") || node.get("value").isNull()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "condition value is required for op " + op);
        }
        Object value = coerceValue(field, op, node.get("value"), knownDefs);
        return new GroupFilterModels.Condition(field, op, value);
    }

    private static void assertKnownField(String field, Map<String, ContactModels.CustomFieldDef> knownDefs) {
        if (BUILTIN_FIELDS.contains(field)) {
            return;
        }
        if (field.startsWith("custom.")) {
            String key = field.substring("custom.".length());
            if (!knownDefs.containsKey(key)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown or inactive custom field: " + key);
            }
            return;
        }
        if (isConsentField(field)) {
            return;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported filter field: " + field);
    }

    private static boolean isConsentField(String field) {
        if (!field.startsWith("consent.")) {
            return false;
        }
        String[] parts = field.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        return ConsentModels.CHANNELS.contains(parts[1]) && ConsentModels.PURPOSES.contains(parts[2]);
    }

    private static Object coerceValue(
            String field,
            String op,
            JsonNode valueNode,
            Map<String, ContactModels.CustomFieldDef> knownDefs
    ) {
        if (isConsentField(field)) {
            if (!STATUS_OPS.contains(op)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported op for consent field");
            }
            String s = valueNode.asText("").trim().toUpperCase(Locale.ROOT);
            if (!ConsentModels.STATUSES.contains(s)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid consent status value");
            }
            return s;
        }
        if (BUILTIN_FIELDS.contains(field)) {
            return switch (field) {
                case "display_name", "primary_email_normalized", "primary_phone_e164" -> {
                    if (!STRING_OPS.contains(op)) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported op for " + field);
                    }
                    String s = valueNode.asText("").trim();
                    if (s.isEmpty() || s.length() > 255) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid value for " + field);
                    }
                    yield field.equals("primary_email_normalized") ? s.toLowerCase(Locale.ROOT) : s;
                }
                case "status" -> {
                    if (!STATUS_OPS.contains(op)) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported op for status");
                    }
                    String s = valueNode.asText("").trim().toUpperCase(Locale.ROOT);
                    if (!s.equals("ACTIVE") && !s.equals("ARCHIVED")) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "status value must be ACTIVE or ARCHIVED");
                    }
                    yield s;
                }
                default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported filter field: " + field);
            };
        }
        if (field.startsWith("custom.")) {
            String key = field.substring("custom.".length());
            ContactModels.CustomFieldDef def = knownDefs.get(key);
            if (def == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown or inactive custom field: " + key);
            }
            return coerceCustom(def, op, valueNode);
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported filter field: " + field);
    }

    private static Object coerceCustom(ContactModels.CustomFieldDef def, String op, JsonNode valueNode) {
        String type = def.dataType().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "STRING" -> {
                if (!STRING_OPS.contains(op)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported op for STRING custom field");
                }
                String s = valueNode.asText("").trim();
                if (s.isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field value cannot be blank");
                }
                yield s;
            }
            case "NUMBER" -> {
                if (!NUMBER_OPS.contains(op)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported op for NUMBER custom field");
                }
                try {
                    yield new BigDecimal(valueNode.isNumber() ? valueNode.asText() : valueNode.asText().trim());
                } catch (Exception ex) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field value must be a number");
                }
            }
            case "BOOLEAN" -> {
                if (!BOOL_OPS.contains(op)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported op for BOOLEAN custom field");
                }
                if (valueNode.isBoolean()) {
                    yield valueNode.asBoolean();
                }
                String s = valueNode.asText("").trim().toLowerCase(Locale.ROOT);
                if ("true".equals(s) || "false".equals(s)) {
                    yield Boolean.parseBoolean(s);
                }
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field value must be boolean");
            }
            case "DATE" -> {
                if (!DATE_OPS.contains(op)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported op for DATE custom field");
                }
                try {
                    yield LocalDate.parse(valueNode.asText("").trim()).toString();
                } catch (Exception ex) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field value must be ISO date");
                }
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported custom field type");
        };
    }

    public static Set<String> supportedBuiltinFields() {
        return new LinkedHashSet<>(BUILTIN_FIELDS);
    }
}
