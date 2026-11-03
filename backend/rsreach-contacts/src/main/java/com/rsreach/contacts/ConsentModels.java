package com.rsreach.contacts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ConsentModels {

    public static final Set<String> CHANNELS = Set.of("EMAIL", "WHATSAPP");
    public static final Set<String> PURPOSES = Set.of("MARKETING", "TRANSACTIONAL", "SERVICE");
    public static final Set<String> STATUSES = Set.of("OPTED_IN", "OPTED_OUT", "UNKNOWN");
    public static final Set<String> SOURCES = Set.of("MANUAL", "IMPORT", "FORM", "API", "PROVIDER_WEBHOOK");

    private ConsentModels() {
    }

    public record ConsentView(
            UUID id,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose,
            String status,
            String source,
            Map<String, Object> evidence,
            Instant capturedAt,
            Instant updatedAt,
            UUID updatedBy
    ) {
    }

    public record ConsentEventView(
            UUID id,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose,
            String previousStatus,
            String newStatus,
            String eventType,
            String source,
            Map<String, Object> evidence,
            UUID actorId,
            Instant occurredAt
    ) {
    }

    public record ConsentChangeCommand(
            String channelType,
            String purpose,
            String status,
            String source,
            String evidenceNote
    ) {
    }

    public record ConsentMatrix(UUID contactId, UUID workspaceId, List<ConsentView> items) {
    }

    public record ConsentHistoryPage(List<ConsentEventView> items, int page, int size, boolean hasNext) {
    }

    public record SuppressionView(
            UUID id,
            String channelType,
            String valueRaw,
            String valueNormalized,
            String reason,
            Instant createdAt,
            Instant releasedAt,
            UUID createdBy,
            UUID releasedBy
    ) {
    }

    public record SuppressionCreateCommand(String channelType, String value, String reason) {
    }

    public record SuppressionPage(List<SuppressionView> items, int page, int size, boolean hasNext) {
    }

    /**
     * Explicit-address eligibility request for future send paths.
     * Callers must pass the exact recipient address; primary-address fallback is not used.
     */
    public record EligibilityRequest(String channelType, String purpose, String address) {
    }

    public record EligibilityResult(
            boolean eligible,
            String reasonCode,
            String message,
            String consentStatus,
            boolean suppressed,
            String evaluatedAddress
    ) {
        public EligibilityResult(
                boolean eligible,
                String reasonCode,
                String message,
                String consentStatus,
                boolean suppressed
        ) {
            this(eligible, reasonCode, message, consentStatus, suppressed, null);
        }
    }
}
