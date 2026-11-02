package com.rsreach.common;

public final class Permissions {

    public static final String PLATFORM_TENANTS_READ = "platform.tenants.read";
    public static final String PLATFORM_TENANTS_MANAGE = "platform.tenants.manage";
    public static final String PLATFORM_AUDIT_READ = "platform.audit.read";
    public static final String PLATFORM_GRANTS_MANAGE = "platform.grants.manage";
    public static final String PLATFORM_GRANTS_REQUEST = "platform.grants.request";

    public static final String TENANT_READ = "tenant.read";
    public static final String TENANT_MANAGE = "tenant.manage";
    public static final String TENANT_MEMBERS_MANAGE = "tenant.members.manage";

    public static final String WORKSPACE_READ = "workspace.read";
    public static final String WORKSPACE_MANAGE = "workspace.manage";
    public static final String WORKSPACE_MEMBERS_MANAGE = "workspace.members.manage";
    public static final String WORKSPACE_ORG_MANAGE = "workspace.org.manage";

    private Permissions() {
    }
}
