package com.rsreach.contacts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MergeModels {

    private MergeModels() {
    }

    public record PrimaryChoice(String channelType, UUID channelId) {
    }

    public record FieldChoice(String fieldKey, String source) {
        // source: SURVIVOR | LOSER
    }

    public record PreviewCommand(
            UUID candidateId,
            UUID survivorContactId,
            UUID loserContactId,
            List<FieldChoice> fieldChoices,
            List<PrimaryChoice> primaryChoices
    ) {
    }

    public record ExecuteCommand(
            UUID candidateId,
            UUID survivorContactId,
            UUID loserContactId,
            List<FieldChoice> fieldChoices,
            List<PrimaryChoice> primaryChoices,
            String previewHash,
            boolean confirm
    ) {
    }

    public record ChannelDisposition(
            UUID channelId,
            UUID contactId,
            String channelType,
            String valueNormalized,
            String valueRaw,
            String status,
            String action,
            boolean primaryAfterMerge
    ) {
        // action: RETAINED | MOVED | DEDUPED_ARCHIVED | LEFT_ARCHIVED | LEFT_INVALID
    }

    public record IntroducedAddress(
            String channelType,
            String storageType,
            UUID channelId,
            String valueNormalized,
            String valueRaw
    ) {
    }

    public record ConsentCellDiff(
            String channelType,
            String purpose,
            String survivorBefore,
            String loserBefore,
            String after,
            boolean changed,
            boolean requiresFreshConsent
    ) {
    }

    public record FieldDiff(
            String fieldKey,
            Object survivorValue,
            Object loserValue,
            Object resultValue,
            String source
    ) {
    }

    public record GroupChange(
            UUID groupId,
            String groupType,
            String action
    ) {
        // action: UNION_MEMBER | REMOVE_LOSER_MEMBER | DYNAMIC_REFRESH
    }

    public record ShareRevokePreview(
            UUID shareId,
            UUID sourceWorkspaceId,
            UUID targetWorkspaceId,
            String permission
    ) {
    }

    public record BlockingConflict(String code, String message) {
    }

    public record MergePreview(
            UUID candidateId,
            UUID survivorContactId,
            UUID loserContactId,
            List<FieldDiff> fields,
            List<ChannelDisposition> channels,
            List<IntroducedAddress> introducedAddresses,
            List<ConsentCellDiff> consent,
            List<GroupChange> groups,
            List<ShareRevokePreview> sharesToRevoke,
            List<BlockingConflict> blockingConflicts,
            boolean mergeAllowed,
            String previewHash,
            boolean stale
    ) {
    }

    public record MergeItemView(
            UUID id,
            String itemType,
            String itemKey,
            String action,
            Map<String, Object> before,
            Map<String, Object> after
    ) {
    }

    public record MergeView(
            UUID id,
            UUID workspaceId,
            UUID survivorContactId,
            UUID loserContactId,
            String status,
            String idempotencyKey,
            String requestHash,
            String previewHash,
            UUID actorId,
            Instant completedAt,
            List<MergeItemView> items
    ) {
    }

    public record MergePage(List<MergeView> items, int page, int size, boolean hasNext) {
    }
}
