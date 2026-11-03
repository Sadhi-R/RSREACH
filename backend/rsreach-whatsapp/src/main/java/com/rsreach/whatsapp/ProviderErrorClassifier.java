package com.rsreach.whatsapp;

import java.util.Locale;
import java.util.Set;

/**
 * Maps Meta-style error codes to retryable vs terminal failures.
 */
public final class ProviderErrorClassifier {

    private static final Set<String> RETRYABLE = Set.of(
            "throttled", "rate_limit", "rate_limit_hit", "temporarily_unavailable", "503", "429"
    );

    private ProviderErrorClassifier() {
    }

    public static boolean isRetryable(String errorCode, String providerStatus) {
        String code = normalize(errorCode);
        String status = normalize(providerStatus);
        if (RETRYABLE.contains(code) || RETRYABLE.contains(status)) {
            return true;
        }
        return code.contains("rate") || code.contains("throttle") || status.contains("429") || status.contains("503");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
