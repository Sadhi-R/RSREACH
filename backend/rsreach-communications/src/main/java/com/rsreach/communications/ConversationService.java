package com.rsreach.communications;

import com.rsreach.audit.AuditService;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.common.event.WorkspaceDomainEvent;
import com.rsreach.contacts.ConsentEvaluationService;
import com.rsreach.contacts.ConsentModels;
import com.rsreach.contacts.ContactRepository;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    private static final Set<String> CHANNELS = Set.of("WHATSAPP", "EMAIL", "INTERNAL_TEST");

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConnectedAccountRepository accountRepository;
    private final ContactRepository contactRepository;
    private final ConsentEvaluationService consentEvaluationService;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;
    private final TemplateService templateService;
    private final EntitlementService entitlementService;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            ConnectedAccountRepository accountRepository,
            ContactRepository contactRepository,
            ConsentEvaluationService consentEvaluationService,
            OutboxRepository outboxRepository,
            AuditService auditService,
            TemplateService templateService,
            EntitlementService entitlementService,
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.accountRepository = accountRepository;
        this.contactRepository = contactRepository;
        this.consentEvaluationService = consentEvaluationService;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.templateService = templateService;
        this.entitlementService = entitlementService;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public CommunicationModels.ConversationPage list(
            UUID tenantId, UUID workspaceId, String channel, int page, int size
    ) {
        return conversationRepository.search(tenantId, workspaceId, channel, page, size);
    }

    @Transactional(readOnly = true)
    public CommunicationModels.ConversationView get(UUID tenantId, UUID workspaceId, UUID id) {
        return conversationRepository.findById(tenantId, workspaceId, id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conversation not found"));
    }

    @Transactional
    public CommunicationModels.ConversationView create(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            CommunicationModels.CreateConversationCommand command
    ) {
        if (command == null || command.contactId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "contactId is required");
        }
        String channel = requireChannel(command.channel());
        contactRepository.findById(tenantId, workspaceId, command.contactId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        if (command.connectedAccountId() != null) {
            accountRepository.findById(tenantId, workspaceId, command.connectedAccountId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Connected account not found"));
        }
        if (command.externalThreadKey() != null && !command.externalThreadKey().isBlank()) {
            var existing = conversationRepository.findByThread(
                    tenantId, workspaceId, channel, command.externalThreadKey().trim());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        UUID id = UUID.randomUUID();
        conversationRepository.insert(
                id, tenantId, workspaceId, channel, command.contactId(),
                command.connectedAccountId(), blankToNull(command.subject()),
                blankToNull(command.externalThreadKey())
        );
        return get(tenantId, workspaceId, id);
    }

    @Transactional(readOnly = true)
    public CommunicationModels.MessagePage listMessages(
            UUID tenantId, UUID workspaceId, UUID conversationId, int page, int size
    ) {
        get(tenantId, workspaceId, conversationId);
        return messageRepository.listByConversation(tenantId, workspaceId, conversationId, page, size);
    }

    @Transactional
    public CommunicationModels.MessageView composeOutbound(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID conversationId,
            CommunicationModels.ComposeMessageCommand command
    ) {
        CommunicationModels.ConversationView conversation = get(tenantId, workspaceId, conversationId);
        if (command == null || command.bodyText() == null || command.bodyText().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "bodyText is required");
        }
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            var existing = messageRepository.findByIdempotency(
                    tenantId, workspaceId, command.idempotencyKey().trim());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String purpose = command.purpose() == null || command.purpose().isBlank()
                ? "SERVICE"
                : command.purpose().trim().toUpperCase(Locale.ROOT);
        String address = command.destinationAddress();
        if (!"INTERNAL_TEST".equals(conversation.channel())) {
            if (address == null || address.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "destinationAddress is required");
            }
            String consentChannel = "EMAIL".equals(conversation.channel()) ? "EMAIL" : "WHATSAPP";
            ConsentModels.EligibilityResult eligibility = consentEvaluationService.evaluate(
                    tenantId, workspaceId, conversation.contactId(), consentChannel, purpose, address);
            if (!eligibility.eligible()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Not eligible to send: " + eligibility.reasonCode()
                );
            }
            address = eligibility.evaluatedAddress();
        }
        // Interactive WhatsApp compose outside the 24h window requires an APPROVED template.
        // Campaign/system enqueue (actorId null) may supply templateName or use a linked campaign template later.
        if (actorId != null
                && "WHATSAPP".equals(conversation.channel())
                && !insideServiceWindow(tenantId, workspaceId, conversationId)) {
            requireApprovedTemplate(tenantId, workspaceId, command.templateName());
        }
        // Entitlement: check message meter BEFORE creating the message row
        if (!"INTERNAL_TEST".equals(conversation.channel())) {
            String meterCode = "EMAIL".equals(conversation.channel())
                    ? BillingModels.METER_EMAIL_MESSAGES
                    : BillingModels.METER_WHATSAPP_MESSAGES;
            BillingModels.CheckResult msgCheck = entitlementService.check(tenantId, meterCode, 1);
            if (!msgCheck.allowed()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Entitlement limit exceeded: " + meterCode + " – " + msgCheck.reason());
            }
        }
        UUID messageId = UUID.randomUUID();
        String preview = preview(command.bodyText());
        String status = "INTERNAL_TEST".equals(conversation.channel()) ? "SENT" : "QUEUED";
        messageRepository.insert(
                messageId, tenantId, workspaceId, conversationId, "OUTBOUND", conversation.channel(),
                command.bodyText(), preview, purpose, address, null,
                blankToNull(command.idempotencyKey()), status, actorId
        );
        messageRepository.insertStatusEvent(
                UUID.randomUUID(), tenantId, workspaceId, messageId, status, null);
        storeAttachmentMetadata(tenantId, workspaceId, messageId, command.attachments());
        conversationRepository.touchLastMessage(tenantId, workspaceId, conversationId);
        if (!"INTERNAL_TEST".equals(conversation.channel())) {
            outboxRepository.append(OutboxAppendCommand.of(
                    "message",
                    messageId,
                    CommunicationModels.EVENT_MESSAGE_SEND,
                    "{\"messageId\":\"" + messageId + "\"}",
                    tenantId,
                    workspaceId,
                    null,
                    "MESSAGE_SEND:" + messageId
            ));
        }
        if (actorId != null) {
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "message.outbound_queued", "message", messageId.toString(),
                    "{\"channel\":\"" + conversation.channel() + "\",\"status\":\"" + status + "\"}"
            );
        }
        // Entitlement: consume 1 channel message unit (INTERNAL_TEST exempt)
        if (!"INTERNAL_TEST".equals(conversation.channel())) {
            String meterCode = "EMAIL".equals(conversation.channel())
                    ? BillingModels.METER_EMAIL_MESSAGES
                    : BillingModels.METER_WHATSAPP_MESSAGES;
            entitlementService.consume(
                    tenantId, workspaceId,
                    meterCode, 1L,
                    "MSG_SEND:" + messageId,
                    "message", messageId.toString()
            );
        }
        return messageRepository.findById(tenantId, workspaceId, messageId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Message create failed"));
    }

    @Transactional
    public CommunicationModels.MessageView appendInbound(
            UUID tenantId,
            UUID workspaceId,
            UUID conversationId,
            String bodyText,
            String providerMessageId,
            String destinationAddress
    ) {
        CommunicationModels.ConversationView conversation = get(tenantId, workspaceId, conversationId);
        if (providerMessageId != null && !providerMessageId.isBlank()) {
            var existing = messageRepository.findByProviderMessageId(
                    tenantId, workspaceId, conversation.channel(), providerMessageId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        UUID messageId = UUID.randomUUID();
        messageRepository.insert(
                messageId, tenantId, workspaceId, conversationId, "INBOUND", conversation.channel(),
                bodyText, preview(bodyText), null, destinationAddress, providerMessageId,
                null, "DELIVERED", null
        );
        messageRepository.insertStatusEvent(
                UUID.randomUUID(), tenantId, workspaceId, messageId, "DELIVERED", null);
        conversationRepository.touchLastMessage(tenantId, workspaceId, conversationId);
        // Publish INBOUND_MESSAGE so automations can react (e.g., auto-reply flows).
        if (conversation.contactId() != null) {
            eventPublisher.publishEvent(new WorkspaceDomainEvent(
                    "INBOUND_MESSAGE", tenantId, workspaceId, conversation.contactId(),
                    "{\"messageId\":\"" + messageId + "\",\"channel\":\"" + conversation.channel() + "\"}"
            ));
        }
        return messageRepository.findById(tenantId, workspaceId, messageId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Inbound message failed"));
    }

    @Transactional
    public void applyProviderStatus(
            UUID tenantId,
            UUID workspaceId,
            UUID messageId,
            String status,
            String providerMessageId,
            String providerStatus,
            String errorCode,
            String errorMessage
    ) {
        messageRepository.findById(tenantId, workspaceId, messageId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Message not found"));
        messageRepository.updateStatus(
                tenantId, workspaceId, messageId, status, providerMessageId, providerStatus, errorCode, errorMessage);
        messageRepository.insertStatusEvent(
                UUID.randomUUID(), tenantId, workspaceId, messageId, status, providerStatus);
    }

    private boolean insideServiceWindow(UUID tenantId, UUID workspaceId, UUID conversationId) {
        List<Instant> lastInbound = jdbcTemplate.query(
                """
                SELECT created_at FROM messages
                WHERE tenant_id = ? AND workspace_id = ? AND conversation_id = ?
                  AND direction = 'INBOUND'
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (rs, i) -> rs.getTimestamp("created_at").toInstant(),
                tenantId, workspaceId, conversationId
        );
        if (lastInbound.isEmpty()) {
            return false;
        }
        return lastInbound.get(0).isAfter(Instant.now().minus(24, ChronoUnit.HOURS));
    }

    private void requireApprovedTemplate(UUID tenantId, UUID workspaceId, String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Approved WhatsApp template is required outside the 24h service window"
            );
        }
        boolean approved = templateService.list(tenantId, workspaceId, "WHATSAPP").stream()
                .anyMatch(t -> t.name().equalsIgnoreCase(templateName.trim())
                        && "APPROVED".equalsIgnoreCase(t.status()));
        if (!approved) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "WhatsApp template is not APPROVED");
        }
    }

    private void storeAttachmentMetadata(
            UUID tenantId,
            UUID workspaceId,
            UUID messageId,
            List<CommunicationModels.AttachmentMeta> attachments
    ) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        for (CommunicationModels.AttachmentMeta meta : attachments) {
            if (meta == null || meta.providerMediaId() == null || meta.providerMediaId().isBlank()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Attachment providerMediaId is required (ADR 0008; no object store yet)"
                );
            }
            messageRepository.insertAttachment(
                    UUID.randomUUID(),
                    tenantId,
                    workspaceId,
                    messageId,
                    meta.filename(),
                    meta.mimeType(),
                    meta.sizeBytes(),
                    meta.providerMediaId().trim(),
                    null
            );
        }
    }

    private static String requireChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "channel is required");
        }
        String normalized = channel.trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported channel");
        }
        return normalized;
    }

    private static String preview(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
