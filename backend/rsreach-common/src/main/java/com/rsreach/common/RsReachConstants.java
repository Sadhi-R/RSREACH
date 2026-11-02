package com.rsreach.common;

/**
 * Shared platform constants for Phase 0+.
 */
public final class RsReachConstants {

    public static final String API_V1 = "/api/v1";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_WORKSPACE_ID = "X-Workspace-Id";
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    public static final String REALM_CUSTOMER = "customer";
    public static final String REALM_STAFF = "staff";

    private RsReachConstants() {
    }
}
