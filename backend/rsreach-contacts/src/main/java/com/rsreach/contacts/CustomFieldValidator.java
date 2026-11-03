package com.rsreach.contacts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CustomFieldValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CustomFieldValidator() {
    }

    public static Map<String, Object> validateAndNormalize(
            Map<String, Object> input,
            List<ContactModels.CustomFieldDef> defs
    ) {
        Map<String, Object> source = input == null ? Map.of() : input;
        Map<String, ContactModels.CustomFieldDef> byKey = new LinkedHashMap<>();
        for (ContactModels.CustomFieldDef def : defs) {
            if (def.active()) {
                byKey.put(def.fieldKey(), def);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            ContactModels.CustomFieldDef def = byKey.get(entry.getKey());
            if (def == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown custom field: " + entry.getKey());
            }
            result.put(entry.getKey(), coerce(def, entry.getValue()));
        }
        for (ContactModels.CustomFieldDef def : byKey.values()) {
            if (def.required() && !result.containsKey(def.fieldKey())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Missing required custom field: " + def.fieldKey());
            }
        }
        return result;
    }

    public static String toJson(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields == null ? Map.of() : fields);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid custom fields");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static Object coerce(ContactModels.CustomFieldDef def, Object value) {
        if (value == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field cannot be null: " + def.fieldKey());
        }
        return switch (def.dataType().toUpperCase(Locale.ROOT)) {
            case "STRING" -> {
                String s = String.valueOf(value).trim();
                if (s.isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field cannot be blank: " + def.fieldKey());
                }
                yield s;
            }
            case "NUMBER" -> {
                try {
                    yield new BigDecimal(String.valueOf(value).trim());
                } catch (NumberFormatException ex) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field must be a number: " + def.fieldKey());
                }
            }
            case "BOOLEAN" -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                if ("true".equals(s) || "false".equals(s)) {
                    yield Boolean.parseBoolean(s);
                }
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field must be boolean: " + def.fieldKey());
            }
            case "DATE" -> {
                try {
                    yield LocalDate.parse(String.valueOf(value).trim()).toString();
                } catch (DateTimeParseException ex) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Custom field must be ISO date: " + def.fieldKey());
                }
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported custom field type");
        };
    }
}
