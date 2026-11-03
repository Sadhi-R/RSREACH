package com.rsreach.contacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.common.event.WorkspaceDomainEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConsentRepository consentRepository;
    private final ContactRepository contactRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public ConsentService(
            ConsentRepository consentRepository,
            ContactRepository contactRepository,
            AuditService auditService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.consentRepository = consentRepository;
        this.contactRepository = contactRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Returns the consent matrix for the contact's home workspace.
     * Missing cells are seeded as UNKNOWN only (never OPTED_IN). Concurrent GETs are idempotent.
     */
    @Transactional
    public ConsentModels.ConsentMatrix getOrSeedMatrix(UUID tenantId, UUID workspaceId, UUID contactId, UUID actorId) {
        if (!contactRepository.lockById(tenantId, workspaceId, contactId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Contact not found");
        }
        for (String channel : ConsentModels.CHANNELS) {
            for (String purpose : ConsentModels.PURPOSES) {
                Optional<UUID> inserted = consentRepository.insertUnknownIfAbsent(
                        tenantId, workspaceId, contactId, channel, purpose, actorId);
                if (inserted.isPresent()) {
                    consentRepository.insertEvent(
                            tenantId, workspaceId, contactId, channel, purpose,
                            null, "UNKNOWN", "SEEDED_UNKNOWN", "API", "{}", actorId
                    );
                }
            }
        }
        return new ConsentModels.ConsentMatrix(
                contactId,
                workspaceId,
                consentRepository.listConsents(tenantId, workspaceId, contactId)
        );
    }

    @Transactional(readOnly = true)
    public ConsentModels.ConsentHistoryPage history(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            int page,
            int size
    ) {
        requireContact(tenantId, workspaceId, contactId);
        return consentRepository.listEvents(tenantId, workspaceId, contactId, page, size);
    }

    @Transactional
    public ConsentModels.ConsentView change(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID contactId,
            ConsentModels.ConsentChangeCommand command
    ) {
        if (!contactRepository.lockById(tenantId, workspaceId, contactId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Contact not found");
        }
        String channel = requireChannel(command.channelType());
        String purpose = requirePurpose(command.purpose());
        String status = requireStatus(command.status());
        String source = requireSource(command.source() == null ? "MANUAL" : command.source());

        if ("OPTED_IN".equals(status) && "MANUAL".equals(source)) {
            if (command.evidenceNote() == null || command.evidenceNote().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Evidence note is required for MANUAL opt-in");
            }
        }
        if ("UNKNOWN".equals(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cannot set consent to UNKNOWN via grant/withdraw API");
        }

        String evidenceJson = evidenceJson(command.evidenceNote());
        Optional<ConsentModels.ConsentView> current =
                consentRepository.findConsent(tenantId, workspaceId, contactId, channel, purpose);
        String previous = current.map(ConsentModels.ConsentView::status).orElse(null);
        if (current.isEmpty()) {
            try {
                consentRepository.insertConsent(
                        tenantId, workspaceId, contactId, channel, purpose, status, source, evidenceJson, actorId);
            } catch (DataIntegrityViolationException ex) {
                current = consentRepository.findConsent(tenantId, workspaceId, contactId, channel, purpose);
                if (current.isEmpty()) {
                    throw new ApiException(ErrorCode.CONFLICT, "Concurrent consent update failed");
                }
                previous = current.get().status();
                if (status.equals(previous)) {
                    return current.get();
                }
                consentRepository.updateConsent(
                        tenantId, workspaceId, contactId, channel, purpose, status, source, evidenceJson, actorId);
            }
        } else {
            if (status.equals(current.get().status())) {
                return current.get();
            }
            consentRepository.updateConsent(
                    tenantId, workspaceId, contactId, channel, purpose, status, source, evidenceJson, actorId);
        }

        String eventType = "OPTED_IN".equals(status) ? "GRANTED" : "WITHDRAWN";
        consentRepository.insertEvent(
                tenantId, workspaceId, contactId, channel, purpose,
                previous, status, eventType, source, evidenceJson, actorId
        );
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "consent.changed", "contact", contactId.toString(),
                "{\"channel\":\"" + channel + "\",\"purpose\":\"" + purpose
                        + "\",\"status\":\"" + status + "\",\"eventType\":\"" + eventType
                        + "\",\"workspaceId\":\"" + workspaceId + "\"}"
        );
        // Publish CONSENT_CHANGED so automations can react (e.g., re-check eligibility).
        eventPublisher.publishEvent(new WorkspaceDomainEvent(
                "CONSENT_CHANGED", tenantId, workspaceId, contactId,
                "{\"channel\":\"" + channel + "\",\"purpose\":\"" + purpose + "\",\"status\":\"" + status + "\"}"
        ));
        return consentRepository.findConsent(tenantId, workspaceId, contactId, channel, purpose)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Consent update failed"));
    }

    private ContactModels.ContactView requireContact(UUID tenantId, UUID workspaceId, UUID contactId) {
        // Consent is home-workspace only (ADR 0004); never resolve via share-expand.
        return contactRepository.findByIdHomeOnly(tenantId, workspaceId, contactId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
    }

    private static String requireChannel(String channel) {
        if (channel == null || !ConsentModels.CHANNELS.contains(channel.trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "channelType must be EMAIL or WHATSAPP");
        }
        return channel.trim().toUpperCase(Locale.ROOT);
    }

    private static String requirePurpose(String purpose) {
        if (purpose == null || !ConsentModels.PURPOSES.contains(purpose.trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "purpose must be MARKETING, TRANSACTIONAL, or SERVICE");
        }
        return purpose.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireStatus(String status) {
        if (status == null || !ConsentModels.STATUSES.contains(status.trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid consent status");
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireSource(String source) {
        if (source == null || !ConsentModels.SOURCES.contains(source.trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid consent source");
        }
        return source.trim().toUpperCase(Locale.ROOT);
    }

    private static String evidenceJson(String note) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            if (note != null && !note.isBlank()) {
                String trimmed = note.trim();
                if (trimmed.length() > 500) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Evidence note is too long");
                }
                map.put("note", trimmed);
            }
            return MAPPER.writeValueAsString(map);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid evidence");
        }
    }
}
