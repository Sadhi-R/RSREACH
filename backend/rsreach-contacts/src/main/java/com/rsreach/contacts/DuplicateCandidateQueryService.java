package com.rsreach.contacts;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DuplicateCandidateQueryService {

    private final DuplicateCandidateRepository candidateRepository;
    private final DuplicateDetectionService detectionService;
    private final ContactRepository contactRepository;

    public DuplicateCandidateQueryService(
            DuplicateCandidateRepository candidateRepository,
            DuplicateDetectionService detectionService,
            ContactRepository contactRepository
    ) {
        this.candidateRepository = candidateRepository;
        this.detectionService = detectionService;
        this.contactRepository = contactRepository;
    }

    @Transactional(readOnly = true)
    public DuplicateModels.CandidatePage list(
            UUID tenantId,
            UUID workspaceId,
            String status,
            UUID contactId,
            int page,
            int size
    ) {
        String normalizedStatus = normalizeStatusFilter(status);
        return candidateRepository.list(tenantId, workspaceId, normalizedStatus, contactId, page, size);
    }

    @Transactional
    public DuplicateModels.CandidateDetail get(UUID tenantId, UUID workspaceId, UUID candidateId) {
        DuplicateCandidateRepository.CandidateRow row = candidateRepository.findRow(tenantId, workspaceId, candidateId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Duplicate candidate not found"));

        boolean valid = detectionService.evidenceStillValid(tenantId, workspaceId, row);
        boolean stale = !valid;
        if ("OPEN".equals(row.status()) && stale) {
            candidateRepository.markSuperseded(tenantId, workspaceId, candidateId);
            row = candidateRepository.findRow(tenantId, workspaceId, candidateId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Duplicate candidate not found"));
        }

        ContactModels.ContactView contactA = contactRepository.findById(tenantId, workspaceId, row.contactIdA())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        ContactModels.ContactView contactB = contactRepository.findById(tenantId, workspaceId, row.contactIdB())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));

        List<DuplicateModels.ResolvedMatch> resolved = new ArrayList<>();
        for (DuplicateModels.MatchEvidence match : row.evidence()) {
            boolean matchValid = detectionService.isMatchValid(
                    tenantId, workspaceId, row.contactIdA(), row.contactIdB(), match);
            var chA = candidateRepository.findChannel(tenantId, workspaceId, match.channelIdA());
            var chB = candidateRepository.findChannel(tenantId, workspaceId, match.channelIdB());
            resolved.add(new DuplicateModels.ResolvedMatch(
                    match.matchType(),
                    match.channelType(),
                    match.channelIdA(),
                    match.channelIdB(),
                    chA.map(DuplicateCandidateRepository.ChannelLookup::valueNormalized).orElse(null),
                    chA.map(DuplicateCandidateRepository.ChannelLookup::valueRaw).orElse(null),
                    chB.map(DuplicateCandidateRepository.ChannelLookup::valueRaw).orElse(null),
                    matchValid
            ));
        }

        return new DuplicateModels.CandidateDetail(
                row.id(),
                workspaceId,
                row.contactIdA(),
                row.contactIdB(),
                contactA,
                contactB,
                row.matchTypes(),
                row.confidence(),
                row.score(),
                row.mergeAllowed(),
                row.status(),
                resolved,
                row.detectedAt(),
                row.lastDetectedAt(),
                row.dismissedAt(),
                row.dismissReason(),
                stale || "SUPERSEDED".equals(row.status())
        );
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return "OPEN";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "DISMISSED", "MERGED", "SUPERSEDED").contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid candidate status filter");
        }
        return normalized;
    }
}
