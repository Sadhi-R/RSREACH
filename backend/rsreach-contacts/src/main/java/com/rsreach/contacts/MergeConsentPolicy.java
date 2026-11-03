package com.rsreach.contacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure consent reconciliation for contact merge (ADR 0005 / plan §0 R1 + §6).
 * Uses ChannelMergePlanner post-merge ACTIVE sets only — never non-ACTIVE channels.
 */
public final class MergeConsentPolicy {

    private static final Map<String, Integer> RANK = Map.of(
            "OPTED_OUT", 3,
            "UNKNOWN", 2,
            "OPTED_IN", 1
    );

    private MergeConsentPolicy() {
    }

    public record Decision(
            List<MergeModels.ConsentCellDiff> cells,
            boolean requiresFreshConsent
    ) {
    }

    public static Decision reconcile(
            Map<String, String> survivorCells,
            Map<String, String> loserCells,
            Map<String, Set<String>> introducedByStorageType
    ) {
        List<MergeModels.ConsentCellDiff> diffs = new ArrayList<>();
        boolean anyFresh = false;
        for (String consentChannel : sorted(ConsentModels.CHANNELS)) {
            String storageType = storageTypeFor(consentChannel);
            boolean introduced = !introducedByStorageType.getOrDefault(storageType, Set.of()).isEmpty();
            for (String purpose : sorted(ConsentModels.PURPOSES)) {
                String key = cellKey(consentChannel, purpose);
                String survivorBefore = normalize(survivorCells.get(key));
                String loserBefore = normalize(loserCells.get(key));
                String base = mostRestrictive(survivorBefore, loserBefore);
                String after = base;
                if (introduced && "OPTED_IN".equals(base)) {
                    after = "UNKNOWN";
                }
                boolean changed = !after.equals(survivorBefore);
                boolean fresh = introduced && !"OPTED_OUT".equals(after);
                if (fresh) {
                    anyFresh = true;
                }
                diffs.add(new MergeModels.ConsentCellDiff(
                        consentChannel, purpose, survivorBefore, loserBefore, after, changed, fresh
                ));
            }
        }
        return new Decision(List.copyOf(diffs), anyFresh);
    }

    public static String mostRestrictive(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        return RANK.get(left) >= RANK.get(right) ? left : right;
    }

    public static String storageTypeFor(String consentChannel) {
        String normalized = consentChannel == null ? "" : consentChannel.trim().toUpperCase(Locale.ROOT);
        if ("WHATSAPP".equals(normalized)) {
            return "PHONE";
        }
        return "EMAIL";
    }

    public static String cellKey(String channelType, String purpose) {
        return channelType.trim().toUpperCase(Locale.ROOT) + ":" + purpose.trim().toUpperCase(Locale.ROOT);
    }

    public static Map<String, String> indexCells(List<ConsentModels.ConsentView> cells) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cells == null) {
            return map;
        }
        for (ConsentModels.ConsentView cell : cells) {
            map.put(cellKey(cell.channelType(), cell.purpose()), normalize(cell.status()));
        }
        return map;
    }

    private static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!ConsentModels.STATUSES.contains(normalized)) {
            return "UNKNOWN";
        }
        return normalized;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
