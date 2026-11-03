-- Phase 2 M4.0: duplicate candidates, merge records, merge marker on contacts,
-- and MERGE_RECONCILED consent event type. V1–V14 unchanged.

-- ---------------------------------------------------------------------------
-- contacts: irreversible merge marker (loser points at survivor)
-- ---------------------------------------------------------------------------

ALTER TABLE contacts
    ADD COLUMN merged_into_contact_id UUID REFERENCES contacts (id),
    ADD COLUMN merged_at TIMESTAMPTZ;

ALTER TABLE contacts
    ADD CONSTRAINT chk_contacts_merged_pair CHECK (
        (merged_into_contact_id IS NULL AND merged_at IS NULL)
        OR (merged_into_contact_id IS NOT NULL AND merged_at IS NOT NULL)
    );

CREATE INDEX idx_contacts_merged_into
    ON contacts (tenant_id, home_workspace_id, merged_into_contact_id)
    WHERE merged_into_contact_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- contact_duplicate_candidates (same-home-workspace pairs only)
-- ---------------------------------------------------------------------------

CREATE TABLE contact_duplicate_candidates (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    workspace_id      UUID NOT NULL,
    contact_id_a      UUID NOT NULL,
    contact_id_b      UUID NOT NULL,
    match_types       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    confidence        VARCHAR(32)  NOT NULL,
    score             INT          NOT NULL,
    merge_allowed     BOOLEAN      NOT NULL DEFAULT TRUE,
    evidence          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status            VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    dismissed_by      UUID REFERENCES customer_users (id),
    dismissed_at      TIMESTAMPTZ,
    dismiss_reason    VARCHAR(255),
    detected_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_detected_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contact_dup_candidate_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_dup_candidate_contact_a
        FOREIGN KEY (tenant_id, workspace_id, contact_id_a)
            REFERENCES contacts (tenant_id, home_workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_dup_candidate_contact_b
        FOREIGN KEY (tenant_id, workspace_id, contact_id_b)
            REFERENCES contacts (tenant_id, home_workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_contact_dup_candidate_pair
        UNIQUE (tenant_id, workspace_id, contact_id_a, contact_id_b),
    CONSTRAINT chk_contact_dup_candidate_canonical
        CHECK (contact_id_a < contact_id_b),
    CONSTRAINT chk_contact_dup_candidate_confidence
        CHECK (confidence IN ('HIGH', 'VERY_HIGH')),
    CONSTRAINT chk_contact_dup_candidate_score
        CHECK (score >= 0 AND score <= 100),
    CONSTRAINT chk_contact_dup_candidate_status
        CHECK (status IN ('OPEN', 'DISMISSED', 'MERGED', 'SUPERSEDED')),
    CONSTRAINT chk_contact_dup_candidate_dismiss
        CHECK (
            (status <> 'DISMISSED' AND dismissed_at IS NULL AND dismiss_reason IS NULL)
            OR (status = 'DISMISSED' AND dismissed_at IS NOT NULL)
        )
);

CREATE INDEX idx_contact_dup_candidates_ws_status_score
    ON contact_duplicate_candidates (tenant_id, workspace_id, status, score DESC);

CREATE INDEX idx_contact_dup_candidates_contact_a
    ON contact_duplicate_candidates (tenant_id, workspace_id, contact_id_a);

CREATE INDEX idx_contact_dup_candidates_contact_b
    ON contact_duplicate_candidates (tenant_id, workspace_id, contact_id_b);

-- ---------------------------------------------------------------------------
-- contact_merges (idempotent completed merge operations)
-- ---------------------------------------------------------------------------

CREATE TABLE contact_merges (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    workspace_id          UUID NOT NULL,
    survivor_contact_id   UUID NOT NULL,
    loser_contact_id      UUID NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    idempotency_key       VARCHAR(128) NOT NULL,
    request_hash          VARCHAR(64)  NOT NULL,
    preview_hash          VARCHAR(64)  NOT NULL,
    actor_id              UUID NOT NULL REFERENCES customer_users (id),
    request_snapshot      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    result_snapshot       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    completed_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contact_merge_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_merge_survivor
        FOREIGN KEY (tenant_id, workspace_id, survivor_contact_id)
            REFERENCES contacts (tenant_id, home_workspace_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_contact_merge_loser
        FOREIGN KEY (tenant_id, workspace_id, loser_contact_id)
            REFERENCES contacts (tenant_id, home_workspace_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_contact_merge_idempotency
        UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT chk_contact_merge_distinct
        CHECK (survivor_contact_id <> loser_contact_id),
    CONSTRAINT chk_contact_merge_status
        CHECK (status IN ('COMPLETED')),
    CONSTRAINT chk_contact_merge_request_hash
        CHECK (char_length(trim(request_hash)) > 0),
    CONSTRAINT chk_contact_merge_preview_hash
        CHECK (char_length(trim(preview_hash)) > 0),
    CONSTRAINT chk_contact_merge_idempotency_key
        CHECK (char_length(trim(idempotency_key)) > 0)
);

-- A contact may be the loser of at most one completed merge.
CREATE UNIQUE INDEX uq_contact_merges_loser_completed
    ON contact_merges (loser_contact_id)
    WHERE status = 'COMPLETED';

CREATE INDEX idx_contact_merges_ws_completed
    ON contact_merges (tenant_id, workspace_id, completed_at DESC);

CREATE INDEX idx_contact_merges_survivor
    ON contact_merges (tenant_id, workspace_id, survivor_contact_id, completed_at DESC);

-- ---------------------------------------------------------------------------
-- contact_merge_items (explainability; ids/enums only — no address PII)
-- ---------------------------------------------------------------------------

CREATE TABLE contact_merge_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merge_id     UUID NOT NULL REFERENCES contact_merges (id) ON DELETE CASCADE,
    item_type    VARCHAR(64)  NOT NULL,
    item_key     VARCHAR(128) NOT NULL,
    action       VARCHAR(64)  NOT NULL,
    before_json  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    after_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT chk_contact_merge_item_type
        CHECK (item_type IN (
            'FIELD', 'CHANNEL', 'CONSENT', 'GROUP', 'SHARE', 'SUPPRESSION_WARNING'
        )),
    CONSTRAINT chk_contact_merge_item_action
        CHECK (action IN (
            'KEPT_SURVIVOR', 'TAKEN_LOSER', 'UNION', 'ARCHIVED_DUP',
            'RECONCILED', 'REVOKED', 'WARNED', 'MOVED', 'DEDUPLICATED'
        )),
    CONSTRAINT chk_contact_merge_item_key
        CHECK (char_length(trim(item_key)) > 0)
);

CREATE INDEX idx_contact_merge_items_merge
    ON contact_merge_items (merge_id);

-- ---------------------------------------------------------------------------
-- Consent events: allow MERGE_RECONCILED (append-only triggers unchanged)
-- ---------------------------------------------------------------------------

ALTER TABLE contact_consent_events
    DROP CONSTRAINT chk_contact_consent_event_type;

ALTER TABLE contact_consent_events
    ADD CONSTRAINT chk_contact_consent_event_type
        CHECK (event_type IN (
            'GRANTED', 'WITHDRAWN', 'RESET_UNKNOWN', 'SEEDED_UNKNOWN', 'MERGE_RECONCILED'
        ));
