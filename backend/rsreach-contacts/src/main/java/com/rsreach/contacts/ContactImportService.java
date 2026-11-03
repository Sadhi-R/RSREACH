package com.rsreach.contacts;

import com.rsreach.audit.AuditService;
import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import com.rsreach.tenancy.CustomerAuthorizationService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContactImportService {

    private static final Logger log = LoggerFactory.getLogger(ContactImportService.class);

    private final ContactImportRepository importRepository;
    private final ContactService contactService;
    private final ContactRepository contactRepository;
    private final ConsentRepository consentRepository;
    private final ConsentService consentService;
    private final CustomerAuthorizationService authorizationService;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public ContactImportService(
            ContactImportRepository importRepository,
            ContactService contactService,
            ContactRepository contactRepository,
            ConsentRepository consentRepository,
            ConsentService consentService,
            CustomerAuthorizationService authorizationService,
            OutboxRepository outboxRepository,
            AuditService auditService,
            PlatformTransactionManager transactionManager
    ) {
        this.importRepository = importRepository;
        this.contactService = contactService;
        this.contactRepository = contactRepository;
        this.consentRepository = consentRepository;
        this.consentService = consentService;
        this.authorizationService = authorizationService;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public ContactImportModels.JobView preview(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            String filename,
            InputStream csvStream,
            ContactImportModels.ColumnMapping mapping
    ) {
        ContactImportModels.ColumnMapping resolved = requireMapping(mapping);
        assertConsentMappingAllowed(tenantId, workspaceId, actorId, resolved);
        ContactCsvSupport.ParsedCsv parsed = ContactCsvSupport.parse(
                csvStream, ContactImportModels.MAX_UPLOAD_BYTES, ContactImportModels.MAX_ROWS);
        validateHeaders(parsed.headers(), resolved);

        String safeName = sanitizeFilename(filename);
        UUID jobId = importRepository.insertJob(
                tenantId, workspaceId, safeName, ContactImportModels.POLICY_SKIP,
                parsed.rows().size(), importRepository.toJson(resolved), actorId, null
        );

        int rowNumber = 0;
        for (Map<String, String> raw : parsed.rows()) {
            rowNumber++;
            Map<String, Object> payload = new LinkedHashMap<>(raw);
            String result = "PENDING";
            String errorCode = null;
            String errorMessage = null;
            if (rowNumber <= ContactImportModels.PREVIEW_VALIDATE_ROWS) {
                RowValidation validation = validateRow(tenantId, workspaceId, resolved, raw);
                result = validation.result();
                errorCode = validation.errorCode();
                errorMessage = validation.errorMessage();
            }
            importRepository.insertRow(
                    jobId, tenantId, workspaceId, rowNumber,
                    importRepository.toJson(payload), result, errorCode, errorMessage
            );
        }
        importRepository.markPreviewReady(jobId, tenantId, workspaceId);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contacts.import.previewed", "contact_import_job", jobId.toString(),
                "{\"filename\":\"" + escapeJson(safeName) + "\",\"rowCount\":" + parsed.rows().size() + "}"
        );
        return getJob(tenantId, workspaceId, jobId);
    }

    @Transactional
    public ContactImportModels.JobView commit(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID jobId,
            String conflictPolicy,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key is required");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 128) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key is too long");
        }

        Optional<ContactImportModels.JobView> byKey =
                importRepository.findByIdempotencyKey(tenantId, workspaceId, key);
        if (byKey.isPresent()) {
            ContactImportModels.JobView existing = byKey.get();
            if (!existing.id().equals(jobId)) {
                throw new ApiException(ErrorCode.CONFLICT, "Idempotency-Key reuse with different job");
            }
            return getJob(tenantId, workspaceId, jobId);
        }

        ContactImportModels.JobView job = importRepository.findJob(tenantId, workspaceId, jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Import job not found"));
        if (!"PREVIEW_READY".equals(job.status()) && !"RUNNING".equals(job.status())
                && !"COMPLETED".equals(job.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Import job is not ready to commit");
        }
        if ("RUNNING".equals(job.status()) || "COMPLETED".equals(job.status())) {
            return getJob(tenantId, workspaceId, jobId);
        }

        assertConsentMappingAllowed(tenantId, workspaceId, actorId, job.columnMapping());

        String policy = normalizePolicy(conflictPolicy);
        boolean marked = importRepository.markRunning(jobId, tenantId, workspaceId, policy);
        if (!marked) {
            return getJob(tenantId, workspaceId, jobId);
        }

        try {
            importRepository.setIdempotencyKey(jobId, tenantId, workspaceId, key);
        } catch (DataIntegrityViolationException ex) {
            return importRepository.findByIdempotencyKey(tenantId, workspaceId, key)
                    .map(j -> getJob(tenantId, workspaceId, j.id()))
                    .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Import commit race"));
        }

        outboxRepository.append(OutboxAppendCommand.of(
                ContactImportModels.AGGREGATE_IMPORT_JOB,
                jobId,
                ContactImportModels.EVENT_CONTACT_IMPORT_PROCESS,
                "{\"jobId\":\"" + jobId + "\"}",
                tenantId,
                workspaceId,
                null,
                "CONTACT_IMPORT_PROCESS:" + jobId
        ));
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contacts.import.committed", "contact_import_job", jobId.toString(),
                "{\"conflictPolicy\":\"" + policy + "\",\"rowCount\":" + job.rowCount() + "}"
        );
        return getJob(tenantId, workspaceId, jobId);
    }

    @Transactional(readOnly = true)
    public ContactImportModels.JobView getJob(UUID tenantId, UUID workspaceId, UUID jobId) {
        ContactImportModels.JobView job = importRepository.findJob(tenantId, workspaceId, jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Import job not found"));
        List<ContactImportModels.RowPreview> sample = importRepository.listSampleRows(jobId, 100);
        return new ContactImportModels.JobView(
                job.id(), job.tenantId(), job.workspaceId(), job.filename(), job.status(),
                job.conflictPolicy(), job.rowCount(), job.successCount(), job.failureCount(),
                job.skippedCount(), job.previewReadyAt(), job.startedAt(), job.completedAt(),
                job.errorSummary(), job.columnMapping(), job.createdBy(), job.createdAt(),
                job.updatedAt(), sample
        );
    }

    public void processJob(UUID tenantId, UUID workspaceId, UUID jobId) {
        ContactImportModels.JobView job = transactionTemplate.execute(status ->
                importRepository.findJob(tenantId, workspaceId, jobId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Import job not found"))
        );
        if (job == null) {
            return;
        }
        if (!"RUNNING".equals(job.status()) && !"COMPLETED".equals(job.status())) {
            log.info("import_process_skip jobId={} status={}", jobId, job.status());
            return;
        }
        if ("COMPLETED".equals(job.status())) {
            return;
        }

        ContactImportModels.ColumnMapping mapping = job.columnMapping();
        String policy = job.conflictPolicy();
        UUID actorId = job.createdBy();
        boolean canManageConsent = authorizationService
                .resolveWorkspacePermissions(actorId, tenantId, workspaceId)
                .contains(Permissions.CONTACTS_CONSENT_MANAGE);
        while (true) {
            List<ContactImportRepository.RowWorkItem> batch = transactionTemplate.execute(status ->
                    importRepository.claimPendingRows(
                            jobId, tenantId, workspaceId, ContactImportModels.WORKER_BATCH_SIZE)
            );
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (ContactImportRepository.RowWorkItem item : batch) {
                applyRow(tenantId, workspaceId, jobId, mapping, policy, actorId, canManageConsent, item);
            }
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (!importRepository.hasProcessableRows(jobId)) {
                importRepository.markCompleted(jobId);
            }
        });
    }

    private void applyRow(
            UUID tenantId,
            UUID workspaceId,
            UUID jobId,
            ContactImportModels.ColumnMapping mapping,
            String policy,
            UUID actorId,
            boolean canManageConsent,
            ContactImportRepository.RowWorkItem item
    ) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                RowValidation validation = validateRow(tenantId, workspaceId, mapping, item.payload());
                if ("ERROR".equals(validation.result())) {
                    importRepository.updateRowResult(
                            item.id(), "FAILED", validation.errorCode(), validation.errorMessage(), null);
                    importRepository.incrementCounters(jobId, 0, 1, 0);
                    return;
                }

                Optional<UUID> existingId = findMatchingContact(tenantId, workspaceId, mapping, item.payload());
                UUID contactId;
                String result;
                if (existingId.isPresent()) {
                    if (ContactImportModels.POLICY_SKIP.equals(policy)) {
                        importRepository.updateRowResult(
                                item.id(), "SKIPPED", "CONFLICT", "Matching contact exists", existingId.get());
                        importRepository.incrementCounters(jobId, 0, 0, 1);
                        return;
                    }
                    if (ContactImportModels.POLICY_UPDATE.equals(policy)) {
                        ContactModels.ContactView updated = applyUpdate(
                                tenantId, workspaceId, actorId, existingId.get(), mapping, item.payload());
                        contactId = updated.id();
                        result = "UPDATED";
                    } else {
                        ContactModels.ContactCreateCommand command = toCreateCommand(mapping, item.payload());
                        ContactModels.ContactView created =
                                contactService.create(tenantId, workspaceId, actorId, command);
                        contactId = created.id();
                        result = "CREATED";
                    }
                } else {
                    ContactModels.ContactCreateCommand command = toCreateCommand(mapping, item.payload());
                    ContactModels.ContactView created =
                            contactService.create(tenantId, workspaceId, actorId, command);
                    contactId = created.id();
                    result = "CREATED";
                }

                applyConsentColumns(
                        tenantId, workspaceId, actorId, contactId, mapping, item.payload(), canManageConsent);
                importRepository.updateRowResult(item.id(), result, null, null, contactId);
                importRepository.incrementCounters(jobId, 1, 0, 0);
            });
        } catch (ApiException ex) {
            markRowFailed(jobId, item.id(), ex.getCode().name(), ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            markRowFailed(jobId, item.id(), "CONFLICT", "Contact identifier already exists in this workspace");
        } catch (RuntimeException ex) {
            log.warn("import_row_failed jobId={} row={}", jobId, item.rowNumber());
            markRowFailed(jobId, item.id(), "INTERNAL_ERROR", "Row processing failed");
        }
    }

    private void markRowFailed(UUID jobId, UUID rowId, String errorCode, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            importRepository.updateRowResult(rowId, "FAILED", errorCode, errorMessage, null);
            importRepository.incrementCounters(jobId, 0, 1, 0);
        });
    }

    private ContactModels.ContactView applyUpdate(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID contactId,
            ContactImportModels.ColumnMapping mapping,
            Map<String, String> row
    ) {
        ContactModels.ContactView existing = contactService.get(tenantId, workspaceId, contactId);
        String displayName = ContactCsvSupport.cell(row, mapping.displayName());
        if (displayName.isBlank()) {
            displayName = existing.displayName();
        }
        List<ContactModels.ChannelInput> channels = new ArrayList<>();
        for (ContactModels.ChannelView ch : existing.channels()) {
            if ("ACTIVE".equals(ch.status())) {
                channels.add(new ContactModels.ChannelInput(
                        ch.channelType(), ch.value(), ch.primary(), ch.label()));
            }
        }
        addChannelIfMissing(channels, "EMAIL", ContactCsvSupport.cell(row, mapping.email()));
        addChannelIfMissing(channels, "PHONE", ContactCsvSupport.cell(row, mapping.phone()));

        Map<String, Object> customFields = new HashMap<>(
                existing.customFields() == null ? Map.of() : existing.customFields());
        for (Map.Entry<String, String> e : mapping.customFields().entrySet()) {
            String value = ContactCsvSupport.cell(row, e.getValue());
            if (!value.isBlank()) {
                customFields.put(e.getKey(), value);
            }
        }
        return contactService.update(
                tenantId, workspaceId, actorId, contactId,
                new ContactModels.ContactUpdateCommand(displayName, channels, customFields)
        );
    }

    private static void addChannelIfMissing(
            List<ContactModels.ChannelInput> channels, String type, String raw
    ) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String normalized = ContactIdentifierNormalizer.normalize(type, raw);
        for (ContactModels.ChannelInput ch : channels) {
            if (type.equalsIgnoreCase(ch.channelType())) {
                String existingNorm = ContactIdentifierNormalizer.normalize(type, ch.value());
                if (existingNorm.equals(normalized)) {
                    return;
                }
            }
        }
        boolean hasPrimary = channels.stream()
                .anyMatch(c -> type.equalsIgnoreCase(c.channelType()) && Boolean.TRUE.equals(c.primary()));
        channels.add(new ContactModels.ChannelInput(type, raw.trim(), !hasPrimary, null));
    }

    private Optional<UUID> findMatchingContact(
            UUID tenantId,
            UUID workspaceId,
            ContactImportModels.ColumnMapping mapping,
            Map<String, String> row
    ) {
        String email = ContactCsvSupport.cell(row, mapping.email());
        if (!email.isBlank()) {
            try {
                String normalized = ContactIdentifierNormalizer.normalizeEmail(email);
                Optional<UUID> id = contactRepository.findActiveContactIdByNormalized(
                        tenantId, workspaceId, "EMAIL", normalized);
                if (id.isPresent()) {
                    return id;
                }
            } catch (ApiException ignored) {
                // validation handles invalid values
            }
        }
        String phone = ContactCsvSupport.cell(row, mapping.phone());
        if (!phone.isBlank()) {
            try {
                String normalized = ContactIdentifierNormalizer.normalizePhone(phone);
                return contactRepository.findActiveContactIdByNormalized(
                        tenantId, workspaceId, "PHONE", normalized);
            } catch (ApiException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private RowValidation validateRow(
            UUID tenantId,
            UUID workspaceId,
            ContactImportModels.ColumnMapping mapping,
            Map<String, String> row
    ) {
        String displayName = ContactCsvSupport.cell(row, mapping.displayName());
        if (displayName.isBlank()) {
            return new RowValidation("ERROR", "VALIDATION_FAILED", "displayName is required");
        }
        String email = ContactCsvSupport.cell(row, mapping.email());
        String phone = ContactCsvSupport.cell(row, mapping.phone());
        if (email.isBlank() && phone.isBlank()) {
            return new RowValidation("ERROR", "VALIDATION_FAILED", "At least one of email or phone is required");
        }
        String emailNormalized = null;
        String phoneNormalized = null;
        try {
            if (!email.isBlank()) {
                emailNormalized = ContactIdentifierNormalizer.normalizeEmail(email);
            }
            if (!phone.isBlank()) {
                phoneNormalized = ContactIdentifierNormalizer.normalizePhone(phone);
            }
            validateConsentCells(mapping, row);
            toCreateCommand(mapping, row);
        } catch (ApiException ex) {
            return new RowValidation("ERROR", ex.getCode().name(), ex.getMessage());
        }

        Optional<UUID> match = findMatchingContact(tenantId, workspaceId, mapping, row);
        if (match.isPresent()) {
            return new RowValidation("WARNING", "CONFLICT", "Matching contact exists");
        }

        if (emailNormalized != null
                && consentRepository.findActiveSuppression(tenantId, workspaceId, "EMAIL", emailNormalized)
                .isPresent()) {
            return new RowValidation(
                    "WARNING", "SUPPRESSED", "Email matches an active suppression list entry");
        }
        if (phoneNormalized != null
                && consentRepository.findActiveSuppression(tenantId, workspaceId, "WHATSAPP", phoneNormalized)
                .isPresent()) {
            return new RowValidation(
                    "WARNING", "SUPPRESSED", "Phone matches an active suppression list entry");
        }
        return new RowValidation("OK", null, null);
    }

    private void applyConsentColumns(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID contactId,
            ContactImportModels.ColumnMapping mapping,
            Map<String, String> row,
            boolean canManageConsent
    ) {
        if (!mapping.hasConsentColumns()) {
            return;
        }
        if (!canManageConsent) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing permission: " + Permissions.CONTACTS_CONSENT_MANAGE);
        }
        applyOneConsent(
                tenantId, workspaceId, actorId, contactId, "EMAIL", "MARKETING",
                ContactCsvSupport.cell(row, mapping.consentEmailMarketing()));
        applyOneConsent(
                tenantId, workspaceId, actorId, contactId, "WHATSAPP", "MARKETING",
                ContactCsvSupport.cell(row, mapping.consentWhatsappMarketing()));
    }

    private void applyOneConsent(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID contactId,
            String channel,
            String purpose,
            String rawStatus
    ) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return;
        }
        String status = normalizeConsentStatus(rawStatus);
        consentService.change(
                tenantId,
                workspaceId,
                actorId,
                contactId,
                new ConsentModels.ConsentChangeCommand(
                        channel, purpose, status, "IMPORT", "CSV import column"
                )
        );
    }

    private static void validateConsentCells(
            ContactImportModels.ColumnMapping mapping,
            Map<String, String> row
    ) {
        if (mapping.consentEmailMarketing() != null && !mapping.consentEmailMarketing().isBlank()) {
            String value = ContactCsvSupport.cell(row, mapping.consentEmailMarketing());
            if (!value.isBlank()) {
                normalizeConsentStatus(value);
            }
        }
        if (mapping.consentWhatsappMarketing() != null && !mapping.consentWhatsappMarketing().isBlank()) {
            String value = ContactCsvSupport.cell(row, mapping.consentWhatsappMarketing());
            if (!value.isBlank()) {
                normalizeConsentStatus(value);
            }
        }
    }

    private static String normalizeConsentStatus(String raw) {
        String status = raw.trim().toUpperCase(Locale.ROOT);
        if (!"OPTED_IN".equals(status) && !"OPTED_OUT".equals(status)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Consent status must be OPTED_IN or OPTED_OUT");
        }
        return status;
    }

    private void assertConsentMappingAllowed(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            ContactImportModels.ColumnMapping mapping
    ) {
        if (!mapping.hasConsentColumns()) {
            return;
        }
        authorizationService.requireWorkspacePermission(
                actorId, tenantId, workspaceId, Permissions.CONTACTS_CONSENT_MANAGE);
    }

    private ContactModels.ContactCreateCommand toCreateCommand(
            ContactImportModels.ColumnMapping mapping,
            Map<String, String> row
    ) {
        String displayName = ContactCsvSupport.cell(row, mapping.displayName());
        List<ContactModels.ChannelInput> channels = new ArrayList<>();
        String email = ContactCsvSupport.cell(row, mapping.email());
        if (!email.isBlank()) {
            channels.add(new ContactModels.ChannelInput("EMAIL", email, true, null));
        }
        String phone = ContactCsvSupport.cell(row, mapping.phone());
        if (!phone.isBlank()) {
            channels.add(new ContactModels.ChannelInput("PHONE", phone, true, null));
        }
        Map<String, Object> customFields = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.customFields().entrySet()) {
            String value = ContactCsvSupport.cell(row, e.getValue());
            if (!value.isBlank()) {
                customFields.put(e.getKey(), value);
            }
        }
        return new ContactModels.ContactCreateCommand(displayName, channels, customFields);
    }

    private ContactImportModels.ColumnMapping requireMapping(ContactImportModels.ColumnMapping mapping) {
        if (mapping == null || mapping.displayName() == null || mapping.displayName().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "displayName column mapping is required");
        }
        if ((mapping.email() == null || mapping.email().isBlank())
                && (mapping.phone() == null || mapping.phone().isBlank())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "At least one of email or phone column mapping is required");
        }
        Map<String, String> custom = mapping.customFields() == null ? Map.of() : mapping.customFields();
        return new ContactImportModels.ColumnMapping(
                mapping.displayName().trim(),
                blankToNull(mapping.email()),
                blankToNull(mapping.phone()),
                custom,
                blankToNull(mapping.consentEmailMarketing()),
                blankToNull(mapping.consentWhatsappMarketing())
        );
    }

    private void validateHeaders(List<String> headers, ContactImportModels.ColumnMapping mapping) {
        requireHeader(headers, mapping.displayName());
        if (mapping.email() != null) {
            requireHeader(headers, mapping.email());
        }
        if (mapping.phone() != null) {
            requireHeader(headers, mapping.phone());
        }
        for (String header : mapping.customFields().values()) {
            requireHeader(headers, header);
        }
        if (mapping.consentEmailMarketing() != null) {
            requireHeader(headers, mapping.consentEmailMarketing());
        }
        if (mapping.consentWhatsappMarketing() != null) {
            requireHeader(headers, mapping.consentWhatsappMarketing());
        }
    }

    private static void requireHeader(List<String> headers, String name) {
        boolean found = headers.stream().anyMatch(h -> h.equalsIgnoreCase(name));
        if (!found) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV missing mapped column: " + name);
        }
    }

    private static String normalizePolicy(String conflictPolicy) {
        if (conflictPolicy == null || conflictPolicy.isBlank()) {
            return ContactImportModels.POLICY_SKIP;
        }
        String policy = conflictPolicy.trim().toUpperCase(Locale.ROOT);
        if (!policy.equals(ContactImportModels.POLICY_SKIP)
                && !policy.equals(ContactImportModels.POLICY_UPDATE)
                && !policy.equals(ContactImportModels.POLICY_CREATE_ANYWAY)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "conflictPolicy must be SKIP, UPDATE, or CREATE_ANYWAY");
        }
        return policy;
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "contacts.csv";
        }
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || name.length() > 255) {
            return "contacts.csv";
        }
        return name;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record RowValidation(String result, String errorCode, String errorMessage) {
    }
}
