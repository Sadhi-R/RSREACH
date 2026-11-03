package com.rsreach.contacts;

import com.rsreach.common.Permissions;
import java.util.List;
import java.util.Set;

/**
 * Contact RBAC codes and role-mapping expectations for Phase 2.
 * Seeded by Flyway {@code V10__contact_rbac_permissions.sql}.
 */
public final class ContactPermissionCodes {

    public static final Set<String> ALL = Set.of(
            Permissions.CONTACTS_READ,
            Permissions.CONTACTS_MANAGE,
            Permissions.CONTACTS_CONSENT_MANAGE,
            Permissions.CONTACTS_IMPORT,
            Permissions.CONTACTS_EXPORT
    );

    public static final Set<String> ADMIN_ROLE_CODES = Set.of(
            "TENANT_OWNER",
            "TENANT_ADMIN",
            "WORKSPACE_ADMIN"
    );

    public static final String MEMBER_ROLE_CODE = "WORKSPACE_MEMBER";

    public static final List<String> ADMIN_PERMISSIONS = List.copyOf(ALL);

    public static final List<String> MEMBER_PERMISSIONS = List.of(Permissions.CONTACTS_READ);

    private ContactPermissionCodes() {
    }
}
