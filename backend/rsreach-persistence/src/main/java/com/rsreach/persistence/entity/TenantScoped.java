package com.rsreach.persistence.entity;

import java.util.UUID;

/**
 * Marker for tenant-owned records. Repositories must always filter by tenantId.
 */
public interface TenantScoped {

    UUID getTenantId();
}
