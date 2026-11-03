package com.rsreach.contacts;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic channel merge plan shared by preview and execution (ADR 0005 / plan §0.1 F1).
 */
public final class ChannelMergePlanner {

    private ChannelMergePlanner() {
    }

    public record Plan(
            List<MergeModels.ChannelDisposition> dispositions,
            List<UUID> moveChannelIds,
            List<UUID> dedupeLoserChannelIds,
            Map<String, Set<String>> preMergeSurvivorActive,
            Map<String, Set<String>> postMergeActiveByType,
            Map<String, Set<String>> introducedByType,
            Map<String, UUID> primaryByType,
            String channelFingerprint
    ) {
    }

    public static Plan plan(
            UUID survivorContactId,
            UUID loserContactId,
            List<ContactModels.ChannelView> survivorChannels,
            List<ContactModels.ChannelView> loserChannels,
            List<MergeModels.PrimaryChoice> primaryChoices
    ) {
        List<ContactModels.ChannelView> survivorSorted = sortChannels(survivorChannels);
        List<ContactModels.ChannelView> loserSorted = sortChannels(loserChannels);

        Map<String, Set<String>> preSurvivorActive = activeNormalizedByType(survivorSorted);
        Map<String, UUID> survivorActiveIdByKey = new LinkedHashMap<>();
        for (ContactModels.ChannelView ch : survivorSorted) {
            if ("ACTIVE".equals(ch.status())) {
                survivorActiveIdByKey.put(key(ch.channelType(), ch.valueNormalized()), ch.id());
            }
        }

        List<MergeModels.ChannelDisposition> dispositions = new ArrayList<>();
        List<UUID> moveIds = new ArrayList<>();
        List<UUID> dedupeIds = new ArrayList<>();
        Map<String, Set<String>> postActive = copySets(preSurvivorActive);
        Map<String, Set<String>> introduced = new TreeMap<>();

        for (ContactModels.ChannelView ch : survivorSorted) {
            dispositions.add(new MergeModels.ChannelDisposition(
                    ch.id(), survivorContactId, ch.channelType(), ch.valueNormalized(), ch.value(),
                    ch.status(), "RETAINED", false
            ));
        }

        for (ContactModels.ChannelView ch : loserSorted) {
            if ("ARCHIVED".equals(ch.status())) {
                dispositions.add(new MergeModels.ChannelDisposition(
                        ch.id(), loserContactId, ch.channelType(), ch.valueNormalized(), ch.value(),
                        ch.status(), "LEFT_ARCHIVED", false
                ));
                continue;
            }
            if ("INVALID".equals(ch.status())) {
                dispositions.add(new MergeModels.ChannelDisposition(
                        ch.id(), loserContactId, ch.channelType(), ch.valueNormalized(), ch.value(),
                        ch.status(), "LEFT_INVALID", false
                ));
                continue;
            }
            if (!"ACTIVE".equals(ch.status())) {
                dispositions.add(new MergeModels.ChannelDisposition(
                        ch.id(), loserContactId, ch.channelType(), ch.valueNormalized(), ch.value(),
                        ch.status(), "LEFT_INVALID", false
                ));
                continue;
            }

            String k = key(ch.channelType(), ch.valueNormalized());
            if (survivorActiveIdByKey.containsKey(k)) {
                dedupeIds.add(ch.id());
                dispositions.add(new MergeModels.ChannelDisposition(
                        ch.id(), loserContactId, ch.channelType(), ch.valueNormalized(), ch.value(),
                        ch.status(), "DEDUPED_ARCHIVED", false
                ));
            } else {
                moveIds.add(ch.id());
                postActive.computeIfAbsent(ch.channelType(), t -> new LinkedHashSet<>())
                        .add(ch.valueNormalized());
                if (!preSurvivorActive.getOrDefault(ch.channelType(), Set.of()).contains(ch.valueNormalized())) {
                    introduced.computeIfAbsent(ch.channelType(), t -> new LinkedHashSet<>())
                            .add(ch.valueNormalized());
                }
                dispositions.add(new MergeModels.ChannelDisposition(
                        ch.id(), survivorContactId, ch.channelType(), ch.valueNormalized(), ch.value(),
                        ch.status(), "MOVED", false
                ));
            }
        }

        Map<String, UUID> primaryByType = resolvePrimaries(
                survivorSorted, loserSorted, moveIds, primaryChoices, postActive);

        List<MergeModels.ChannelDisposition> withPrimary = dispositions.stream()
                .map(d -> {
                    boolean primary = "ACTIVE".equals(statusAfter(d))
                            && d.channelId().equals(primaryByType.get(d.channelType()))
                            && isOnSurvivorAfter(d);
                    return new MergeModels.ChannelDisposition(
                            d.channelId(), d.contactId(), d.channelType(), d.valueNormalized(), d.valueRaw(),
                            d.status(), d.action(), primary
                    );
                })
                .toList();

        String fingerprint = fingerprint(withPrimary, primaryByType);
        return new Plan(
                withPrimary,
                List.copyOf(moveIds),
                List.copyOf(dedupeIds),
                freeze(preSurvivorActive),
                freeze(postActive),
                freeze(introduced),
                Map.copyOf(primaryByType),
                fingerprint
        );
    }

    private static boolean isOnSurvivorAfter(MergeModels.ChannelDisposition d) {
        return "RETAINED".equals(d.action()) || "MOVED".equals(d.action());
    }

    private static String statusAfter(MergeModels.ChannelDisposition d) {
        if ("MOVED".equals(d.action()) || ("RETAINED".equals(d.action()) && "ACTIVE".equals(d.status()))) {
            return "ACTIVE";
        }
        if ("DEDUPED_ARCHIVED".equals(d.action())) {
            return "ARCHIVED";
        }
        return d.status();
    }

    private static Map<String, UUID> resolvePrimaries(
            List<ContactModels.ChannelView> survivor,
            List<ContactModels.ChannelView> loser,
            List<UUID> moveIds,
            List<MergeModels.PrimaryChoice> choices,
            Map<String, Set<String>> postActive
    ) {
        Set<UUID> eligible = new LinkedHashSet<>();
        Map<UUID, ContactModels.ChannelView> byId = new LinkedHashMap<>();
        for (ContactModels.ChannelView ch : survivor) {
            byId.put(ch.id(), ch);
            if ("ACTIVE".equals(ch.status())) {
                eligible.add(ch.id());
            }
        }
        Set<UUID> moveSet = new LinkedHashSet<>(moveIds);
        for (ContactModels.ChannelView ch : loser) {
            byId.put(ch.id(), ch);
            if (moveSet.contains(ch.id())) {
                eligible.add(ch.id());
            }
        }

        Map<String, UUID> result = new TreeMap<>();
        if (choices != null) {
            for (MergeModels.PrimaryChoice choice : choices) {
                if (choice == null || choice.channelType() == null || choice.channelId() == null) {
                    continue;
                }
                String type = choice.channelType().trim().toUpperCase(Locale.ROOT);
                ContactModels.ChannelView ch = byId.get(choice.channelId());
                if (ch == null || !type.equals(ch.channelType()) || !eligible.contains(choice.channelId())) {
                    throw new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "primaryChoices must reference a post-merge ACTIVE channel"
                    );
                }
                result.put(type, choice.channelId());
            }
        }

        for (String type : postActive.keySet()) {
            if (result.containsKey(type)) {
                continue;
            }
            UUID survivorPrimary = survivor.stream()
                    .filter(c -> type.equals(c.channelType()) && c.primary() && "ACTIVE".equals(c.status()))
                    .map(ContactModels.ChannelView::id)
                    .findFirst()
                    .orElse(null);
            if (survivorPrimary != null && eligible.contains(survivorPrimary)) {
                result.put(type, survivorPrimary);
                continue;
            }
            UUID first = survivor.stream()
                    .filter(c -> type.equals(c.channelType()) && "ACTIVE".equals(c.status()))
                    .map(ContactModels.ChannelView::id)
                    .findFirst()
                    .orElseGet(() -> loser.stream()
                            .filter(c -> type.equals(c.channelType()) && moveSet.contains(c.id()))
                            .map(ContactModels.ChannelView::id)
                            .findFirst()
                            .orElse(null));
            if (first != null) {
                result.put(type, first);
            }
        }
        return result;
    }

    private static String fingerprint(
            List<MergeModels.ChannelDisposition> dispositions,
            Map<String, UUID> primaryByType
    ) {
        StringBuilder sb = new StringBuilder();
        for (MergeModels.ChannelDisposition d : dispositions) {
            sb.append(d.channelId()).append('|')
                    .append(d.channelType()).append('|')
                    .append(d.action()).append('|')
                    .append(d.status()).append(';');
        }
        primaryByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("P:").append(e.getKey()).append('=').append(e.getValue()).append(';'));
        return sha256(sb.toString());
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Hash computation failed");
        }
    }

    private static List<ContactModels.ChannelView> sortChannels(List<ContactModels.ChannelView> channels) {
        return channels.stream()
                .sorted(Comparator
                        .comparing(ContactModels.ChannelView::channelType)
                        .thenComparing(ContactModels.ChannelView::valueNormalized)
                        .thenComparing(c -> c.id().toString()))
                .collect(Collectors.toList());
    }

    private static Map<String, Set<String>> activeNormalizedByType(List<ContactModels.ChannelView> channels) {
        Map<String, Set<String>> map = new TreeMap<>();
        for (ContactModels.ChannelView ch : channels) {
            if ("ACTIVE".equals(ch.status())) {
                map.computeIfAbsent(ch.channelType(), t -> new LinkedHashSet<>()).add(ch.valueNormalized());
            }
        }
        return map;
    }

    private static Map<String, Set<String>> copySets(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new TreeMap<>();
        source.forEach((k, v) -> copy.put(k, new LinkedHashSet<>(v)));
        return copy;
    }

    private static Map<String, Set<String>> freeze(Map<String, Set<String>> source) {
        Map<String, Set<String>> frozen = new TreeMap<>();
        source.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        return Map.copyOf(frozen);
    }

    private static String key(String type, String normalized) {
        return type + "|" + normalized;
    }
}
