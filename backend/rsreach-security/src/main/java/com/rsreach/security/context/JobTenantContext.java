package com.rsreach.security.context;

import java.util.UUID;

/**
 * Foundation for restoring tenant/workspace context in background workers.
 */
public final class JobTenantContext {

    private JobTenantContext() {
    }

    public static void runWithContext(UUID tenantId, UUID workspaceId, Runnable action) {
        try {
            TenantContext.set(tenantId, workspaceId);
            action.run();
        } finally {
            TenantContext.clear();
        }
    }
}
