import type { FieldChoice, FieldDiff, MergePreview, PrimaryChoice } from "./types";

export type WizardStep =
  | "survivor"
  | "fields"
  | "channels"
  | "consent"
  | "groups"
  | "confirm"
  | "done";

export const WIZARD_STEPS: WizardStep[] = [
  "survivor",
  "fields",
  "channels",
  "consent",
  "groups",
  "confirm"
];

export function wizardStepLabel(step: WizardStep): string {
  switch (step) {
    case "survivor":
      return "Choose survivor";
    case "fields":
      return "Resolve fields";
    case "channels":
      return "Review channels";
    case "consent":
      return "Consent impact";
    case "groups":
      return "Groups & shares";
    case "confirm":
      return "Confirm merge";
    case "done":
      return "Complete";
    default:
      return step;
  }
}

export function statusBadgeClass(status: string, stale?: boolean): string {
  if (stale) {
    return "badge badge-warn";
  }
  switch (status) {
    case "OPEN":
      return "badge badge-ok";
    case "DISMISSED":
      return "badge badge-muted";
    case "SUPERSEDED":
      return "badge badge-warn";
    case "MERGED":
      return "badge badge-ok";
    default:
      return "badge";
  }
}

export function channelActionLabel(action: string): string {
  switch (action) {
    case "RETAINED":
      return "Retained";
    case "MOVED":
      return "Moved to survivor";
    case "DEDUPED_ARCHIVED":
      return "Deduplicated (archived)";
    case "LEFT_ARCHIVED":
      return "Left archived on loser";
    case "LEFT_INVALID":
      return "Left invalid on loser";
    default:
      return action;
  }
}

export function groupActionLabel(action: string): string {
  switch (action) {
    case "UNION_MEMBER":
      return "Add survivor to static group (union)";
    case "REMOVE_LOSER_MEMBER":
      return "Remove loser membership";
    case "DYNAMIC_REFRESH":
      return "Queue dynamic group refresh";
    default:
      return action;
  }
}

/** Defaults from preview field diffs (backend source). */
export function fieldChoicesFromPreview(fields: FieldDiff[]): FieldChoice[] {
  return fields.map((f) => ({ fieldKey: f.fieldKey, source: f.source }));
}

export function primaryChoicesFromPreview(preview: MergePreview): PrimaryChoice[] {
  const byType = new Map<string, string>();
  for (const ch of preview.channels) {
    if (ch.primaryAfterMerge) {
      byType.set(ch.channelType, ch.channelId);
    }
  }
  return [...byType.entries()].map(([channelType, channelId]) => ({ channelType, channelId }));
}

export function fieldsNeedExplicitChoice(fields: FieldDiff[]): FieldDiff[] {
  return fields.filter((f) => {
    const a = f.survivorValue;
    const b = f.loserValue;
    if (a == null && b == null) {
      return false;
    }
    if (a == null || b == null) {
      return false;
    }
    return String(a) !== String(b);
  });
}

export function hasBlockingConflicts(preview: MergePreview | null): boolean {
  return Boolean(preview && (!preview.mergeAllowed || preview.blockingConflicts.length > 0));
}

export function consentDowngrades(preview: MergePreview | null): NonNullable<MergePreview["consent"]> {
  if (!preview) {
    return [];
  }
  return preview.consent.filter((c) => c.changed || c.requiresFreshConsent);
}

export function createIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `merge-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Reuse the same key for retries of an identical logical request.
 * Generate a new key only when the logical request fingerprint changes.
 */
export function resolveIdempotencyKey(
  previousKey: string | null,
  previousFingerprint: string | null,
  fingerprint: string
): { key: string; fingerprint: string } {
  if (previousKey && previousFingerprint === fingerprint) {
    return { key: previousKey, fingerprint };
  }
  return { key: createIdempotencyKey(), fingerprint };
}

export function mergeRequestFingerprint(input: {
  candidateId: string;
  survivorContactId: string;
  loserContactId: string;
  fieldChoices: FieldChoice[];
  primaryChoices: PrimaryChoice[];
  previewHash: string;
}): string {
  const fields = [...input.fieldChoices]
    .map((f) => `${f.fieldKey}:${f.source}`)
    .sort()
    .join("|");
  const primaries = [...input.primaryChoices]
    .map((p) => `${p.channelType}:${p.channelId}`)
    .sort()
    .join("|");
  return [
    input.candidateId,
    input.survivorContactId,
    input.loserContactId,
    input.previewHash,
    fields,
    primaries
  ].join("::");
}

export function formatInstant(value: string | null | undefined): string {
  if (!value) {
    return "—";
  }
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) {
    return value;
  }
  return d.toLocaleString();
}

export function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === "") {
    return "—";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

export function safeUnauthorizedMessage(status: number): string {
  if (status === 401) {
    return "Your session expired. Sign in again.";
  }
  if (status === 403) {
    return "You do not have permission for this action.";
  }
  if (status === 404) {
    return "This record was not found in the current workspace.";
  }
  return "Unable to complete the request.";
}
