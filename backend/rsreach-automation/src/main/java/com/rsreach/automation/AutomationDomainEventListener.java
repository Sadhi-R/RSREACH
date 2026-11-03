package com.rsreach.automation;

import com.rsreach.common.event.WorkspaceDomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges {@link WorkspaceDomainEvent} instances published by domain services
 * (contacts, campaigns) into automation trigger evaluations.
 *
 * <p>Uses {@code AFTER_COMMIT} so automation runs are only enqueued once the
 * originating business transaction has committed successfully. The outbox
 * append inside {@link AutomationTriggerService#onDomainEvent} runs in a new
 * transaction started by the event listener.
 */
@Component
public class AutomationDomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationDomainEventListener.class);

    private final AutomationTriggerService automationTriggerService;

    public AutomationDomainEventListener(AutomationTriggerService automationTriggerService) {
        this.automationTriggerService = automationTriggerService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(WorkspaceDomainEvent event) {
        if (event.tenantId() == null || event.workspaceId() == null) {
            log.debug("automation_domain_event_skipped type={} missing context", event.type());
            return;
        }

        String triggerType = mapToTriggerType(event.type());
        if (triggerType == null) {
            log.debug("automation_domain_event_no_trigger type={}", event.type());
            return;
        }

        log.debug("automation_domain_event type={} trigger={} tenant={} workspace={}",
                event.type(), triggerType, event.tenantId(), event.workspaceId());

        try {
            automationTriggerService.onDomainEvent(
                    event.tenantId(),
                    event.workspaceId(),
                    triggerType,
                    event.resourceId(),
                    event.payloadJson() != null ? event.payloadJson() : "{}"
            );
        } catch (Exception ex) {
            // Log and swallow — automation trigger failures must not roll back the originating transaction
            log.error("automation_domain_event_error type={} tenantId={}: {}",
                    event.type(), event.tenantId(), ex.getMessage(), ex);
        }
    }

    /**
     * Maps a domain event type string to the corresponding automation trigger type constant.
     * Returns {@code null} for event types that do not have a matching automation trigger.
     */
    private static String mapToTriggerType(String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case "CONTACT_CREATED"      -> AutomationModels.TRIGGER_CONTACT_CREATED;
            case "CONTACT_UPDATED"      -> AutomationModels.TRIGGER_CONTACT_UPDATED;
            case "CONTACT_GROUP_ADDED"  -> AutomationModels.TRIGGER_CONTACT_GROUP_ADDED;
            case "CONSENT_CHANGED"      -> AutomationModels.TRIGGER_CONSENT_CHANGED;
            case "INBOUND_MESSAGE"      -> AutomationModels.TRIGGER_INBOUND_MESSAGE;
            case "CAMPAIGN_COMPLETED"   -> AutomationModels.TRIGGER_CAMPAIGN_COMPLETED;
            default -> null;
        };
    }
}
