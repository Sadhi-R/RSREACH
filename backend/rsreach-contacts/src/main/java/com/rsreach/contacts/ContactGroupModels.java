package com.rsreach.contacts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContactGroupModels {

    private ContactGroupModels() {
    }

    public record GroupCreateCommand(
            String name,
            String groupType,
            Object filterDefinition
    ) {
    }

    public record GroupUpdateCommand(
            String name,
            Object filterDefinition
    ) {
    }

    public record GroupView(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String name,
            String slug,
            String groupType,
            Map<String, Object> filterDefinition,
            String status,
            String materializationStatus,
            String materializationError,
            Instant materializedAt,
            long memberCount,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt
    ) {
    }

    public record GroupPage(List<GroupView> items, int page, int size, boolean hasNext) {
    }

    public record MemberView(
            UUID contactId,
            String displayName,
            String status,
            String source,
            String primaryEmail,
            String primaryPhone,
            Instant createdAt
    ) {
    }

    public record MemberPage(List<MemberView> items, int page, int size, boolean hasNext, long totalEstimate) {
    }

    public record PreviewResult(long matchCount, List<ContactModels.ContactView> items, int page, int size, boolean hasNext) {
    }

    public record MembershipChangeResult(int added, int removed, int skipped) {
    }

    public static final String EVENT_CONTACT_GROUP_REFRESH = "CONTACT_GROUP_REFRESH";
    public static final String AGGREGATE_CONTACT_GROUP = "contact_group";
}
