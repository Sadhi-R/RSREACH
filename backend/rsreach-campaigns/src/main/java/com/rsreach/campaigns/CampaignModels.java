package com.rsreach.campaigns;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CampaignModels {

    private CampaignModels() {
    }

    public record CampaignView(
            UUID id,
            String name,
            String channel,
            String purpose,
            String status,
            UUID audienceGroupId,
            Instant scheduledAt,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt
    ) {
    }

    public record CreateCampaignCommand(
            String name,
            String channel,
            String purpose,
            UUID audienceGroupId,
            String bodyText,
            Instant scheduledAt,
            String timezone
    ) {
    }

    public record CampaignPage(List<CampaignView> items, int page, int size, boolean hasNext) {
    }

    public record RecipientStats(int total, int pending, int queued, int sent, int skipped, int failed) {
    }
}
