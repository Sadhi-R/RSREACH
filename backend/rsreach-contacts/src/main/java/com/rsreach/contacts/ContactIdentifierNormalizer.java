package com.rsreach.contacts;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ContactIdentifierNormalizer {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private ContactIdentifierNormalizer() {
    }

    public static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Email is required");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(normalized).matches() || normalized.length() > 320) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid email address");
        }
        return normalized;
    }

    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Phone is required");
        }
        String digits = raw.trim().replaceAll("[\\s()-]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        if (!digits.startsWith("+")) {
            // Require explicit country code for MVP to avoid ambiguous local formats.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Phone must be E.164 with country code (e.g. +9198...)");
        }
        if (!E164.matcher(digits).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid phone number");
        }
        return digits;
    }

    public static String normalize(String channelType, String raw) {
        return switch (channelType) {
            case "EMAIL" -> normalizeEmail(raw);
            case "PHONE" -> normalizePhone(raw);
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported channel type");
        };
    }
}
