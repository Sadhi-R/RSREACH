-- Phase 2 M3 verification: align uniqueness with home-workspace scope and enforce append-only events.

ALTER TABLE contact_consents
    DROP CONSTRAINT uq_contact_consent;

ALTER TABLE contact_consents
    ADD CONSTRAINT uq_contact_consent
        UNIQUE (tenant_id, workspace_id, contact_id, channel_type, purpose);

CREATE OR REPLACE FUNCTION prevent_contact_consent_events_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'contact_consent_events is append-only';
END;
$$;

DROP TRIGGER IF EXISTS trg_contact_consent_events_no_update ON contact_consent_events;
CREATE TRIGGER trg_contact_consent_events_no_update
    BEFORE UPDATE ON contact_consent_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_contact_consent_events_mutation();

DROP TRIGGER IF EXISTS trg_contact_consent_events_no_delete ON contact_consent_events;
CREATE TRIGGER trg_contact_consent_events_no_delete
    BEFORE DELETE ON contact_consent_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_contact_consent_events_mutation();
