package com.rsreach.contacts;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContactMergeService {

    private static final Logger log = LoggerFactory.getLogger(ContactMergeService.class);

    private final MergePreviewService previewService;
    private final ContactRepository contactRepository;
    private final ContactMergeRepository mergeRepository;
    private final ConsentRepository consentRepository;
    private final ContactGroupRepository groupRepository;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;
    private final PostgresUuidOrderingSupport uuidOrdering;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<MergeExecutionProbe> executionProbe;

    public ContactMergeService(
            MergePreviewService previewService,
            ContactRepository contactRepository,
            ContactMergeRepository mergeRepository,
            ConsentRepository consentRepository,
            ContactGroupRepository groupRepository,
            OutboxRepository outboxRepository,
            AuditService auditService,
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectProvider<MergeExecutionProbe> executionProbe
    ) {
        this.previewService = previewService;
        this.contactRepository = contactRepository;
        this.mergeRepository = mergeRepository;
        this.consentRepository = consentRepository;
        this.groupRepository = groupRepository;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.uuidOrdering = new PostgresUuidOrderingSupport(jdbcTemplate);
        this.transactionTemplate = transactionTemplate;
        this.executionProbe = executionProbe;
    }

    @Transactional(readOnly = true)
    public MergeModels.MergePage history(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            int page,
            int size
    ) {
        return mergeRepository.list(tenantId, workspaceId, contactId, page, size);
    }

    @Transactional(readOnly = true)
    public MergeModels.MergeView get(UUID tenantId, UUID workspaceId, UUID mergeId) {
        return mergeRepository.findById(tenantId, workspaceId, mergeId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Merge not found"));
    }

    /**
     * Facade: opens one DB transaction via {@link TransactionTemplate}. Unique-key races
     * roll back all local mutations, then replay the committed merge when the key matches.
     */
    public MergeModels.MergeView execute(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            String idempotencyKey,
            MergeModels.ExecuteCommand command
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key is required");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 128) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key is too long");
        }
        if (command == null || !command.confirm()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "confirm must be true");
        }
        if (command.previewHash() == null || command.previewHash().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "previewHash is required");
        }

        String requestHash = canonicalRequestHash(command);
        MergeModels.MergeView existing = replayIfPresent(tenantId, key, requestHash);
        if (existing != null) {
            return existing;
        }

        try {
            return transactionTemplate.execute(status ->
                    executeInTransaction(tenantId, workspaceId, actorId, key, requestHash, command));
        } catch (MergeIdempotencyRaceException race) {
            MergeModels.MergeView committed = replayIfPresent(tenantId, race.idempotencyKey(), race.requestHash());
            if (committed != null) {
                return committed;
            }
            throw new ApiException(ErrorCode.CONFLICT, "Contact is already a merge loser");
        }
    }

    private MergeModels.MergeView replayIfPresent(UUID tenantId, String key, String requestHash) {
        var existing = mergeRepository.findByIdempotencyKey(tenantId, key);
        if (existing.isEmpty()) {
            return null;
        }
        MergeModels.MergeView prior = existing.get();
        if (!requestHash.equals(prior.requestHash())) {
            throw new ApiException(ErrorCode.CONFLICT, "Idempotency-Key reuse with different request");
        }
        return prior;
    }

    private MergeModels.MergeView executeInTransaction(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            String key,
            String requestHash,
            MergeModels.ExecuteCommand command
    ) {
        PostgresUuidOrdering.Pair lockOrder = uuidOrdering.canonical(
                command.survivorContactId(), command.loserContactId());
        if (!contactRepository.lockById(tenantId, workspaceId, lockOrder.low())
                || !contactRepository.lockById(tenantId, workspaceId, lockOrder.high())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Contact not found");
        }

        // Peer may have committed while we waited for locks.
        MergeModels.MergeView afterLock = replayIfPresent(tenantId, key, requestHash);
        if (afterLock != null) {
            return afterLock;
        }
        if (mergeRepository.isMergeLoser(tenantId, workspaceId, command.loserContactId())
                || mergeRepository.isMergeLoser(tenantId, workspaceId, command.survivorContactId())) {
            throw new ApiException(ErrorCode.CONFLICT, "Contact is already a merge loser");
        }

        MergePreviewService.MergeContext ctx = previewService.loadAndValidate(
                tenantId, workspaceId, command.candidateId(),
                command.survivorContactId(), command.loserContactId(), true);

        MergeModels.MergePreview preview = previewService.preview(
                tenantId,
                workspaceId,
                new MergeModels.PreviewCommand(
                        command.candidateId(),
                        command.survivorContactId(),
                        command.loserContactId(),
                        command.fieldChoices(),
                        command.primaryChoices()
                )
        );
        if (!preview.mergeAllowed()) {
            throw new ApiException(ErrorCode.CONFLICT, "Merge blocked by conflicts");
        }
        if (!command.previewHash().equals(preview.previewHash())) {
            throw new ApiException(ErrorCode.CONFLICT, "STALE_PREVIEW: previewHash does not match current state");
        }

        ChannelMergePlanner.Plan plan = ChannelMergePlanner.plan(
                ctx.survivor().id(),
                ctx.loser().id(),
                ctx.survivor().channels(),
                ctx.loser().channels(),
                command.primaryChoices() == null ? List.of() : command.primaryChoices()
        );

        applyFields(tenantId, workspaceId, actorId, ctx.survivor(), ctx.loser(), preview.fields());
        applyChannels(tenantId, workspaceId, ctx.survivor().id(), ctx.loser().id(), plan);
        applyConsent(tenantId, workspaceId, actorId, ctx.survivor().id(), preview.consent(), preview.previewHash());
        applyGroups(tenantId, workspaceId, ctx.survivor().id(), ctx.loser().id(), preview.groups());

        List<ContactMergeRepository.ShareRow> revokedShares = revokeShares(
                tenantId, workspaceId, actorId, ctx.survivor().id(), ctx.loser().id());

        mergeRepository.markLoserMerged(tenantId, workspaceId, ctx.loser().id(), ctx.survivor().id(), actorId);
        mergeRepository.markCandidateMerged(tenantId, workspaceId, ctx.candidateId());
        mergeRepository.supersedeCandidatesInvolving(tenantId, workspaceId, ctx.loser().id());

        Map<String, Object> requestSnapshot = new TreeMap<>();
        requestSnapshot.put("candidateId", command.candidateId().toString());
        requestSnapshot.put("survivorContactId", command.survivorContactId().toString());
        requestSnapshot.put("loserContactId", command.loserContactId().toString());
        requestSnapshot.put("previewHash", command.previewHash());
        requestSnapshot.put("confirm", true);

        Map<String, Object> resultSnapshot = new TreeMap<>();
        resultSnapshot.put("movedChannelCount", plan.moveChannelIds().size());
        resultSnapshot.put("dedupedChannelCount", plan.dedupeLoserChannelIds().size());
        resultSnapshot.put("revokedShareCount", revokedShares.size());
        resultSnapshot.put("consentChangeCount",
                preview.consent().stream().filter(MergeModels.ConsentCellDiff::changed).count());

        executionProbe.ifAvailable(MergeExecutionProbe::beforeMergeRecordPersist);

        UUID mergeId;
        try {
            mergeId = mergeRepository.insertMerge(
                    tenantId, workspaceId, ctx.survivor().id(), ctx.loser().id(),
                    key, requestHash, preview.previewHash(), actorId,
                    ContactMergeRepository.toJson(requestSnapshot),
                    ContactMergeRepository.toJson(resultSnapshot)
            );
        } catch (DataIntegrityViolationException ex) {
            // Roll back local mutations; facade reloads the peer's committed merge if any.
            throw new MergeIdempotencyRaceException(key, requestHash, ex);
        }

        writeMergeItems(mergeId, preview, plan, revokedShares);
        for (ContactMergeRepository.ShareRow share : revokedShares) {
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "contact.share_revoked", "resource_share", share.id().toString(),
                    ContactMergeRepository.toJson(Map.of(
                            "shareId", share.id().toString(),
                            "mergeId", mergeId.toString(),
                            "loserContactId", ctx.loser().id().toString(),
                            "survivorContactId", ctx.survivor().id().toString(),
                            "sourceWorkspaceId", share.sourceWorkspaceId().toString(),
                            "targetWorkspaceId", share.targetWorkspaceId().toString(),
                            "permission", share.permission(),
                            "reason", "CONTACT_MERGED"
                    ))
            );
        }
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "contact.merged", "contact_merge", mergeId.toString(),
                ContactMergeRepository.toJson(Map.of(
                        "mergeId", mergeId.toString(),
                        "survivorContactId", ctx.survivor().id().toString(),
                        "loserContactId", ctx.loser().id().toString(),
                        "candidateId", ctx.candidateId().toString()
                ))
        );
        log.info("contact_merged mergeId={} survivorId={} loserId={}", mergeId, ctx.survivor().id(), ctx.loser().id());
        return mergeRepository.findById(tenantId, workspaceId, mergeId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Merge record missing after insert"));
    }

    private void applyFields(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            ContactModels.ContactView survivor,
            ContactModels.ContactView loser,
            List<MergeModels.FieldDiff> fields
    ) {
        String displayName = survivor.displayName();
        Map<String, Object> custom = new LinkedHashMap<>(
                survivor.customFields() == null ? Map.of() : survivor.customFields());
        for (MergeModels.FieldDiff field : fields) {
            if ("display_name".equals(field.fieldKey()) && "LOSER".equals(field.source())) {
                displayName = loser.displayName();
            } else if (field.fieldKey().startsWith("custom.")) {
                String key = field.fieldKey().substring("custom.".length());
                if ("LOSER".equals(field.source()) || (isBlank(custom.get(key)) && field.resultValue() != null)) {
                    custom.put(key, field.resultValue());
                }
            }
        }
        String primaryEmail = survivor.channels().stream()
                .filter(c -> "EMAIL".equals(c.channelType()) && c.primary() && "ACTIVE".equals(c.status()))
                .map(ContactModels.ChannelView::valueNormalized)
                .findFirst()
                .orElse(survivor.primaryEmail());
        String primaryPhone = survivor.channels().stream()
                .filter(c -> "PHONE".equals(c.channelType()) && c.primary() && "ACTIVE".equals(c.status()))
                .map(ContactModels.ChannelView::valueNormalized)
                .findFirst()
                .orElse(survivor.primaryPhone());
        contactRepository.updateContactForMerge(
                tenantId, workspaceId, survivor.id(), displayName, primaryEmail, primaryPhone,
                CustomFieldValidator.toJson(custom), actorId
        );
        // linked user: take loser only when survivor null
        UUID survivorLinked = mergeRepository.findLinkedCustomerUserId(tenantId, workspaceId, survivor.id()).orElse(null);
        UUID loserLinked = mergeRepository.findLinkedCustomerUserId(tenantId, workspaceId, loser.id()).orElse(null);
        if (survivorLinked == null && loserLinked != null) {
            contactRepository.updateLinkedCustomerUser(tenantId, workspaceId, survivor.id(), loserLinked);
        }
    }

    private void applyChannels(
            UUID tenantId,
            UUID workspaceId,
            UUID survivorId,
            UUID loserId,
            ChannelMergePlanner.Plan plan
    ) {
        for (UUID channelId : plan.dedupeLoserChannelIds()) {
            mergeRepository.archiveChannel(tenantId, workspaceId, channelId, loserId);
        }
        for (UUID channelId : plan.moveChannelIds()) {
            mergeRepository.moveChannelToSurvivor(tenantId, workspaceId, channelId, loserId, survivorId);
        }
        for (Map.Entry<String, UUID> primary : plan.primaryByType().entrySet()) {
            mergeRepository.clearPrimaries(tenantId, workspaceId, survivorId, primary.getKey());
            mergeRepository.setPrimary(tenantId, workspaceId, survivorId, primary.getValue());
        }
        refreshPrimaries(tenantId, workspaceId, survivorId);
    }

    private void refreshPrimaries(UUID tenantId, UUID workspaceId, UUID survivorId) {
        ContactModels.ContactView refreshed = contactRepository.findById(tenantId, workspaceId, survivorId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        String email = refreshed.channels().stream()
                .filter(c -> "EMAIL".equals(c.channelType()) && c.primary() && "ACTIVE".equals(c.status()))
                .map(ContactModels.ChannelView::valueNormalized)
                .findFirst().orElse(null);
        String phone = refreshed.channels().stream()
                .filter(c -> "PHONE".equals(c.channelType()) && c.primary() && "ACTIVE".equals(c.status()))
                .map(ContactModels.ChannelView::valueNormalized)
                .findFirst().orElse(null);
        contactRepository.updateContactForMerge(
                tenantId, workspaceId, survivorId, refreshed.displayName(), email, phone,
                CustomFieldValidator.toJson(refreshed.customFields() == null ? Map.of() : refreshed.customFields()),
                null
        );
    }

    private void applyConsent(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID survivorId,
            List<MergeModels.ConsentCellDiff> cells,
            String previewHash
    ) {
        for (MergeModels.ConsentCellDiff cell : cells) {
            if (!cell.changed()) {
                continue;
            }
            var existing = consentRepository.findConsent(
                    tenantId, workspaceId, survivorId, cell.channelType(), cell.purpose());
            String evidence = ContactMergeRepository.toJson(Map.of(
                    "mergePreviewHash", previewHash,
                    "event", "MERGE_RECONCILED"
            ));
            if (existing.isEmpty()) {
                consentRepository.insertConsent(
                        tenantId, workspaceId, survivorId, cell.channelType(), cell.purpose(),
                        cell.after(), "API", evidence, actorId
                );
            } else {
                consentRepository.updateConsent(
                        tenantId, workspaceId, survivorId, cell.channelType(), cell.purpose(),
                        cell.after(), "API", evidence, actorId
                );
            }
            consentRepository.insertEvent(
                    tenantId, workspaceId, survivorId, cell.channelType(), cell.purpose(),
                    cell.survivorBefore(), cell.after(), "MERGE_RECONCILED", "API", evidence, actorId
            );
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "consent.changed", "contact_consent",
                    survivorId + ":" + cell.channelType() + ":" + cell.purpose(),
                    ContactMergeRepository.toJson(Map.of(
                            "channelType", cell.channelType(),
                            "purpose", cell.purpose(),
                            "previousStatus", cell.survivorBefore(),
                            "newStatus", cell.after(),
                            "eventType", "MERGE_RECONCILED"
                    ))
            );
        }
    }

    private void applyGroups(
            UUID tenantId,
            UUID workspaceId,
            UUID survivorId,
            UUID loserId,
            List<MergeModels.GroupChange> groups
    ) {
        for (MergeModels.GroupChange change : groups) {
            if ("UNION_MEMBER".equals(change.action())) {
                groupRepository.addManualMembers(tenantId, workspaceId, change.groupId(), List.of(survivorId));
            } else if ("REMOVE_LOSER_MEMBER".equals(change.action())) {
                groupRepository.removeMembers(tenantId, workspaceId, change.groupId(), List.of(loserId));
            } else if ("DYNAMIC_REFRESH".equals(change.action())) {
                groupRepository.markMaterializationPending(tenantId, workspaceId, change.groupId());
                outboxRepository.append(OutboxAppendCommand.of(
                        ContactGroupModels.AGGREGATE_CONTACT_GROUP,
                        change.groupId(),
                        ContactGroupModels.EVENT_CONTACT_GROUP_REFRESH,
                        "{\"groupId\":\"" + change.groupId() + "\",\"reason\":\"CONTACT_MERGED\"}",
                        tenantId,
                        workspaceId,
                        null,
                        "CONTACT_GROUP_REFRESH:" + change.groupId() + ":" + UUID.randomUUID()
                ));
            }
        }
    }

    private List<ContactMergeRepository.ShareRow> revokeShares(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID survivorId,
            UUID loserId
    ) {
        List<ContactMergeRepository.ShareRow> shares = mergeRepository.listActiveContactShares(tenantId, loserId);
        List<ContactMergeRepository.ShareRow> revoked = new ArrayList<>();
        for (ContactMergeRepository.ShareRow share : shares) {
            if (mergeRepository.revokeShare(tenantId, share.id()) > 0) {
                revoked.add(share);
            }
        }
        return revoked;
    }

    private void writeMergeItems(
            UUID mergeId,
            MergeModels.MergePreview preview,
            ChannelMergePlanner.Plan plan,
            List<ContactMergeRepository.ShareRow> revokedShares
    ) {
        for (MergeModels.FieldDiff field : preview.fields()) {
            mergeRepository.insertItem(
                    mergeId, "FIELD", field.fieldKey(),
                    "LOSER".equals(field.source()) ? "TAKEN_LOSER" : "KEPT_SURVIVOR",
                    ContactMergeRepository.toJson(Map.of("source", "SURVIVOR")),
                    ContactMergeRepository.toJson(Map.of("source", field.source()))
            );
        }
        for (MergeModels.ChannelDisposition d : plan.dispositions()) {
            String action = switch (d.action()) {
                case "MOVED" -> "MOVED";
                case "DEDUPED_ARCHIVED" -> "DEDUPLICATED";
                case "RETAINED" -> "KEPT_SURVIVOR";
                default -> "KEPT_SURVIVOR";
            };
            if ("LEFT_ARCHIVED".equals(d.action()) || "LEFT_INVALID".equals(d.action())) {
                action = "KEPT_SURVIVOR";
            }
            mergeRepository.insertItem(
                    mergeId, "CHANNEL", d.channelId().toString(), action,
                    ContactMergeRepository.toJson(Map.of("status", d.status(), "action", d.action())),
                    ContactMergeRepository.toJson(Map.of("primaryAfterMerge", d.primaryAfterMerge()))
            );
        }
        for (MergeModels.ConsentCellDiff cell : preview.consent()) {
            if (!cell.changed()) {
                continue;
            }
            mergeRepository.insertItem(
                    mergeId, "CONSENT", cell.channelType() + ":" + cell.purpose(), "RECONCILED",
                    ContactMergeRepository.toJson(Map.of("status", cell.survivorBefore())),
                    ContactMergeRepository.toJson(Map.of("status", cell.after()))
            );
        }
        for (MergeModels.GroupChange group : preview.groups()) {
            String action = "DYNAMIC_REFRESH".equals(group.action()) ? "UNION" : "UNION";
            if ("REMOVE_LOSER_MEMBER".equals(group.action())) {
                action = "REVOKED";
            } else if ("UNION_MEMBER".equals(group.action())) {
                action = "UNION";
            }
            mergeRepository.insertItem(
                    mergeId, "GROUP", group.groupId().toString(), action,
                    ContactMergeRepository.toJson(Map.of("groupType", group.groupType())),
                    ContactMergeRepository.toJson(Map.of("action", group.action()))
            );
        }
        for (ContactMergeRepository.ShareRow share : revokedShares) {
            mergeRepository.insertItem(
                    mergeId, "SHARE", share.id().toString(), "REVOKED",
                    ContactMergeRepository.toJson(Map.of("permission", share.permission())),
                    ContactMergeRepository.toJson(Map.of("reason", "CONTACT_MERGED"))
            );
        }
    }

    static String canonicalRequestHash(MergeModels.ExecuteCommand command) {
        Map<String, Object> body = new TreeMap<>();
        body.put("candidateId", command.candidateId().toString());
        body.put("survivorContactId", command.survivorContactId().toString());
        body.put("loserContactId", command.loserContactId().toString());
        body.put("previewHash", command.previewHash());
        body.put("confirm", command.confirm());
        List<Map<String, String>> fieldChoices = new ArrayList<>();
        if (command.fieldChoices() != null) {
            command.fieldChoices().stream()
                    .sorted((a, b) -> a.fieldKey().compareTo(b.fieldKey()))
                    .forEach(c -> {
                        Map<String, String> m = new TreeMap<>();
                        m.put("fieldKey", c.fieldKey());
                        m.put("source", c.source() == null ? "SURVIVOR" : c.source());
                        fieldChoices.add(m);
                    });
        }
        body.put("fieldChoices", fieldChoices);
        List<Map<String, String>> primaryChoices = new ArrayList<>();
        if (command.primaryChoices() != null) {
            command.primaryChoices().stream()
                    .sorted((a, b) -> a.channelType().compareTo(b.channelType()))
                    .forEach(c -> {
                        Map<String, String> m = new TreeMap<>();
                        m.put("channelType", c.channelType());
                        m.put("channelId", c.channelId().toString());
                        primaryChoices.add(m);
                    });
        }
        body.put("primaryChoices", primaryChoices);
        return ChannelMergePlanner.sha256(ContactMergeRepository.toJson(body));
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    /** Thin wrapper so ContactMergeService can call LEAST/GREATEST without static JdbcTemplate. */
    static final class PostgresUuidOrderingSupport {
        private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

        PostgresUuidOrderingSupport(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        PostgresUuidOrdering.Pair canonical(UUID a, UUID b) {
            return PostgresUuidOrdering.canonical(jdbcTemplate, a, b);
        }
    }
}
