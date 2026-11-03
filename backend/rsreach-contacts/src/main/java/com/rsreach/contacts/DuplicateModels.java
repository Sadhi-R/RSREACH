package com.rsreach.contacts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DuplicateModels {

    public static final String EVENT_CONTACT_DUPLICATE_SCAN = "CONTACT_DUPLICATE_SCAN";
    public static final String AGGREGATE_WORKSPACE = "workspace";

    private DuplicateModels() {
    }

    public record MatchEvidence(
            String matchType,
            String channelType,
            UUID channelIdA,
            UUID channelIdB
    ) {
    }

    public record ResolvedMatch(
            String matchType,
            String channelType,
            UUID channelIdA,
            UUID channelIdB,
            String valueNormalized,
            String valueRawA,
            String valueRawB,
            boolean stillValid
    ) {
    }

    public record CandidateSummary(
            UUID id,
            UUID contactIdA,
            UUID contactIdB,
            List<String> matchTypes,
            String confidence,
            int score,
            boolean mergeAllowed,
            String status,
            Instant detectedAt,
            Instant lastDetectedAt,
            boolean stale
    ) {
    }

    public record CandidateDetail(
            UUID id,
            UUID workspaceId,
            UUID contactIdA,
            UUID contactIdB,
            ContactModels.ContactView contactA,
            ContactModels.ContactView contactB,
            List<String> matchTypes,
            String confidence,
            int score,
            boolean mergeAllowed,
            String status,
            List<ResolvedMatch> matches,
            Instant detectedAt,
            Instant lastDetectedAt,
            Instant dismissedAt,
            String dismissReason,
            boolean stale
    ) {
    }

    public record CandidatePage(List<CandidateSummary> items, int page, int size, boolean hasNext) {
    }

    public record ScanAccepted(UUID scanId, String status) {
    }

    public record DismissCommand(String reason) {
    }
}
