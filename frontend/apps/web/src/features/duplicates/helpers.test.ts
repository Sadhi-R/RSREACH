import { describe, expect, it } from "vitest";
import {
  channelActionLabel,
  consentDowngrades,
  createIdempotencyKey,
  fieldChoicesFromPreview,
  fieldsNeedExplicitChoice,
  hasBlockingConflicts,
  mergeRequestFingerprint,
  resolveIdempotencyKey,
  safeUnauthorizedMessage,
  statusBadgeClass
} from "./helpers";
import type { MergePreview } from "./types";

function samplePreview(overrides: Partial<MergePreview> = {}): MergePreview {
  return {
    candidateId: "c1",
    survivorContactId: "s1",
    loserContactId: "l1",
    fields: [
      {
        fieldKey: "display_name",
        survivorValue: "Ada",
        loserValue: "Ada Lovelace",
        resultValue: "Ada",
        source: "SURVIVOR"
      }
    ],
    channels: [
      {
        channelId: "ch1",
        contactId: "s1",
        channelType: "EMAIL",
        valueNormalized: "a@example.com",
        valueRaw: "a@example.com",
        status: "ACTIVE",
        action: "RETAINED",
        primaryAfterMerge: true
      },
      {
        channelId: "ch2",
        contactId: "l1",
        channelType: "EMAIL",
        valueNormalized: "b@example.com",
        valueRaw: "b@example.com",
        status: "ARCHIVED",
        action: "LEFT_ARCHIVED",
        primaryAfterMerge: false
      }
    ],
    introducedAddresses: [],
    consent: [
      {
        channelType: "EMAIL",
        purpose: "MARKETING",
        survivorBefore: "OPTED_IN",
        loserBefore: "OPTED_IN",
        after: "UNKNOWN",
        changed: true,
        requiresFreshConsent: true
      }
    ],
    groups: [],
    sharesToRevoke: [],
    blockingConflicts: [],
    mergeAllowed: true,
    previewHash: "hash-1",
    stale: false,
    ...overrides
  };
}

describe("duplicate/merge helpers", () => {
  it("marks dismissed and superseded status badges distinctly", () => {
    expect(statusBadgeClass("DISMISSED")).toContain("badge-muted");
    expect(statusBadgeClass("SUPERSEDED")).toContain("badge-warn");
    expect(statusBadgeClass("OPEN", true)).toContain("badge-warn");
  });

  it("labels channel dispositions without inventing reactivation", () => {
    expect(channelActionLabel("LEFT_ARCHIVED")).toMatch(/archived/i);
    expect(channelActionLabel("LEFT_INVALID")).toMatch(/invalid/i);
    expect(channelActionLabel("MOVED")).toMatch(/moved/i);
    expect(channelActionLabel("DEDUPED_ARCHIVED")).toMatch(/deduplicated/i);
  });

  it("derives field choices and detects conflicts from preview", () => {
    const preview = samplePreview();
    expect(fieldChoicesFromPreview(preview.fields)).toEqual([
      { fieldKey: "display_name", source: "SURVIVOR" }
    ]);
    expect(fieldsNeedExplicitChoice(preview.fields)).toHaveLength(1);
  });

  it("surfaces consent downgrade warnings from preview", () => {
    const downgrades = consentDowngrades(samplePreview());
    expect(downgrades).toHaveLength(1);
    expect(downgrades[0]?.after).toBe("UNKNOWN");
    expect(downgrades[0]?.requiresFreshConsent).toBe(true);
  });

  it("blocks progress when backend returns blocking conflicts", () => {
    expect(
      hasBlockingConflicts(
        samplePreview({
          mergeAllowed: false,
          blockingConflicts: [{ code: "LINKED_USER", message: "Conflict" }]
        })
      )
    ).toBe(true);
  });

  it("reuses idempotency key for the same logical request fingerprint", () => {
    const body = {
      candidateId: "c1",
      survivorContactId: "s1",
      loserContactId: "l1",
      fieldChoices: [{ fieldKey: "display_name", source: "SURVIVOR" }],
      primaryChoices: [{ channelType: "EMAIL", channelId: "ch1" }],
      previewHash: "hash-1"
    };
    const fp = mergeRequestFingerprint(body);
    const first = resolveIdempotencyKey(null, null, fp);
    const retry = resolveIdempotencyKey(first.key, first.fingerprint, fp);
    expect(retry.key).toBe(first.key);

    const changed = resolveIdempotencyKey(
      first.key,
      first.fingerprint,
      mergeRequestFingerprint({ ...body, previewHash: "hash-2" })
    );
    expect(changed.key).not.toBe(first.key);
  });

  it("creates non-empty idempotency keys", () => {
    expect(createIdempotencyKey().length).toBeGreaterThan(8);
  });

  it("maps unauthorized statuses to safe messages", () => {
    expect(safeUnauthorizedMessage(404)).toMatch(/not found/i);
    expect(safeUnauthorizedMessage(403)).toMatch(/permission/i);
    expect(safeUnauthorizedMessage(401)).toMatch(/session/i);
  });
});
