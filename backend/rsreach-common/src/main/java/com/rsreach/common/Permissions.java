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

    public static final String CONTACTS_READ = "contacts.read";
    public static final String CONTACTS_MANAGE = "contacts.manage";
    public static final String CONTACTS_CONSENT_MANAGE = "contacts.consent.manage";
    public static final String CONTACTS_IMPORT = "contacts.import";
    public static final String CONTACTS_EXPORT = "contacts.export";

    public static final String INBOX_READ = "inbox.read";
    public static final String INBOX_REPLY = "inbox.reply";
    public static final String INBOX_ASSIGN = "inbox.assign";
    public static final String INTEGRATIONS_MANAGE = "integrations.manage";
    public static final String TEMPLATES_MANAGE = "templates.manage";
    public static final String CAMPAIGNS_READ = "campaigns.read";
    public static final String CAMPAIGNS_MANAGE = "campaigns.manage";
    public static final String CAMPAIGNS_SEND = "campaigns.send";

    public static final String TENANT_BILLING_READ = "tenant.billing.read";
    public static final String TENANT_BILLING_MANAGE = "tenant.billing.manage";
    public static final String BILLING_INVOICES_READ = "billing.invoices.read";
    public static final String AUTOMATIONS_READ = "automations.read";
    public static final String AUTOMATIONS_MANAGE = "automations.manage";
    public static final String PLATFORM_BILLING_MANAGE = "platform.billing.manage";

    private Permissions() {
    }
}
