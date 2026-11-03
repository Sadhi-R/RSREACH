package com.rsreach.contacts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContactShareModels {

    public static final String RESOURCE_CONTACT = "CONTACT";
    public static final String RESOURCE_CONTACT_GROUP = "CONTACT_GROUP";
    public static final String PERM_READ = "READ";
    public static final String PERM_WRITE = "WRITE";

    private ContactShareModels() {
    }

    public record ShareView(
            UUID id,
            UUID sourceWorkspaceId,
            UUID targetWorkspaceId,
            String resourceType,
            UUID resourceId,
            String permission,
            Instant createdAt,
            Instant revokedAt
    ) {
    }

    public record CreateShareCommand(UUID targetWorkspaceId, String permission) {
    }

    public record SharePage(List<ShareView> items) {
    }

    public enum AccessLevel {
        HOME,
        READ,
        WRITE
    }

    public record Access(
            AccessLevel level,
            UUID homeWorkspaceId
    ) {
        public boolean canRead() {
            return level != null;
        }

        public boolean canWrite() {
            return level == AccessLevel.HOME || level == AccessLevel.WRITE;
        }

        public boolean isHome() {
            return level == AccessLevel.HOME;
        }
    }
}
