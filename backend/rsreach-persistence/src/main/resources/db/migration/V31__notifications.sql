-- V31: in-app notification store
CREATE TABLE notifications (
  id           UUID PRIMARY KEY,
  tenant_id    UUID NOT NULL REFERENCES tenants(id),
  workspace_id UUID,
  user_id      UUID,
  title        VARCHAR(255) NOT NULL,
  body         TEXT,
  link_path    VARCHAR(512),
  read_at      TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notifications_user ON notifications (tenant_id, user_id, created_at DESC);
