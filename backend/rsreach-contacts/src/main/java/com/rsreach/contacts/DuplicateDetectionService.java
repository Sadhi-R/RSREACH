package com.rsreach.contacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exact EMAIL/PHONE duplicate detection within a single home workspace.
 * Evidence stores channel ids only — never address strings.
 */
@Service
public class DuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionService.class);
    private static final int SCAN_BATCH = 200;

    private final DuplicateCandidateRepository candidateRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public DuplicateDetectionService(
            DuplicateCandidateRepository candidateRepository,
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate
    ) {
        this.candidateRepository = candidateRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void refreshForContact(UUID tenantId, UUID workspaceId, UUID contactId) {
        List<DuplicateCandidateRepository.ChannelMatchRow> matches =
                candidateRepository.findExactMatchesForContact(tenantId, workspaceId, contactId);
        Map<UUID, List<DuplicateCandidateRepository.ChannelMatchRow>> byOther = new LinkedHashMap<>();
        for (DuplicateCandidateRepository.ChannelMatchRow row : matches) {
            byOther.computeIfAbsent(row.otherContactId(), k -> new ArrayList<>()).add(row);
        }
        Set<UUID> paired = new LinkedHashSet<>();
        for (Map.Entry<UUID, List<DuplicateCandidateRepository.ChannelMatchRow>> entry : byOther.entrySet()) {
            upsertPair(tenantId, workspaceId, contactId, entry.getKey(), entry.getValue());
            paired.add(entry.getKey());
        }
        revalidateOpenCandidatesInvolving(tenantId, workspaceId, contactId, paired);
        log.debug("duplicate_refresh contactId={} pairs={}", contactId, paired.size());
    }

    @Transactional
    public int scanWorkspace(UUID tenantId, UUID workspaceId) {
        int processed = 0;
        UUID after = null;
        while (true) {
            List<UUID> batch = candidateRepository.listContactIdsForScan(
                    tenantId, workspaceId, SCAN_BATCH, after);
            if (batch.isEmpty()) {
                break;
            }
            for (UUID contactId : batch) {
                refreshForContact(tenantId, workspaceId, contactId);
                processed++;
            }
            after = batch.get(batch.size() - 1);
            if (batch.size() < SCAN_BATCH) {
                break;
            }
        }
        supersedeStaleOpenCandidates(tenantId, workspaceId);
        log.info("duplicate_scan_completed tenantId={} workspaceId={} contactsProcessed={}",
                tenantId, workspaceId, processed);
        return processed;
    }

    private void upsertPair(
            UUID tenantId,
            UUID workspaceId,
            UUID contactOne,
            UUID contactTwo,
            List<DuplicateCandidateRepository.ChannelMatchRow> rows
    ) {
        PostgresUuidOrdering.Pair pair = PostgresUuidOrdering.canonical(jdbcTemplate, contactOne, contactTwo);
        boolean email = false;
        boolean phone = false;
        List<DuplicateModels.MatchEvidence> evidence = new ArrayList<>();
        for (DuplicateCandidateRepository.ChannelMatchRow row : rows) {
            UUID channelA;
            UUID channelB;
            if (pair.low().equals(contactOne)) {
                channelA = row.channelIdSelf();
                channelB = row.channelIdOther();
            } else {
                channelA = row.channelIdOther();
                channelB = row.channelIdSelf();
            }
            String matchType = "EMAIL".equals(row.channelType()) ? "EXACT_EMAIL" : "EXACT_PHONE";
            if ("EMAIL".equals(row.channelType())) {
                email = true;
            } else {
                phone = true;
            }
            evidence.add(new DuplicateModels.MatchEvidence(
                    matchType, row.channelType(), channelA, channelB));
        }
        List<String> matchTypes = new ArrayList<>();
        int score;
        String confidence;
        if (email && phone) {
            matchTypes.add("EXACT_EMAIL_AND_PHONE");
            score = 100;
            confidence = "VERY_HIGH";
        } else if (email) {
            matchTypes.add("EXACT_EMAIL");
            score = 80;
            confidence = "HIGH";
        } else {
            matchTypes.add("EXACT_PHONE");
            score = 80;
            confidence = "HIGH";
        }
        String evidenceJson = DuplicateCandidateRepository.buildEvidenceJson(evidence, score);
        candidateRepository.upsertOpenOrRefresh(
                tenantId, workspaceId, pair.low(), pair.high(),
                matchTypes, confidence, score, evidenceJson
        );
    }

    private void revalidateOpenCandidatesInvolving(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            Set<UUID> stillPaired
    ) {
        for (UUID candidateId : candidateRepository.listOpenCandidateIds(tenantId, workspaceId)) {
            candidateRepository.findRow(tenantId, workspaceId, candidateId).ifPresent(row -> {
                if (!row.contactIdA().equals(contactId) && !row.contactIdB().equals(contactId)) {
                    return;
                }
                UUID other = row.contactIdA().equals(contactId) ? row.contactIdB() : row.contactIdA();
                if (!stillPaired.contains(other) || !evidenceStillValid(tenantId, workspaceId, row)) {
                    candidateRepository.markSuperseded(tenantId, workspaceId, candidateId);
                }
            });
        }
    }

    private void supersedeStaleOpenCandidates(UUID tenantId, UUID workspaceId) {
        for (UUID candidateId : candidateRepository.listOpenCandidateIds(tenantId, workspaceId)) {
            candidateRepository.findRow(tenantId, workspaceId, candidateId).ifPresent(row -> {
                if (!evidenceStillValid(tenantId, workspaceId, row)) {
                    candidateRepository.markSuperseded(tenantId, workspaceId, candidateId);
                }
            });
        }
    }

    boolean evidenceStillValid(
            UUID tenantId,
            UUID workspaceId,
            DuplicateCandidateRepository.CandidateRow row
    ) {
        if (row.evidence().isEmpty()) {
            return false;
        }
        for (DuplicateModels.MatchEvidence match : row.evidence()) {
            if (isMatchValid(tenantId, workspaceId, row.contactIdA(), row.contactIdB(), match)) {
                return true;
            }
        }
        return false;
    }

    boolean isMatchValid(
            UUID tenantId,
            UUID workspaceId,
            UUID contactA,
            UUID contactB,
            DuplicateModels.MatchEvidence match
    ) {
        var chA = candidateRepository.findChannel(tenantId, workspaceId, match.channelIdA());
        var chB = candidateRepository.findChannel(tenantId, workspaceId, match.channelIdB());
        if (chA.isEmpty() || chB.isEmpty()) {
            return false;
        }
        DuplicateCandidateRepository.ChannelLookup a = chA.get();
        DuplicateCandidateRepository.ChannelLookup b = chB.get();
        if (!a.contactId().equals(contactA) || !b.contactId().equals(contactB)) {
            return false;
        }
        if (!a.channelType().equals(b.channelType()) || !a.channelType().equals(match.channelType())) {
            return false;
        }
        if (!a.valueNormalized().equals(b.valueNormalized())) {
            return false;
        }
        return isMatchableStatus(a.status()) && isMatchableStatus(b.status());
    }

    private static boolean isMatchableStatus(String status) {
        return "ACTIVE".equals(status) || "ARCHIVED".equals(status);
    }
}
