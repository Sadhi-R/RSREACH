package com.rsreach.contacts;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.common.event.WorkspaceDomainEvent;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactGroupService {

    private final ContactGroupRepository groupRepository;
    private final ContactRepository contactRepository;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public ContactGroupService(
            ContactGroupRepository groupRepository,
            ContactRepository contactRepository,
            OutboxRepository outboxRepository,
            AuditService auditService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.groupRepository = groupRepository;
        this.contactRepository = contactRepository;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ContactGroupModels.GroupView create(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            ContactGroupModels.GroupCreateCommand command
    ) {
        String name = requireName(command.name());
        String type = requireType(command.groupType());
        String slug = slugify(name);
        if (groupRepository.slugExists(tenantId, workspaceId, slug, null)) {
            slug = slug + "-" + UUID.randomUUID().toString().substring(0, 8);
        }

        String filterJson = null;
        if ("DYNAMIC".equals(type)) {
            List<ContactModels.CustomFieldDef> defs = contactRepository.listFieldDefs(tenantId, workspaceId, true);
            GroupFilterModels.FilterDefinition filter =
                    GroupFilterValidator.parseAndValidate(command.filterDefinition(), defs);
            filterJson = GroupFilterValidator.toJson(filter);
        } else if (command.filterDefinition() != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "STATIC groups cannot have filterDefinition");
        }

        try {
            UUID id = groupRepository.insert(tenantId, workspaceId, name, slug, type, filterJson, actorId);
            if ("DYNAMIC".equals(type)) {
                enqueueRefresh(tenantId, workspaceId, id);
            }
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "contact.group.created", "contact_group", id.toString(),
                    "{\"groupType\":\"" + type + "\"}"
            );
            return groupRepository.findById(tenantId, workspaceId, id)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Group create failed"));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Contact group slug already exists");
        }
    }

    @Transactional(readOnly = true)
    public ContactGroupModels.GroupView get(UUID tenantId, UUID workspaceId, UUID groupId) {
        return groupRepository.findById(tenantId, workspaceId, groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact group not found"));
    }

    @Transactional(readOnly = true)
    public ContactGroupModels.GroupPage search(
            UUID tenantId,
            UUID workspaceId,
            String query,
            String status,
            String groupType,
            int page,
            int size
    ) {
        return groupRepository.search(tenantId, workspaceId, query, status, groupType, page, size);
    }

    @Transactional
    public ContactGroupModels.GroupView update(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID groupId,
            ContactGroupModels.GroupUpdateCommand command
    ) {
        ContactGroupModels.GroupView existing = requireHomeGroup(tenantId, workspaceId, groupId);
        if (!"ACTIVE".equals(existing.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only ACTIVE groups can be updated");
        }
        String name = requireName(command.name());
        String filterJson;
        boolean enqueue = false;
        if ("DYNAMIC".equals(existing.groupType())) {
            List<ContactModels.CustomFieldDef> defs = contactRepository.listFieldDefs(tenantId, workspaceId, true);
            Object rawFilter = command.filterDefinition() != null
                    ? command.filterDefinition()
                    : existing.filterDefinition();
            GroupFilterModels.FilterDefinition filter = GroupFilterValidator.parseAndValidate(rawFilter, defs);
            filterJson = GroupFilterValidator.toJson(filter);
            enqueue = true;
        } else {
            if (command.filterDefinition() != null) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Cannot convert STATIC group to DYNAMIC via filterDefinition"
                );
            }
            filterJson = null;
        }

        groupRepository.update(tenantId, workspaceId, groupId, name, filterJson, enqueue, actorId);
        if (enqueue) {
            enqueueRefresh(tenantId, workspaceId, groupId);
        }
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.group.updated", "contact_group", groupId.toString(),
                "{\"filterUpdated\":" + enqueue + "}"
        );
        return get(tenantId, workspaceId, groupId);
    }

    @Transactional
    public ContactGroupModels.GroupView archive(UUID tenantId, UUID workspaceId, UUID actorId, UUID groupId) {
        requireHomeGroup(tenantId, workspaceId, groupId);
        groupRepository.archive(tenantId, workspaceId, groupId, actorId);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.group.archived", "contact_group", groupId.toString(), "{}"
        );
        return get(tenantId, workspaceId, groupId);
    }

    @Transactional
    public ContactGroupModels.MembershipChangeResult addMembers(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID groupId,
            List<UUID> contactIds
    ) {
        ContactGroupModels.GroupView group = requireHomeGroup(tenantId, workspaceId, groupId);
        if (!"ACTIVE".equals(group.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Cannot modify members of an archived group");
        }
        if ("DYNAMIC".equals(group.groupType())) {
            // Manual members are allowed on dynamic groups (union with materialized set).
        }
        List<UUID> unique = dedupeIds(contactIds);
        if (unique.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "contactIds is required");
        }
        int valid = groupRepository.countValidContacts(tenantId, workspaceId, unique);
        if (valid != unique.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more contacts are invalid for this workspace");
        }
        int added = groupRepository.addManualMembers(tenantId, workspaceId, groupId, unique);
        int skipped = unique.size() - added;
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.group.members_changed", "contact_group", groupId.toString(),
                "{\"added\":" + added + ",\"removed\":0,\"skipped\":" + skipped + "}"
        );
        // Publish CONTACT_GROUP_ADDED for each contact so automations can react.
        // Published inside the transaction so @TransactionalEventListener(AFTER_COMMIT) fires
        // only once the membership write has committed.
        String groupPayload = "{\"groupId\":\"" + groupId + "\"}";
        for (UUID contactId : unique) {
            eventPublisher.publishEvent(
                    new WorkspaceDomainEvent("CONTACT_GROUP_ADDED", tenantId, workspaceId, contactId, groupPayload));
        }
        return new ContactGroupModels.MembershipChangeResult(added, 0, skipped);
    }

    @Transactional
    public ContactGroupModels.MembershipChangeResult removeMembers(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID groupId,
            List<UUID> contactIds
    ) {
        ContactGroupModels.GroupView group = requireHomeGroup(tenantId, workspaceId, groupId);
        if (!"ACTIVE".equals(group.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Cannot modify members of an archived group");
        }
        List<UUID> unique = dedupeIds(contactIds);
        if (unique.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "contactIds is required");
        }
        int removed = groupRepository.removeMembers(tenantId, workspaceId, groupId, unique);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.group.members_changed", "contact_group", groupId.toString(),
                "{\"added\":0,\"removed\":" + removed + ",\"skipped\":" + (unique.size() - removed) + "}"
        );
        return new ContactGroupModels.MembershipChangeResult(0, removed, unique.size() - removed);
    }

    @Transactional
    public ContactGroupModels.MembershipChangeResult bulkMembers(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID groupId,
            List<UUID> addIds,
            List<UUID> removeIds
    ) {
        ContactGroupModels.MembershipChangeResult added =
                addIds == null || addIds.isEmpty()
                        ? new ContactGroupModels.MembershipChangeResult(0, 0, 0)
                        : addMembers(tenantId, workspaceId, actorId, groupId, addIds);
        ContactGroupModels.MembershipChangeResult removed =
                removeIds == null || removeIds.isEmpty()
                        ? new ContactGroupModels.MembershipChangeResult(0, 0, 0)
                        : removeMembers(tenantId, workspaceId, actorId, groupId, removeIds);
        return new ContactGroupModels.MembershipChangeResult(
                added.added(), removed.removed(), added.skipped() + removed.skipped()
        );
    }

    @Transactional(readOnly = true)
    public ContactGroupModels.MemberPage listMembers(
            UUID tenantId,
            UUID workspaceId,
            UUID groupId,
            int page,
            int size
    ) {
        ContactGroupModels.GroupView group = get(tenantId, workspaceId, groupId);
        // Membership rows live in the home workspace even when the group is shared READ into another workspace.
        return groupRepository.listMembers(tenantId, group.workspaceId(), groupId, page, size);
    }

    @Transactional(readOnly = true)
    public ContactGroupModels.PreviewResult preview(
            UUID tenantId,
            UUID workspaceId,
            Object filterDefinition,
            int page,
            int size
    ) {
        List<ContactModels.CustomFieldDef> defs = contactRepository.listFieldDefs(tenantId, workspaceId, true);
        GroupFilterModels.FilterDefinition filter = GroupFilterValidator.parseAndValidate(filterDefinition, defs);
        GroupFilterQueryBuilder.BuiltFilter built = GroupFilterQueryBuilder.build(filter);
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        long count = groupRepository.countMatching(tenantId, workspaceId, built);
        List<ContactModels.ContactView> items =
                groupRepository.listMatchingContacts(tenantId, workspaceId, built, limit + 1, offset);
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new ContactGroupModels.PreviewResult(count, items, page, limit, hasNext);
    }

    @Transactional
    public ContactGroupModels.GroupView requestRefresh(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID groupId
    ) {
        ContactGroupModels.GroupView group = requireHomeGroup(tenantId, workspaceId, groupId);
        if (!"DYNAMIC".equals(group.groupType())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only DYNAMIC groups can be refreshed");
        }
        if (!"ACTIVE".equals(group.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Cannot refresh an archived group");
        }
        groupRepository.markMaterializationPending(tenantId, workspaceId, groupId);
        enqueueRefresh(tenantId, workspaceId, groupId);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.group.refresh_requested", "contact_group", groupId.toString(), "{}"
        );
        return get(tenantId, workspaceId, groupId);
    }

    @Transactional
    public int enqueueStaleRefreshes(int limit) {
        int enqueued = 0;
        for (UUID groupId : groupRepository.listStaleDynamicGroupIds(limit)) {
            groupRepository.findGroupScope(groupId).ifPresent(scope -> {
                groupRepository.markMaterializationPending(scope.tenantId(), scope.workspaceId(), groupId);
                enqueueRefresh(scope.tenantId(), scope.workspaceId(), groupId);
            });
            enqueued++;
        }
        return enqueued;
    }

    private void enqueueRefresh(UUID tenantId, UUID workspaceId, UUID groupId) {
        outboxRepository.append(OutboxAppendCommand.of(
                ContactGroupModels.AGGREGATE_CONTACT_GROUP,
                groupId,
                ContactGroupModels.EVENT_CONTACT_GROUP_REFRESH,
                "{\"groupId\":\"" + groupId + "\"}",
                tenantId,
                workspaceId,
                null,
                "CONTACT_GROUP_REFRESH:" + groupId + ":" + UUID.randomUUID()
        ));
    }

    private ContactGroupModels.GroupView requireHomeGroup(UUID tenantId, UUID workspaceId, UUID groupId) {
        return groupRepository.findByIdHomeOnly(tenantId, workspaceId, groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact group not found"));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name is too long");
        }
        return trimmed;
    }

    private static String requireType(String type) {
        if (type == null || type.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "groupType is required");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("STATIC") && !normalized.equals("DYNAMIC")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "groupType must be STATIC or DYNAMIC");
        }
        return normalized;
    }

    private static String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "group";
        }
        if (slug.length() > 100) {
            slug = slug.substring(0, 100);
        }
        return slug;
    }

    private static List<UUID> dedupeIds(List<UUID> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<UUID> unique = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                unique.add(id);
            }
        }
        return List.copyOf(unique);
    }
}
