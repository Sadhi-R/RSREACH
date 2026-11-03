package com.rsreach.contacts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContactModels {

    private ContactModels() {
    }

    public record ChannelInput(String channelType, String value, Boolean primary, String label) {
    }

    public record ContactCreateCommand(
            String displayName,
            List<ChannelInput> channels,
            Map<String, Object> customFields
    ) {
    }

    public record ContactUpdateCommand(
            String displayName,
            List<ChannelInput> channels,
            Map<String, Object> customFields
    ) {
    }

    public record ChannelView(
            UUID id,
            String channelType,
            String value,
            String valueNormalized,
            boolean primary,
            String label,
            String status
    ) {
    }

    public record ContactView(
            UUID id,
            UUID tenantId,
            UUID homeWorkspaceId,
            String displayName,
            String status,
            String primaryEmail,
            String primaryPhone,
            Map<String, Object> customFields,
            List<ChannelView> channels,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt,
            String access
    ) {
        public ContactView {
            if (channels == null) {
                channels = List.of();
            }
        }

        public ContactView withAccess(String accessLevel) {
            return new ContactView(
                    id, tenantId, homeWorkspaceId, displayName, status, primaryEmail, primaryPhone,
                    customFields, channels, createdAt, updatedAt, archivedAt, accessLevel
            );
        }
    }

    public record PageResult(List<ContactView> items, int page, int size, boolean hasNext) {
    }

    public record CustomFieldDef(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String fieldKey,
            String label,
            String dataType,
            boolean required,
            boolean active
    ) {
    }

    public record CustomFieldDefCreateCommand(
            String fieldKey,
            String label,
            String dataType,
            boolean required
    ) {
    }
}
