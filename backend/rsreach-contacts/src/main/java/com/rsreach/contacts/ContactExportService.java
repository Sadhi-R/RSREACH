package com.rsreach.contacts;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactExportService {

    private static final Logger log = LoggerFactory.getLogger(ContactExportService.class);

    public static final int DEFAULT_SYNC_MAX_ROWS = 5_000;

    private final ContactService contactService;
    private final ContactImportRepository importRepository;
    private final ConsentRepository consentRepository;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;
    private final int syncMaxRows;

    public ContactExportService(
            ContactService contactService,
            ContactImportRepository importRepository,
            ConsentRepository consentRepository,
            OutboxRepository outboxRepository,
            AuditService auditService,
            @Value("${rsreach.contacts.export.sync-max-rows:5000}") int syncMaxRows
    ) {
        this.contactService = contactService;
        this.importRepository = importRepository;
        this.consentRepository = consentRepository;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.syncMaxRows = syncMaxRows > 0 ? syncMaxRows : DEFAULT_SYNC_MAX_ROWS;
    }

    @Transactional
    public ContactImportModels.ExportResult export(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            String query,
            String status
    ) {
        String normalizedStatus = normalizeStatus(status);
        int counted = countContacts(tenantId, workspaceId, query, normalizedStatus);
        if (counted > 50_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Export exceeds maximum of 50000 contacts");
        }
        if (counted > syncMaxRows) {
            UUID jobId = importRepository.insertExportJob(
                    tenantId, workspaceId, query, normalizedStatus, actorId);
            outboxRepository.append(OutboxAppendCommand.of(
                    ContactImportModels.AGGREGATE_EXPORT_JOB,
                    jobId,
                    ContactImportModels.EVENT_CONTACT_EXPORT_PROCESS,
                    "{\"jobId\":\"" + jobId + "\"}",
                    tenantId,
                    workspaceId,
                    null,
                    "CONTACT_EXPORT_PROCESS:" + jobId
            ));
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "contacts.exported", "contact_export_job", jobId.toString(),
                    "{\"rowCount\":" + counted + ",\"async\":true}"
            );
            return new ContactImportModels.ExportResult(
                    "contacts-export-" + jobId + ".csv", null, counted, true, jobId);
        }

        List<ContactModels.ContactView> contacts = loadAll(tenantId, workspaceId, query, normalizedStatus);
        String csv = buildCsv(tenantId, workspaceId, contacts);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contacts.exported", "workspace", workspaceId.toString(),
                "{\"rowCount\":" + contacts.size() + ",\"async\":false}"
        );
        return new ContactImportModels.ExportResult(
                "contacts-export.csv", csv, contacts.size(), false, null);
    }

    public void processExportJob(UUID tenantId, UUID workspaceId, UUID jobId) {
        ContactImportRepository.ExportJobDetail job = importRepository
                .findExportJobDetail(tenantId, workspaceId, jobId)
                .orElse(null);
        if (job == null) {
            return;
        }
        if ("COMPLETED".equals(job.status())) {
            return;
        }
        if (!"PENDING".equals(job.status()) && !"RUNNING".equals(job.status())) {
            log.info("export_process_skip jobId={} status={}", jobId, job.status());
            return;
        }
        try {
            importRepository.markExportRunning(jobId);
            List<ContactModels.ContactView> contacts = loadAll(
                    tenantId, workspaceId, job.filterQuery(), job.filterStatus());
            String csv = buildCsv(tenantId, workspaceId, contacts);
            importRepository.completeExportJob(jobId, csv, contacts.size());
        } catch (RuntimeException ex) {
            log.warn("export_process_failed jobId={}", jobId);
            importRepository.failExportJob(jobId, "Export processing failed");
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public ContactImportModels.ExportJobStatus getExportJobStatus(
            UUID tenantId, UUID workspaceId, UUID jobId
    ) {
        ContactImportRepository.ExportJobDetail job = importRepository
                .findExportJobDetail(tenantId, workspaceId, jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Export job not found"));
        return new ContactImportModels.ExportJobStatus(
                job.id(),
                job.status(),
                job.rowCount(),
                "COMPLETED".equals(job.status()) && job.csvContent() != null
        );
    }

    @Transactional(readOnly = true)
    public ContactImportModels.ExportResult getExportJob(UUID tenantId, UUID workspaceId, UUID jobId) {
        ContactImportRepository.ExportJobDetail job = importRepository
                .findExportJobDetail(tenantId, workspaceId, jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Export job not found"));
        if (!"COMPLETED".equals(job.status()) || job.csvContent() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "Export job is not ready");
        }
        return new ContactImportModels.ExportResult(
                "contacts-export-" + jobId + ".csv", job.csvContent(), job.rowCount(), true, jobId);
    }

    private int countContacts(UUID tenantId, UUID workspaceId, String query, String status) {
        int total = 0;
        int page = 0;
        while (true) {
            ContactModels.PageResult result = contactService.search(tenantId, workspaceId, query, status, page, 200);
            total += result.items().size();
            if (!result.hasNext()) {
                break;
            }
            page++;
            if (total > 50_000) {
                return total;
            }
        }
        return total;
    }

    private List<ContactModels.ContactView> loadAll(
            UUID tenantId, UUID workspaceId, String query, String status
    ) {
        List<ContactModels.ContactView> all = new ArrayList<>();
        int page = 0;
        while (true) {
            ContactModels.PageResult result = contactService.search(tenantId, workspaceId, query, status, page, 200);
            all.addAll(result.items());
            if (!result.hasNext()) {
                break;
            }
            page++;
            if (all.size() > 50_000) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Export exceeds maximum of 50000 contacts");
            }
        }
        return all;
    }

    private String buildCsv(UUID tenantId, UUID workspaceId, List<ContactModels.ContactView> contacts) {
        TreeSet<String> customKeys = new TreeSet<>();
        for (ContactModels.ContactView contact : contacts) {
            if (contact.customFields() != null) {
                customKeys.addAll(contact.customFields().keySet());
            }
        }
        List<String> headers = new ArrayList<>(List.of(
                "id", "display_name", "status", "email", "phone", "consent_email", "consent_whatsapp"
        ));
        headers.addAll(customKeys);

        StringBuilder sb = new StringBuilder();
        sb.append(ContactCsvSupport.toCsvLine(headers)).append('\n');
        for (ContactModels.ContactView contact : contacts) {
            Map<String, ConsentModels.ConsentView> consents = indexConsents(
                    consentRepository.listConsents(tenantId, workspaceId, contact.id()));
            List<String> cells = new ArrayList<>();
            cells.add(contact.id().toString());
            cells.add(contact.displayName());
            cells.add(contact.status());
            cells.add(contact.primaryEmail() == null ? "" : contact.primaryEmail());
            cells.add(contact.primaryPhone() == null ? "" : contact.primaryPhone());
            cells.add(consentStatus(consents, "EMAIL"));
            cells.add(consentStatus(consents, "WHATSAPP"));
            for (String key : customKeys) {
                Object value = contact.customFields() == null ? null : contact.customFields().get(key);
                cells.add(value == null ? "" : String.valueOf(value));
            }
            sb.append(ContactCsvSupport.toCsvLine(cells)).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, ConsentModels.ConsentView> indexConsents(List<ConsentModels.ConsentView> consents) {
        Map<String, ConsentModels.ConsentView> map = new java.util.HashMap<>();
        for (ConsentModels.ConsentView c : consents) {
            String key = c.channelType().toUpperCase(Locale.ROOT);
            if ("MARKETING".equalsIgnoreCase(c.purpose()) || !map.containsKey(key)) {
                map.put(key, c);
            }
        }
        return map;
    }

    private static String consentStatus(Map<String, ConsentModels.ConsentView> consents, String channel) {
        ConsentModels.ConsentView view = consents.get(channel);
        return view == null ? "" : view.status();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("ACTIVE") && !normalized.equals("ARCHIVED")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "status must be ACTIVE or ARCHIVED");
        }
        return normalized;
    }
}
