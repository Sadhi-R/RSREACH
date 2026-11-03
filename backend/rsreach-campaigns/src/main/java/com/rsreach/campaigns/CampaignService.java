package com.rsreach.campaigns;

import com.rsreach.audit.AuditService;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.common.event.WorkspaceDomainEvent;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConversationService;
import com.rsreach.contacts.ConsentEvaluationService;
import com.rsreach.contacts.ConsentModels;
import com.rsreach.contacts.ContactIdentifierNormalizer;
import com.rsreach.contacts.ContactModels;
import com.rsreach.contacts.ContactRepository;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import org.springframework.context.ApplicationEventPublisher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignService {

    private final JdbcTemplate jdbcTemplate;
    private final ContactRepository contactRepository;
    private final ConsentEvaluationService consentEvaluationService;
    private final ConversationService conversationService;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;
    private final EntitlementService entitlementService;
    private final ApplicationEventPublisher eventPublisher;

    public CampaignService(
            JdbcTemplate jdbcTemplate,
            ContactRepository contactRepository,
            ConsentEvaluationService consentEvaluationService,
            ConversationService conversationService,
            OutboxRepository outboxRepository,
            AuditService auditService,
            EntitlementService entitlementService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.contactRepository = contactRepository;
        this.consentEvaluationService = consentEvaluationService;
        this.conversationService = conversationService;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.entitlementService = entitlementService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public CampaignModels.CampaignPage list(UUID tenantId, UUID workspaceId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        List<CampaignModels.CampaignView> items = jdbcTemplate.query(
                """
                SELECT id, name, channel, purpose, status, audience_group_id, scheduled_at,
                       started_at, completed_at, created_at
                FROM campaigns
                WHERE tenant_id = ? AND workspace_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapCampaign(rs),
                tenantId, workspaceId, limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new CampaignModels.CampaignPage(items, page, limit, hasNext);
    }

    @Transactional(readOnly = true)
    public CampaignModels.CampaignView get(UUID tenantId, UUID workspaceId, UUID id) {
        List<CampaignModels.CampaignView> rows = jdbcTemplate.query(
                """
                SELECT id, name, channel, purpose, status, audience_group_id, scheduled_at,
                       started_at, completed_at, created_at
                FROM campaigns
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> mapCampaign(rs),
                id, tenantId, workspaceId
        );
        return rows.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Campaign not found"));
    }

    @Transactional
    public CampaignModels.CampaignView create(
            UUID tenantId, UUID workspaceId, UUID actorId, CampaignModels.CreateCampaignCommand command
    ) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name is required");
        }
        String channel = requireChannel(command.channel());
        String purpose = command.purpose() == null || command.purpose().isBlank()
                ? "MARKETING"
                : command.purpose().trim().toUpperCase(Locale.ROOT);
        UUID id = UUID.randomUUID();
        String status = command.scheduledAt() != null ? "SCHEDULED" : "DRAFT";
        jdbcTemplate.update(
                """
                INSERT INTO campaigns (
                    id, tenant_id, workspace_id, name, channel, purpose, status, audience_group_id,
                    body_text, scheduled_at, timezone, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, command.name().trim(), channel, purpose, status,
                command.audienceGroupId(), command.bodyText(),
                command.scheduledAt() == null ? null : java.sql.Timestamp.from(command.scheduledAt()),
                command.timezone() == null ? "UTC" : command.timezone(),
                actorId
        );
        return get(tenantId, workspaceId, id);
    }

    @Transactional
    public CampaignModels.CampaignView start(UUID tenantId, UUID workspaceId, UUID actorId, UUID campaignId) {
        CampaignModels.CampaignView campaign = get(tenantId, workspaceId, campaignId);
        if (!"DRAFT".equals(campaign.status()) && !"SCHEDULED".equals(campaign.status())
                && !"PAUSED".equals(campaign.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Campaign cannot be started from status " + campaign.status());
        }
        // Entitlement: consume 1 CAMPAIGNS unit (idempotent key ensures resume/restart doesn't double-count)
        entitlementService.consume(
                tenantId, workspaceId,
                BillingModels.METER_CAMPAIGNS, 1L,
                "CAMPAIGN_START:" + campaignId,
                "campaign", campaignId.toString()
        );
        snapshotRecipients(tenantId, workspaceId, campaign);
        jdbcTemplate.update(
                """
                UPDATE campaigns SET status = 'RUNNING', started_at = COALESCE(started_at, NOW()),
                    paused_at = NULL, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                campaignId, tenantId, workspaceId
        );
        outboxRepository.append(OutboxAppendCommand.of(
                "campaign",
                campaignId,
                CommunicationModels.EVENT_CAMPAIGN_ENQUEUE,
                "{\"campaignId\":\"" + campaignId + "\"}",
                tenantId,
                workspaceId,
                null,
                "CAMPAIGN_ENQUEUE:" + campaignId + ":" + UUID.randomUUID()
        ));
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "campaign.started", "campaign", campaignId.toString(),
                "{\"channel\":\"" + campaign.channel() + "\"}"
        );
        return get(tenantId, workspaceId, campaignId);
    }

    @Transactional
    public CampaignModels.CampaignView pause(UUID tenantId, UUID workspaceId, UUID actorId, UUID campaignId) {
        get(tenantId, workspaceId, campaignId);
        jdbcTemplate.update(
                """
                UPDATE campaigns SET status = 'PAUSED', paused_at = NOW(), updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'RUNNING'
                """,
                campaignId, tenantId, workspaceId
        );
        return get(tenantId, workspaceId, campaignId);
    }

    @Transactional
    public CampaignModels.CampaignView cancel(UUID tenantId, UUID workspaceId, UUID actorId, UUID campaignId) {
        get(tenantId, workspaceId, campaignId);
        jdbcTemplate.update(
                """
                UPDATE campaigns SET status = 'CANCELLED', cancelled_at = NOW(), updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                  AND status IN ('DRAFT', 'SCHEDULED', 'RUNNING', 'PAUSED')
                """,
                campaignId, tenantId, workspaceId
        );
        return get(tenantId, workspaceId, campaignId);
    }

    @Transactional
    public void processEnqueue(UUID tenantId, UUID workspaceId, UUID campaignId) {
        CampaignModels.CampaignView campaign = get(tenantId, workspaceId, campaignId);
        if (!"RUNNING".equals(campaign.status())) {
            return;
        }
        String body = jdbcTemplate.query(
                "SELECT body_text FROM campaigns WHERE id = ? AND tenant_id = ? AND workspace_id = ?",
                (rs, i) -> rs.getString("body_text"),
                campaignId, tenantId, workspaceId
        ).stream().findFirst().orElse("");
        List<RecipientRow> pending = jdbcTemplate.query(
                """
                SELECT id, contact_id, address, address_normalized
                FROM campaign_recipients
                WHERE campaign_id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'PENDING'
                LIMIT 100
                """,
                (rs, i) -> new RecipientRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("contact_id"),
                        rs.getString("address"),
                        rs.getString("address_normalized")
                ),
                campaignId, tenantId, workspaceId
        );
        String consentChannel = "EMAIL".equals(campaign.channel()) ? "EMAIL" : "WHATSAPP";
        for (RecipientRow row : pending) {
            ConsentModels.EligibilityResult eligibility = consentEvaluationService.evaluate(
                    tenantId, workspaceId, row.contactId(), consentChannel, campaign.purpose(), row.address());
            if (!eligibility.eligible()) {
                jdbcTemplate.update(
                        """
                        UPDATE campaign_recipients
                        SET status = 'SKIPPED', skip_reason = ?, updated_at = NOW()
                        WHERE id = ?
                        """,
                        eligibility.reasonCode(), row.id()
                );
                continue;
            }
            CommunicationModels.ConversationView conversation = conversationService.create(
                    tenantId, workspaceId, null,
                    new CommunicationModels.CreateConversationCommand(
                            campaign.channel(), row.contactId(), null,
                            "Campaign " + campaign.name(),
                            "campaign:" + campaignId + ":" + row.contactId()
                    )
            );
            CommunicationModels.MessageView message = conversationService.composeOutbound(
                    tenantId, workspaceId, null, conversation.id(),
                    new CommunicationModels.ComposeMessageCommand(
                            body == null ? "" : body,
                            campaign.purpose(),
                            eligibility.evaluatedAddress(),
                            "campaign-msg:" + campaignId + ":" + row.id(),
                            null,
                            null
                    )
            );
            jdbcTemplate.update(
                    """
                    UPDATE campaign_recipients
                    SET status = 'QUEUED', message_id = ?, updated_at = NOW()
                    WHERE id = ?
                    """,
                    message.id(), row.id()
            );
        }
        Integer remaining = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM campaign_recipients
                WHERE campaign_id = ? AND status = 'PENDING'
                """,
                Integer.class, campaignId
        );
        if (remaining != null && remaining == 0) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE campaigns SET status = 'COMPLETED', completed_at = NOW(), updated_at = NOW()
                    WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'RUNNING'
                    """,
                    campaignId, tenantId, workspaceId
            );
            if (updated > 0) {
                eventPublisher.publishEvent(new WorkspaceDomainEvent(
                        "CAMPAIGN_COMPLETED", tenantId, workspaceId, campaignId,
                        "{\"campaignId\":\"" + campaignId + "\"}"));
            }
        } else if (remaining != null && remaining > 0) {
            outboxRepository.append(OutboxAppendCommand.of(
                    "campaign",
                    campaignId,
                    CommunicationModels.EVENT_CAMPAIGN_ENQUEUE,
                    "{\"campaignId\":\"" + campaignId + "\"}",
                    tenantId,
                    workspaceId,
                    null,
                    "CAMPAIGN_ENQUEUE:" + campaignId + ":" + UUID.randomUUID()
            ));
        }
    }

    @Transactional(readOnly = true)
    public CampaignModels.RecipientStats stats(UUID tenantId, UUID workspaceId, UUID campaignId) {
        get(tenantId, workspaceId, campaignId);
        return jdbcTemplate.query(
                """
                SELECT
                  COUNT(*) AS total,
                  COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                  COUNT(*) FILTER (WHERE status = 'QUEUED') AS queued,
                  COUNT(*) FILTER (WHERE status = 'SENT') AS sent,
                  COUNT(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
                  COUNT(*) FILTER (WHERE status = 'FAILED') AS failed
                FROM campaign_recipients
                WHERE campaign_id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> new CampaignModels.RecipientStats(
                        rs.getInt("total"), rs.getInt("pending"), rs.getInt("queued"),
                        rs.getInt("sent"), rs.getInt("skipped"), rs.getInt("failed")
                ),
                campaignId, tenantId, workspaceId
        ).get(0);
    }

    private void snapshotRecipients(UUID tenantId, UUID workspaceId, CampaignModels.CampaignView campaign) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id = ?",
                Integer.class, campaign.id()
        );
        if (existing != null && existing > 0) {
            return;
        }
        List<UUID> contactIds;
        if (campaign.audienceGroupId() != null) {
            contactIds = jdbcTemplate.query(
                    """
                    SELECT contact_id FROM contact_group_members
                    WHERE tenant_id = ? AND workspace_id = ? AND group_id = ?
                    """,
                    (rs, i) -> (UUID) rs.getObject("contact_id"),
                    tenantId, workspaceId, campaign.audienceGroupId()
            );
        } else {
            contactIds = contactRepository.search(tenantId, workspaceId, "", "ACTIVE", 0, 100).items()
                    .stream().map(ContactModels.ContactView::id).toList();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (UUID contactId : contactIds) {
            ContactModels.ContactView contact = contactRepository.findById(tenantId, workspaceId, contactId)
                    .orElse(null);
            if (contact == null || !"ACTIVE".equals(contact.status())) {
                continue;
            }
            String address;
            String normalized;
            try {
                if ("EMAIL".equals(campaign.channel())) {
                    address = contact.primaryEmail();
                    if (address == null || address.isBlank()) {
                        continue;
                    }
                    normalized = ContactIdentifierNormalizer.normalizeEmail(address);
                } else {
                    address = contact.primaryPhone();
                    if (address == null || address.isBlank()) {
                        continue;
                    }
                    normalized = ContactIdentifierNormalizer.normalizePhone(address);
                }
            } catch (ApiException ex) {
                continue;
            }
            if (!seen.add(normalized)) {
                continue;
            }
            jdbcTemplate.update(
                    """
                    INSERT INTO campaign_recipients (
                        id, tenant_id, workspace_id, campaign_id, contact_id, address,
                        address_normalized, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', NOW(), NOW())
                    """,
                    UUID.randomUUID(), tenantId, workspaceId, campaign.id(), contact.id(),
                    address, normalized
            );
        }
    }

    private static String requireChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "channel is required");
        }
        String normalized = channel.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("EMAIL") && !normalized.equals("WHATSAPP")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "channel must be EMAIL or WHATSAPP");
        }
        return normalized;
    }

    private static CampaignModels.CampaignView mapCampaign(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp scheduled = rs.getTimestamp("scheduled_at");
        java.sql.Timestamp started = rs.getTimestamp("started_at");
        java.sql.Timestamp completed = rs.getTimestamp("completed_at");
        return new CampaignModels.CampaignView(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                rs.getString("channel"),
                rs.getString("purpose"),
                rs.getString("status"),
                (UUID) rs.getObject("audience_group_id"),
                scheduled == null ? null : scheduled.toInstant(),
                started == null ? null : started.toInstant(),
                completed == null ? null : completed.toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private record RecipientRow(UUID id, UUID contactId, String address, String addressNormalized) {
    }
}
