package com.rsreach.contacts;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DuplicateCandidateService {

    private final DuplicateCandidateRepository candidateRepository;
    private final DuplicateCandidateQueryService queryService;
    private final DuplicateDetectionService detectionService;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;

    public DuplicateCandidateService(
            DuplicateCandidateRepository candidateRepository,
            DuplicateCandidateQueryService queryService,
            DuplicateDetectionService detectionService,
            OutboxRepository outboxRepository,
            AuditService auditService
    ) {
        this.candidateRepository = candidateRepository;
        this.queryService = queryService;
        this.detectionService = detectionService;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
    }

    @Transactional
    public DuplicateModels.ScanAccepted enqueueWorkspaceScan(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId
    ) {
        var pending = candidateRepository.findPendingScanOutboxId(tenantId, workspaceId);
        if (pending.isPresent()) {
            return new DuplicateModels.ScanAccepted(pending.get(), "PENDING");
        }
        UUID scanId = UUID.randomUUID();
        // Keep under outbox_events.idempotency_key VARCHAR(128).
        UUID outboxId = outboxRepository.append(OutboxAppendCommand.of(
                DuplicateModels.AGGREGATE_WORKSPACE,
                workspaceId,
                DuplicateModels.EVENT_CONTACT_DUPLICATE_SCAN,
                "{\"scanId\":\"" + scanId + "\"}",
                tenantId,
                workspaceId,
                null,
                "DUP_SCAN:" + scanId
        ));
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.duplicate.scan_requested", "workspace", workspaceId.toString(),
                "{\"scanId\":\"" + outboxId + "\"}"
        );
        return new DuplicateModels.ScanAccepted(outboxId, "PENDING");
    }

    @Transactional
    public DuplicateModels.CandidateDetail dismiss(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID candidateId,
            DuplicateModels.DismissCommand command
    ) {
        if (command == null || command.reason() == null || command.reason().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "dismiss reason is required");
        }
        String reason = command.reason().trim();
        if (reason.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "dismiss reason is too long");
        }
        candidateRepository.findRowForUpdate(tenantId, workspaceId, candidateId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Duplicate candidate not found"));
        candidateRepository.dismiss(tenantId, workspaceId, candidateId, actorId, reason);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.duplicate.dismissed", "contact_duplicate_candidate", candidateId.toString(),
                "{}"
        );
        return queryService.get(tenantId, workspaceId, candidateId);
    }

    @Transactional
    public DuplicateModels.CandidateDetail reopen(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID candidateId
    ) {
        DuplicateCandidateRepository.CandidateRow row = candidateRepository.findRowForUpdate(tenantId, workspaceId, candidateId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Duplicate candidate not found"));
        if (!"DISMISSED".equals(row.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only DISMISSED candidates can be reopened");
        }
        if (!detectionService.evidenceStillValid(tenantId, workspaceId, row)) {
            candidateRepository.markSupersededFromDismissed(tenantId, workspaceId, candidateId);
            throw new ApiException(ErrorCode.CONFLICT, "Candidate match is no longer valid");
        }
        candidateRepository.reopen(tenantId, workspaceId, candidateId);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.duplicate.reopened", "contact_duplicate_candidate", candidateId.toString(),
                "{}"
        );
        return queryService.get(tenantId, workspaceId, candidateId);
    }

    @Transactional
    public void processScan(UUID tenantId, UUID workspaceId) {
        detectionService.scanWorkspace(tenantId, workspaceId);
    }
}
