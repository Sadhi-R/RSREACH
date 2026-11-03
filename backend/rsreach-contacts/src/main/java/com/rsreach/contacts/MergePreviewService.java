package com.rsreach.contacts;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MergePreviewService {

    private final ContactRepository contactRepository;
    private final ContactMergeRepository mergeRepository;
    private final ConsentRepository consentRepository;
    private final DuplicateCandidateRepository candidateRepository;
    private final DuplicateDetectionService detectionService;
    private final DuplicateCandidateLifecycleService candidateLifecycleService;

    public MergePreviewService(
            ContactRepository contactRepository,
            ContactMergeRepository mergeRepository,
            ConsentRepository consentRepository,
            DuplicateCandidateRepository candidateRepository,
            DuplicateDetectionService detectionService,
            DuplicateCandidateLifecycleService candidateLifecycleService
    ) {
        this.contactRepository = contactRepository;
        this.mergeRepository = mergeRepository;
        this.consentRepository = consentRepository;
        this.candidateRepository = candidateRepository;
        this.detectionService = detectionService;
        this.candidateLifecycleService = candidateLifecycleService;
    }

    @Transactional
    public MergeModels.MergePreview preview(
            UUID tenantId,
            UUID workspaceId,
            MergeModels.PreviewCommand command
    ) {
        MergeContext ctx = loadAndValidate(tenantId, workspaceId, command.candidateId(),
                command.survivorContactId(), command.loserContactId(), true);

        ChannelMergePlanner.Plan plan = ChannelMergePlanner.plan(
                ctx.survivor().id(),
                ctx.loser().id(),
                ctx.survivor().channels(),
                ctx.loser().channels(),
                command.primaryChoices() == null ? List.of() : command.primaryChoices()
        );

        List<MergeModels.FieldDiff> fields = fieldDiffs(ctx.survivor(), ctx.loser(), command.fieldChoices());
        List<MergeModels.BlockingConflict> conflicts = new ArrayList<>();
        UUID survivorLinked = mergeRepository.findLinkedCustomerUserId(tenantId, workspaceId, ctx.survivor().id())
                .orElse(null);
        UUID loserLinked = mergeRepository.findLinkedCustomerUserId(tenantId, workspaceId, ctx.loser().id())
                .orElse(null);
        if (survivorLinked != null && loserLinked != null && !survivorLinked.equals(loserLinked)) {
            conflicts.add(new MergeModels.BlockingConflict(
                    "LINKED_USER_CONFLICT",
                    "Contacts are linked to different customer users"
            ));
        }

        Map<String, String> survivorConsent = MergeConsentPolicy.indexCells(
                consentRepository.listConsents(tenantId, workspaceId, ctx.survivor().id()));
        Map<String, String> loserConsent = MergeConsentPolicy.indexCells(
                consentRepository.listConsents(tenantId, workspaceId, ctx.loser().id()));
        MergeConsentPolicy.Decision consent = MergeConsentPolicy.reconcile(
                survivorConsent, loserConsent, plan.introducedByType());

        List<MergeModels.IntroducedAddress> introduced = buildIntroduced(plan, ctx.loser().channels());
        List<MergeModels.GroupChange> groups = previewGroups(tenantId, workspaceId, ctx.survivor().id(), ctx.loser().id());
        List<MergeModels.ShareRevokePreview> shares = mergeRepository
                .listActiveContactShares(tenantId, ctx.loser().id())
                .stream()
                .map(s -> new MergeModels.ShareRevokePreview(
                        s.id(), s.sourceWorkspaceId(), s.targetWorkspaceId(), s.permission()))
                .toList();

        String previewHash = computePreviewHash(
                ctx.candidateId(),
                ctx.survivor().id(),
                ctx.loser().id(),
                plan,
                fields,
                consent,
                groups,
                shares,
                conflicts,
                ctx.matchFingerprint()
        );

        return new MergeModels.MergePreview(
                ctx.candidateId(),
                ctx.survivor().id(),
                ctx.loser().id(),
                fields,
                plan.dispositions(),
                introduced,
                consent.cells(),
                groups,
                shares,
                conflicts,
                conflicts.isEmpty(),
                previewHash,
                false
        );
    }

    MergeContext loadAndValidate(
            UUID tenantId,
            UUID workspaceId,
            UUID candidateId,
            UUID survivorId,
            UUID loserId,
            boolean supersedeIfStale
    ) {
        if (survivorId == null || loserId == null || candidateId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "candidateId, survivorContactId, and loserContactId are required");
        }
        if (survivorId.equals(loserId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Survivor and loser must be different contacts");
        }

        ContactModels.ContactView survivor = contactRepository.findById(tenantId, workspaceId, survivorId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        ContactModels.ContactView loser = contactRepository.findById(tenantId, workspaceId, loserId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));

        if (!workspaceId.equals(survivor.homeWorkspaceId()) || !workspaceId.equals(loser.homeWorkspaceId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Contact not found");
        }
        if (mergeRepository.isMergeLoser(tenantId, workspaceId, survivorId)
                || mergeRepository.isMergeLoser(tenantId, workspaceId, loserId)) {
            throw new ApiException(ErrorCode.CONFLICT, "Contact is already a merge loser");
        }

        DuplicateCandidateRepository.CandidateRow candidate = candidateRepository
                .findRow(tenantId, workspaceId, candidateId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Duplicate candidate not found"));

        Set<UUID> pair = Set.of(candidate.contactIdA(), candidate.contactIdB());
        if (!pair.contains(survivorId) || !pair.contains(loserId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Survivor/loser must match the candidate pair");
        }
        if ("MERGED".equals(candidate.status()) || "SUPERSEDED".equals(candidate.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Candidate is not mergeable");
        }

        boolean valid = detectionService.evidenceStillValid(tenantId, workspaceId, candidate);
        if (!valid) {
            if (supersedeIfStale && "OPEN".equals(candidate.status())) {
                // Commit SUPERSEDED outside the rolled-back request transaction.
                candidateLifecycleService.supersedeOpen(tenantId, workspaceId, candidateId);
            }
            throw new ApiException(ErrorCode.CONFLICT, "CANDIDATE_STALE: exact-match evidence is no longer valid");
        }

        String matchFingerprint = matchFingerprint(candidate);
        return new MergeContext(candidateId, survivor, loser, candidate, matchFingerprint);
    }

    private List<MergeModels.FieldDiff> fieldDiffs(
            ContactModels.ContactView survivor,
            ContactModels.ContactView loser,
            List<MergeModels.FieldChoice> choices
    ) {
        Map<String, String> choiceMap = new LinkedHashMap<>();
        if (choices != null) {
            for (MergeModels.FieldChoice choice : choices) {
                if (choice == null || choice.fieldKey() == null) {
                    continue;
                }
                String source = choice.source() == null ? "SURVIVOR" : choice.source().trim().toUpperCase();
                if (!source.equals("SURVIVOR") && !source.equals("LOSER")) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "fieldChoices.source must be SURVIVOR or LOSER");
                }
                choiceMap.put(choice.fieldKey(), source);
            }
        }

        List<MergeModels.FieldDiff> diffs = new ArrayList<>();
        String displaySource = choiceMap.getOrDefault("display_name", "SURVIVOR");
        String displayResult = "LOSER".equals(displaySource) ? loser.displayName() : survivor.displayName();
        diffs.add(new MergeModels.FieldDiff(
                "display_name", survivor.displayName(), loser.displayName(), displayResult, displaySource));

        Map<String, Object> survivorFields = survivor.customFields() == null ? Map.of() : survivor.customFields();
        Map<String, Object> loserFields = loser.customFields() == null ? Map.of() : loser.customFields();
        Map<String, Object> result = new TreeMap<>(survivorFields);
        for (Map.Entry<String, Object> entry : loserFields.entrySet()) {
            Object current = result.get(entry.getKey());
            String source = choiceMap.get("custom." + entry.getKey());
            if (source == null) {
                if (isBlank(current) && !isBlank(entry.getValue())) {
                    result.put(entry.getKey(), entry.getValue());
                    source = "LOSER";
                } else {
                    source = "SURVIVOR";
                }
            } else if ("LOSER".equals(source)) {
                result.put(entry.getKey(), entry.getValue());
            }
            diffs.add(new MergeModels.FieldDiff(
                    "custom." + entry.getKey(),
                    survivorFields.get(entry.getKey()),
                    entry.getValue(),
                    result.get(entry.getKey()),
                    source
            ));
        }
        for (String key : survivorFields.keySet()) {
            if (!loserFields.containsKey(key)) {
                diffs.add(new MergeModels.FieldDiff(
                        "custom." + key,
                        survivorFields.get(key),
                        null,
                        survivorFields.get(key),
                        choiceMap.getOrDefault("custom." + key, "SURVIVOR")
                ));
            }
        }

        UUID survivorLinked = mergeRepository.findLinkedCustomerUserId(
                survivor.tenantId(), survivor.homeWorkspaceId(), survivor.id()).orElse(null);
        UUID loserLinked = mergeRepository.findLinkedCustomerUserId(
                loser.tenantId(), loser.homeWorkspaceId(), loser.id()).orElse(null);
        UUID linkedResult = survivorLinked != null ? survivorLinked : loserLinked;
        diffs.add(new MergeModels.FieldDiff(
                "linked_customer_user_id",
                survivorLinked,
                loserLinked,
                linkedResult,
                survivorLinked != null ? "SURVIVOR" : (loserLinked != null ? "LOSER" : "SURVIVOR")
        ));
        return diffs;
    }

    private List<MergeModels.IntroducedAddress> buildIntroduced(
            ChannelMergePlanner.Plan plan,
            List<ContactModels.ChannelView> loserChannels
    ) {
        Map<UUID, ContactModels.ChannelView> byId = new LinkedHashMap<>();
        for (ContactModels.ChannelView ch : loserChannels) {
            byId.put(ch.id(), ch);
        }
        List<MergeModels.IntroducedAddress> result = new ArrayList<>();
        for (MergeModels.ChannelDisposition d : plan.dispositions()) {
            if (!"MOVED".equals(d.action())) {
                continue;
            }
            Set<String> introduced = plan.introducedByType().getOrDefault(d.channelType(), Set.of());
            if (!introduced.contains(d.valueNormalized())) {
                continue;
            }
            ContactModels.ChannelView ch = byId.get(d.channelId());
            result.add(new MergeModels.IntroducedAddress(
                    "PHONE".equals(d.channelType()) ? "WHATSAPP" : "EMAIL",
                    d.channelType(),
                    d.channelId(),
                    d.valueNormalized(),
                    ch == null ? d.valueRaw() : ch.value()
            ));
        }
        return result;
    }

    private List<MergeModels.GroupChange> previewGroups(
            UUID tenantId,
            UUID workspaceId,
            UUID survivorId,
            UUID loserId
    ) {
        List<MergeModels.GroupChange> changes = new ArrayList<>();
        Set<UUID> survivorStatic = new java.util.LinkedHashSet<>();
        for (ContactMergeRepository.GroupMembership m : mergeRepository.listMemberships(tenantId, workspaceId, survivorId)) {
            if ("STATIC".equals(m.groupType()) && "MANUAL".equals(m.source())) {
                survivorStatic.add(m.groupId());
            }
        }
        for (ContactMergeRepository.GroupMembership m : mergeRepository.listMemberships(tenantId, workspaceId, loserId)) {
            if ("STATIC".equals(m.groupType()) && "MANUAL".equals(m.source())) {
                if (!survivorStatic.contains(m.groupId())) {
                    changes.add(new MergeModels.GroupChange(m.groupId(), "STATIC", "UNION_MEMBER"));
                }
                changes.add(new MergeModels.GroupChange(m.groupId(), "STATIC", "REMOVE_LOSER_MEMBER"));
            }
        }
        for (UUID groupId : mergeRepository.listDynamicGroupIds(tenantId, workspaceId)) {
            changes.add(new MergeModels.GroupChange(groupId, "DYNAMIC", "DYNAMIC_REFRESH"));
        }
        return changes;
    }

    String computePreviewHash(
            UUID candidateId,
            UUID survivorId,
            UUID loserId,
            ChannelMergePlanner.Plan plan,
            List<MergeModels.FieldDiff> fields,
            MergeConsentPolicy.Decision consent,
            List<MergeModels.GroupChange> groups,
            List<MergeModels.ShareRevokePreview> shares,
            List<MergeModels.BlockingConflict> conflicts,
            String matchFingerprint
    ) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("candidateId", candidateId.toString());
        canonical.put("survivorContactId", survivorId.toString());
        canonical.put("loserContactId", loserId.toString());
        canonical.put("channelFingerprint", plan.channelFingerprint());
        canonical.put("matchFingerprint", matchFingerprint);
        canonical.put("fields", fields.stream().map(f -> {
            Map<String, Object> m = new TreeMap<>();
            m.put("fieldKey", f.fieldKey());
            m.put("source", f.source());
            m.put("result", Objects.toString(f.resultValue(), ""));
            return m;
        }).toList());
        canonical.put("consent", consent.cells().stream().map(c -> {
            Map<String, Object> m = new TreeMap<>();
            m.put("channelType", c.channelType());
            m.put("purpose", c.purpose());
            m.put("after", c.after());
            return m;
        }).toList());
        canonical.put("groups", groups.stream().map(g -> g.groupId() + ":" + g.action()).sorted().toList());
        canonical.put("shares", shares.stream().map(s -> s.shareId().toString()).sorted().toList());
        canonical.put("conflicts", conflicts.stream().map(MergeModels.BlockingConflict::code).sorted().toList());
        return ChannelMergePlanner.sha256(ContactMergeRepository.toJson(canonical));
    }

    private static String matchFingerprint(DuplicateCandidateRepository.CandidateRow candidate) {
        StringBuilder sb = new StringBuilder();
        candidate.evidence().stream()
                .sorted((a, b) -> a.channelIdA().toString().compareTo(b.channelIdA().toString()))
                .forEach(m -> sb.append(m.matchType()).append('|')
                        .append(m.channelType()).append('|')
                        .append(m.channelIdA()).append('|')
                        .append(m.channelIdB()).append(';'));
        return ChannelMergePlanner.sha256(sb.toString());
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    record MergeContext(
            UUID candidateId,
            ContactModels.ContactView survivor,
            ContactModels.ContactView loser,
            DuplicateCandidateRepository.CandidateRow candidate,
            String matchFingerprint
    ) {
    }
}
