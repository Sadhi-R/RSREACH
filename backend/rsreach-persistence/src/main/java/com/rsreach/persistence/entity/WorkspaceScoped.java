package com.rsreach.persistence.entity;

import java.util.UUID;

/**
 * Marker for workspace-owned records. Repositories must filter by tenantId and workspaceId.
 */
public interface WorkspaceScoped extends TenantScoped {

    UUID getWorkspaceId();
}
