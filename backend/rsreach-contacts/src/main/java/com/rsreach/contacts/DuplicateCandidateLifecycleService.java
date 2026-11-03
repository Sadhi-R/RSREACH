package com.rsreach.contacts;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle updates that must commit even when the calling request ends in CONFLICT.
 */
@Service
public class DuplicateCandidateLifecycleService {

    private final DuplicateCandidateRepository candidateRepository;

    public DuplicateCandidateLifecycleService(DuplicateCandidateRepository candidateRepository) {
        this.candidateRepository = candidateRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void supersedeOpen(UUID tenantId, UUID workspaceId, UUID candidateId) {
        candidateRepository.markSuperseded(tenantId, workspaceId, candidateId);
    }
}
