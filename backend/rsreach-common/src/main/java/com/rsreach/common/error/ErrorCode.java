package com.rsreach.common.error;

/**
 * Stable machine-readable error codes for API responses.
 */
public enum ErrorCode {
    INTERNAL_ERROR,
    VALIDATION_FAILED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    TENANT_ISOLATION_VIOLATION,
    WORKSPACE_ISOLATION_VIOLATION,
    RATE_LIMITED,
    PAYLOAD_TOO_LARGE
}
