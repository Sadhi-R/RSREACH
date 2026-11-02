package com.rsreach.security.context;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<TenantWorkspace> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId, UUID workspaceId) {
        HOLDER.set(new TenantWorkspace(tenantId, workspaceId));
    }

    public static TenantWorkspace get() {
        return HOLDER.get();
    }

    public static UUID requireTenantId() {
        TenantWorkspace ctx = HOLDER.get();
        if (ctx == null || ctx.tenantId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Tenant context required");
        }
        return ctx.tenantId();
    }

    public static UUID requireWorkspaceId() {
        TenantWorkspace ctx = HOLDER.get();
        if (ctx == null || ctx.workspaceId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Workspace context required");
        }
        return ctx.workspaceId();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record TenantWorkspace(UUID tenantId, UUID workspaceId) {
    }
}
