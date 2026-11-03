-- M4.1 schema safety: merged_into_contact_id must reference a survivor in the
-- same tenant and home workspace. V15 only had REFERENCES contacts(id), which
-- allowed cross-tenant/cross-workspace pointers. V1–V15 files are not edited.

ALTER TABLE contacts
    DROP CONSTRAINT IF EXISTS contacts_merged_into_contact_id_fkey;

ALTER TABLE contacts
    ADD CONSTRAINT fk_contacts_merged_into_home_scope
        FOREIGN KEY (tenant_id, home_workspace_id, merged_into_contact_id)
            REFERENCES contacts (tenant_id, home_workspace_id, id);
