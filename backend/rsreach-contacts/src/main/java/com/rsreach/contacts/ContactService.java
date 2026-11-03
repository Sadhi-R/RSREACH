package com.rsreach.contacts;

import com.rsreach.audit.AuditService;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.common.event.WorkspaceDomainEvent;
import org.springframework.context.ApplicationEventPublisher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactService {

    private static final Pattern FIELD_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Set<String> FIELD_TYPES = Set.of("STRING", "NUMBER", "DATE", "BOOLEAN");

    private final ContactRepository contactRepository;
    private final ContactShareService contactShareService;
    private final AuditService auditService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final EntitlementService entitlementService;
    private final ApplicationEventPublisher eventPublisher;

    public ContactService(
            ContactRepository contactRepository,
            ContactShareService contactShareService,
            AuditService auditService,
            DuplicateDetectionService duplicateDetectionService,
            EntitlementService entitlementService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.contactRepository = contactRepository;
        this.contactShareService = contactShareService;
        this.auditService = auditService;
        this.duplicateDetectionService = duplicateDetectionService;
        this.entitlementService = entitlementService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ContactModels.ContactView create(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            ContactModels.ContactCreateCommand command
    ) {
        String displayName = requireDisplayName(command.displayName());
        List<ContactRepository.PreparedChannel> channels = prepareChannels(command.channels());
        assertNoActiveDuplicates(tenantId, workspaceId, channels, null);

        // Entitlement check before insert to fail fast
        BillingModels.CheckResult check = entitlementService.check(tenantId, BillingModels.METER_CONTACTS, 1L);
        if (!check.allowed()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Contacts entitlement limit reached: " + check.reason());
        }

        List<ContactModels.CustomFieldDef> defs = contactRepository.listFieldDefs(tenantId, workspaceId, true);
        Map<String, Object> customFields = CustomFieldValidator.validateAndNormalize(command.customFields(), defs);
        String primaryEmail = primaryNormalized(channels, "EMAIL");
        String primaryPhone = primaryNormalized(channels, "PHONE");

        try {
            UUID contactId = contactRepository.insertContact(
                    tenantId, workspaceId, displayName, primaryEmail, primaryPhone,
                    CustomFieldValidator.toJson(customFields), actorId
            );
            contactRepository.replaceChannels(tenantId, workspaceId, contactId, channels);
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "contact.created", "contact", contactId.toString(),
                    "{\"channelCount\":" + channels.size() + "}"
            );
            // Consume entitlement after successful insert (idempotent key prevents double-count on retry)
            entitlementService.consume(
                    tenantId, workspaceId,
                    BillingModels.METER_CONTACTS, 1L,
                    "CONTACT_CREATE:" + contactId,
                    "contact", contactId.toString()
            );
            duplicateDetectionService.refreshForContact(tenantId, workspaceId, contactId);
            ContactModels.ContactView created = contactRepository.findById(tenantId, workspaceId, contactId)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Contact create failed"));
            eventPublisher.publishEvent(new WorkspaceDomainEvent(
                    "CONTACT_CREATED", tenantId, workspaceId, contactId,
                    "{\"contactId\":\"" + contactId + "\"}"));
            return created;
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Contact identifier already exists in this workspace");
        }
    }

    @Transactional(readOnly = true)
    public ContactModels.ContactView get(UUID tenantId, UUID workspaceId, UUID contactId) {
        ContactModels.ContactView contact = contactRepository.findById(tenantId, workspaceId, contactId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        ContactShareModels.Access access = contactShareService.resolveContactAccess(tenantId, workspaceId, contactId);
        return contact.withAccess(access.level().name());
    }

    @Transactional(readOnly = true)
    public ContactModels.PageResult search(
            UUID tenantId,
            UUID workspaceId,
            String query,
            String status,
            int page,
            int size
    ) {
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!normalized.equals("ACTIVE") && !normalized.equals("ARCHIVED")) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "status must be ACTIVE or ARCHIVED");
            }
        }
        return contactRepository.search(tenantId, workspaceId, query, status, page, size);
    }

    @Transactional
    public ContactModels.ContactView update(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID contactId,
            ContactModels.ContactUpdateCommand command
    ) {
        ContactShareModels.Access access = requireWriteAccess(tenantId, workspaceId, contactId);
        UUID homeWorkspaceId = access.homeWorkspaceId();
        ContactModels.ContactView existing = contactRepository.findById(tenantId, workspaceId, contactId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        if (!"ACTIVE".equals(existing.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only ACTIVE contacts can be updated");
        }
        String displayName = requireDisplayName(command.displayName());
        List<ContactRepository.PreparedChannel> channels = prepareChannels(command.channels());
        assertNoActiveDuplicates(tenantId, homeWorkspaceId, channels, contactId);

        List<ContactModels.CustomFieldDef> defs = contactRepository.listFieldDefs(tenantId, homeWorkspaceId, true);
        Map<String, Object> customFields = CustomFieldValidator.validateAndNormalize(command.customFields(), defs);
        String primaryEmail = primaryNormalized(channels, "EMAIL");
        String primaryPhone = primaryNormalized(channels, "PHONE");

        try {
            contactRepository.updateContact(
                    tenantId, homeWorkspaceId, contactId, displayName, primaryEmail, primaryPhone,
                    CustomFieldValidator.toJson(customFields), actorId
            );
            contactRepository.replaceChannels(tenantId, homeWorkspaceId, contactId, channels);
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "contact.updated", "contact", contactId.toString(),
                    "{\"channelCount\":" + channels.size() + "}"
            );
            duplicateDetectionService.refreshForContact(tenantId, homeWorkspaceId, contactId);
            eventPublisher.publishEvent(new WorkspaceDomainEvent(
                    "CONTACT_UPDATED", tenantId, workspaceId, contactId,
                    "{\"contactId\":\"" + contactId + "\"}"));
            return get(tenantId, workspaceId, contactId);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Contact identifier already exists in this workspace");
        }
    }

    @Transactional
    public ContactModels.ContactView archive(UUID tenantId, UUID workspaceId, UUID actorId, UUID contactId) {
        ContactShareModels.Access access = requireWriteAccess(tenantId, workspaceId, contactId);
        UUID homeWorkspaceId = access.homeWorkspaceId();
        contactRepository.archive(tenantId, homeWorkspaceId, contactId, actorId);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.archived", "contact", contactId.toString(), "{}"
        );
        duplicateDetectionService.refreshForContact(tenantId, homeWorkspaceId, contactId);
        return get(tenantId, workspaceId, contactId);
    }

    @Transactional
    public ContactModels.ContactView restore(UUID tenantId, UUID workspaceId, UUID actorId, UUID contactId) {
        ContactShareModels.Access access = requireWriteAccess(tenantId, workspaceId, contactId);
        UUID homeWorkspaceId = access.homeWorkspaceId();
        ContactModels.ContactView existing = contactRepository.findById(tenantId, workspaceId, contactId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        if (!"ARCHIVED".equals(existing.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only ARCHIVED contacts can be restored");
        }
        if (contactRepository.isMergeLoser(tenantId, homeWorkspaceId, contactId)) {
            throw new ApiException(ErrorCode.CONFLICT, "Merged contacts cannot be restored");
        }
        List<ContactModels.ChannelView> channels = contactRepository.listChannels(tenantId, homeWorkspaceId, contactId);
        for (ContactModels.ChannelView channel : channels) {
            if (contactRepository.activeChannelExists(
                    tenantId, homeWorkspaceId, channel.channelType(), channel.valueNormalized(), contactId)) {
                throw new ApiException(
                        ErrorCode.CONFLICT,
                        "Cannot restore: identifier is already used by another active contact"
                );
            }
        }
        try {
            contactRepository.restore(tenantId, homeWorkspaceId, contactId, actorId);
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "contact.restored", "contact", contactId.toString(), "{}"
            );
            duplicateDetectionService.refreshForContact(tenantId, homeWorkspaceId, contactId);
            return get(tenantId, workspaceId, contactId);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "Cannot restore: identifier is already used by another active contact"
            );
        }
    }

    @Transactional(readOnly = true)
    public List<ContactModels.CustomFieldDef> listFieldDefs(UUID tenantId, UUID workspaceId) {
        return contactRepository.listFieldDefs(tenantId, workspaceId, false);
    }

    @Transactional
    public ContactModels.CustomFieldDef createFieldDef(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            ContactModels.CustomFieldDefCreateCommand command
    ) {
        if (command.fieldKey() == null || !FIELD_KEY.matcher(command.fieldKey()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid custom field key");
        }
        if (command.label() == null || command.label().isBlank() || command.label().trim().length() > 128) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid custom field label");
        }
        String dataType = command.dataType() == null ? "" : command.dataType().trim().toUpperCase(Locale.ROOT);
        if (!FIELD_TYPES.contains(dataType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported custom field data type");
        }
        try {
            UUID id = contactRepository.insertFieldDef(
                    tenantId,
                    workspaceId,
                    new ContactModels.CustomFieldDefCreateCommand(
                            command.fieldKey(), command.label().trim(), dataType, command.required()
                    )
            );
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "contact.field_def.created", "contact_custom_field_def", id.toString(),
                    "{\"fieldKey\":\"" + command.fieldKey() + "\"}"
            );
            return contactRepository.listFieldDefs(tenantId, workspaceId, false).stream()
                    .filter(def -> def.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Field def create failed"));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Custom field key already exists in this workspace");
        }
    }

    private static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "displayName is required");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "displayName is too long");
        }
        return trimmed;
    }

    private List<ContactRepository.PreparedChannel> prepareChannels(List<ContactModels.ChannelInput> inputs) {
        List<ContactModels.ChannelInput> source = inputs == null ? List.of() : inputs;
        List<ContactRepository.PreparedChannel> prepared = new ArrayList<>();
        Set<String> seenNormalized = new HashSet<>();
        boolean primaryEmail = false;
        boolean primaryPhone = false;

        for (ContactModels.ChannelInput input : source) {
            if (input == null || input.channelType() == null || input.channelType().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "channelType is required");
            }
            String type = input.channelType().trim().toUpperCase(Locale.ROOT);
            if (!type.equals("EMAIL") && !type.equals("PHONE")) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported channel type");
            }
            String raw = input.value() == null ? "" : input.value().trim();
            String normalized = ContactIdentifierNormalizer.normalize(type, raw);
            String dedupeKey = type + ":" + normalized;
            if (!seenNormalized.add(dedupeKey)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Duplicate identifier in request");
            }
            boolean isPrimary = Boolean.TRUE.equals(input.primary());
            if (isPrimary) {
                if (type.equals("EMAIL")) {
                    if (primaryEmail) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only one primary EMAIL allowed");
                    }
                    primaryEmail = true;
                } else {
                    if (primaryPhone) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only one primary PHONE allowed");
                    }
                    primaryPhone = true;
                }
            }
            String label = input.label() == null || input.label().isBlank() ? null : input.label().trim();
            if (label != null && label.length() > 64) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Channel label is too long");
            }
            prepared.add(new ContactRepository.PreparedChannel(type, raw, normalized, isPrimary, label));
        }

        // Default first of each type to primary when none marked.
        if (!primaryEmail) {
            markFirstPrimary(prepared, "EMAIL");
        }
        if (!primaryPhone) {
            markFirstPrimary(prepared, "PHONE");
        }
        return prepared;
    }

    private static void markFirstPrimary(List<ContactRepository.PreparedChannel> channels, String type) {
        for (int i = 0; i < channels.size(); i++) {
            ContactRepository.PreparedChannel channel = channels.get(i);
            if (channel.channelType().equals(type)) {
                channels.set(i, new ContactRepository.PreparedChannel(
                        channel.channelType(), channel.valueRaw(), channel.valueNormalized(), true, channel.label()
                ));
                return;
            }
        }
    }

    private void assertNoActiveDuplicates(
            UUID tenantId,
            UUID workspaceId,
            List<ContactRepository.PreparedChannel> channels,
            UUID excludeContactId
    ) {
        for (ContactRepository.PreparedChannel channel : channels) {
            if (contactRepository.activeChannelExists(
                    tenantId, workspaceId, channel.channelType(), channel.valueNormalized(), excludeContactId)) {
                throw new ApiException(ErrorCode.CONFLICT, "Contact identifier already exists in this workspace");
            }
        }
    }

    private static String primaryNormalized(List<ContactRepository.PreparedChannel> channels, String type) {
        for (ContactRepository.PreparedChannel channel : channels) {
            if (channel.channelType().equals(type) && channel.primary()) {
                return channel.valueNormalized();
            }
        }
        return null;
    }

    private ContactShareModels.Access requireWriteAccess(UUID tenantId, UUID workspaceId, UUID contactId) {
        ContactShareModels.Access access = contactShareService.resolveContactAccess(tenantId, workspaceId, contactId);
        if (!access.canWrite()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Write access is required for this contact");
        }
        return access;
    }
}
