package com.rsreach.worker.runtime;

import com.rsreach.automation.AutomationEngine;
import com.rsreach.automation.AutomationModels;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.BillingRenewalService;
import com.rsreach.billing.BillingWebhookService;
import com.rsreach.campaigns.CampaignService;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.MessageSendWorker;
import com.rsreach.communications.ScheduledMessageService;
import com.rsreach.contacts.ContactExportService;
import com.rsreach.contacts.ContactGroupMaterializer;
import com.rsreach.contacts.ContactGroupModels;
import com.rsreach.contacts.ContactImportModels;
import com.rsreach.contacts.ContactImportService;
import com.rsreach.contacts.DuplicateCandidateService;
import com.rsreach.contacts.DuplicateModels;
import com.rsreach.email.MailboxSyncService;
import com.rsreach.persistence.outbox.OutboxEvent;
import com.rsreach.whatsapp.WhatsAppWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventDispatcher.class);

    private final ContactGroupMaterializer contactGroupMaterializer;
    private final DuplicateCandidateService duplicateCandidateService;
    private final ContactImportService contactImportService;
    private final ContactExportService contactExportService;
    private final MessageSendWorker messageSendWorker;
    private final CampaignService campaignService;
    private final MailboxSyncService mailboxSyncService;
    private final ScheduledMessageService scheduledMessageService;
    private final WhatsAppWebhookService whatsAppWebhookService;
    private final AutomationEngine automationEngine;
    private final BillingWebhookService billingWebhookService;
    private final BillingRenewalService billingRenewalService;
    private final JdbcTemplate jdbcTemplate;

    public OutboxEventDispatcher(
            ContactGroupMaterializer contactGroupMaterializer,
            DuplicateCandidateService duplicateCandidateService,
            ContactImportService contactImportService,
            ContactExportService contactExportService,
            MessageSendWorker messageSendWorker,
            CampaignService campaignService,
            MailboxSyncService mailboxSyncService,
            ScheduledMessageService scheduledMessageService,
            WhatsAppWebhookService whatsAppWebhookService,
            AutomationEngine automationEngine,
            BillingWebhookService billingWebhookService,
            BillingRenewalService billingRenewalService,
            JdbcTemplate jdbcTemplate
    ) {
        this.contactGroupMaterializer = contactGroupMaterializer;
        this.duplicateCandidateService = duplicateCandidateService;
        this.contactImportService = contactImportService;
        this.contactExportService = contactExportService;
        this.messageSendWorker = messageSendWorker;
        this.campaignService = campaignService;
        this.mailboxSyncService = mailboxSyncService;
        this.scheduledMessageService = scheduledMessageService;
        this.whatsAppWebhookService = whatsAppWebhookService;
        this.automationEngine = automationEngine;
        this.billingWebhookService = billingWebhookService;
        this.billingRenewalService = billingRenewalService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void dispatch(OutboxEvent event) {
        // Billing events may have null workspaceId (tenant-level); handle BEFORE the null check.
        if (BillingModels.EVENT_BILLING_WEBHOOK_APPLY.equals(event.eventType())) {
            billingWebhookService.applyEvent(event.aggregateId());
            return;
        }
        if (BillingModels.EVENT_SUBSCRIPTION_RENEWAL_TICK.equals(event.eventType())) {
            billingRenewalService.processRenewalTick();
            return;
        }

        if (event.tenantId() == null || event.workspaceId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Outbox event missing tenant/workspace");
        }
        if (ContactGroupModels.EVENT_CONTACT_GROUP_REFRESH.equals(event.eventType())) {
            contactGroupMaterializer.materialize(
                    event.tenantId(), event.workspaceId(), event.aggregateId());
            return;
        }
        if (DuplicateModels.EVENT_CONTACT_DUPLICATE_SCAN.equals(event.eventType())) {
            duplicateCandidateService.processScan(event.tenantId(), event.workspaceId());
            return;
        }
        if (ContactImportModels.EVENT_CONTACT_IMPORT_PROCESS.equals(event.eventType())) {
            contactImportService.processJob(event.tenantId(), event.workspaceId(), event.aggregateId());
            return;
        }
        if (ContactImportModels.EVENT_CONTACT_EXPORT_PROCESS.equals(event.eventType())) {
            contactExportService.processExportJob(event.tenantId(), event.workspaceId(), event.aggregateId());
            return;
        }
        if (CommunicationModels.EVENT_MESSAGE_SEND.equals(event.eventType())) {
            messageSendWorker.process(event.tenantId(), event.workspaceId(), event.payloadJson());
            return;
        }
        if (CommunicationModels.EVENT_CAMPAIGN_ENQUEUE.equals(event.eventType())) {
            campaignService.processEnqueue(event.tenantId(), event.workspaceId(), event.aggregateId());
            return;
        }
        if (CommunicationModels.EVENT_MAILBOX_SYNC.equals(event.eventType())) {
            mailboxSyncService.processSync(event.tenantId(), event.workspaceId(), event.payloadJson());
            return;
        }
        if (CommunicationModels.EVENT_SCHEDULED_MESSAGE_FIRE.equals(event.eventType())) {
            scheduledMessageService.fire(event.tenantId(), event.workspaceId(), event.payloadJson());
            return;
        }
        if (CommunicationModels.EVENT_WEBHOOK_APPLY.equals(event.eventType())) {
            applyWebhook(event);
            return;
        }
        if (AutomationModels.EVENT_AUTOMATION_RUN.equals(event.eventType())) {
            automationEngine.processRun(event.tenantId(), event.workspaceId(), event.aggregateId());
            return;
        }
        log.info("outbox_event_ignored type={} id={}", event.eventType(), event.id());
    }

    private void applyWebhook(OutboxEvent event) {
        var rows = jdbcTemplate.query(
                """
                SELECT provider, connected_account_ref, payload_json::text AS payload
                FROM provider_webhook_events
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> new Object[]{
                        rs.getString("provider"),
                        rs.getString("connected_account_ref"),
                        rs.getString("payload")
                },
                event.aggregateId(), event.tenantId(), event.workspaceId()
        );
        if (rows.isEmpty()) {
            log.info("webhook_apply_missing eventId={}", event.aggregateId());
            return;
        }
        Object[] row = rows.get(0);
        String provider = String.valueOf(row[0]);
        if ("whatsapp".equalsIgnoreCase(provider) || "mock".equalsIgnoreCase(provider)) {
            whatsAppWebhookService.applyPayload(
                    event.tenantId(),
                    event.workspaceId(),
                    String.valueOf(row[1]),
                    String.valueOf(row[2])
            );
            return;
        }
        log.info("webhook_apply_noop provider={} eventId={}", provider, event.aggregateId());
    }
}

