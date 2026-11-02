package com.rsreach.common.error;

import java.time.Instant;
import java.util.List;

/**
 * Standard API error envelope.
 */
public record ApiError(
        ErrorCode code,
        String message,
        List<String> details,
        String traceId,
        Instant timestamp
) {
    public static ApiError of(ErrorCode code, String message, String traceId) {
        return new ApiError(code, message, List.of(), traceId, Instant.now());
    }

    public static ApiError of(ErrorCode code, String message, List<String> details, String traceId) {
        return new ApiError(code, message, details == null ? List.of() : details, traceId, Instant.now());
    }
}
