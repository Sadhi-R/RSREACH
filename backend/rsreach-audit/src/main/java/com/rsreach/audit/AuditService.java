package com.rsreach.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void record(
            String actorRealm,
            UUID actorId,
            UUID tenantId,
            UUID workspaceId,
            String action,
            String resourceType,
            String resourceId,
            String detailsJson
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_events (
                    id, occurred_at, actor_realm, actor_id, tenant_id, workspace_id,
                    action, resource_type, resource_id, details_json
                ) VALUES (?, NOW(), ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                UUID.randomUUID(),
                actorRealm,
                actorId,
                tenantId,
                workspaceId,
                action,
                resourceType,
                resourceId,
                detailsJson == null ? "{}" : detailsJson
        );
    }
}
