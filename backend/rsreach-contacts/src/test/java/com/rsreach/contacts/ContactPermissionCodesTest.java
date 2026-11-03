package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import com.rsreach.common.Permissions;
import org.junit.jupiter.api.Test;

class ContactPermissionCodesTest {

    @Test
    void definesExactlyFiveContactPermissions() {
        assertThat(ContactPermissionCodes.ALL).containsExactlyInAnyOrder(
                Permissions.CONTACTS_READ,
                Permissions.CONTACTS_MANAGE,
                Permissions.CONTACTS_CONSENT_MANAGE,
                Permissions.CONTACTS_IMPORT,
                Permissions.CONTACTS_EXPORT
        );
    }

    @Test
    void membersAreReadOnly() {
        assertThat(ContactPermissionCodes.MEMBER_PERMISSIONS)
                .containsExactly(Permissions.CONTACTS_READ);
        assertThat(ContactPermissionCodes.MEMBER_PERMISSIONS)
                .doesNotContain(
                        Permissions.CONTACTS_MANAGE,
                        Permissions.CONTACTS_CONSENT_MANAGE,
                        Permissions.CONTACTS_IMPORT,
                        Permissions.CONTACTS_EXPORT
                );
    }

    @Test
    void adminsReceiveAllContactPermissions() {
        assertThat(ContactPermissionCodes.ADMIN_PERMISSIONS)
                .containsExactlyInAnyOrderElementsOf(ContactPermissionCodes.ALL);
        assertThat(ContactPermissionCodes.ADMIN_ROLE_CODES)
                .containsExactlyInAnyOrder("TENANT_OWNER", "TENANT_ADMIN", "WORKSPACE_ADMIN");
    }
}
