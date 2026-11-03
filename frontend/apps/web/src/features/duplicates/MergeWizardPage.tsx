import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getTenantId, getWorkspaceId } from "../../auth";
import {
  ApiClientError,
  executeMerge,
  getCandidate,
  loadPermissions,
  previewMerge
} from "./api";
import {
  ChannelDispositionList,
  ConsentImpactList,
  EmptyState,
  ErrorState,
  LoadingState,
  WizardProgress
} from "./components";
import {
  WIZARD_STEPS,
  consentDowngrades,
  displayValue,
  fieldChoicesFromPreview,
  fieldsNeedExplicitChoice,
  groupActionLabel,
  hasBlockingConflicts,
  mergeRequestFingerprint,
  primaryChoicesFromPreview,
  resolveIdempotencyKey,
  safeUnauthorizedMessage,
  wizardStepLabel,
  type WizardStep
} from "./helpers";
import type {
  CandidateDetail,
  FieldChoice,
  MergePreview,
  MergeView,
  PrimaryChoice
} from "./types";

export function MergeWizardPage() {
  const { candidateId } = useParams();

  const [step, setStep] = useState<WizardStep>("survivor");
  const [candidate, setCandidate] = useState<CandidateDetail | null>(null);
  const [survivorId, setSurvivorId] = useState<string | null>(null);
  const [loserId, setLoserId] = useState<string | null>(null);
  const [fieldChoices, setFieldChoices] = useState<FieldChoice[]>([]);
  const [primaryChoices, setPrimaryChoices] = useState<PrimaryChoice[]>([]);
  const [preview, setPreview] = useState<MergePreview | null>(null);
  const [loading, setLoading] = useState(true);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [submitBusy, setSubmitBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [staleNotice, setStaleNotice] = useState<string | null>(null);
  const [confirmChecked, setConfirmChecked] = useState(false);
  const [canMerge, setCanMerge] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null);
  const [idempotencyFingerprint, setIdempotencyFingerprint] = useState<string | null>(null);
  const [result, setResult] = useState<MergeView | null>(null);

  const loadCandidate = useCallback(async () => {
    if (!candidateId) {
      return;
    }
    if (!getTenantId() || !getWorkspaceId()) {
      setError("Select a tenant and workspace first.");
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const perms = await loadPermissions();
      const allowed =
        perms.includes("contacts.manage") && perms.includes("contacts.consent.manage");
      setCanMerge(allowed);
      if (!allowed) {
        setError("Merge requires contacts.manage and contacts.consent.manage.");
        setCandidate(null);
        return;
      }
      const detail = await getCandidate(candidateId);
      setCandidate(detail);
      if (detail.stale || detail.status !== "OPEN") {
        setError("This candidate cannot be merged in its current state.");
      }
    } catch (err) {
      setCandidate(null);
      if (err instanceof ApiClientError) {
        setError(
          err.status === 401 || err.status === 403 || err.status === 404
            ? safeUnauthorizedMessage(err.status)
            : err.message
        );
      } else {
        setError("Unable to load candidate.");
      }
    } finally {
      setLoading(false);
    }
  }, [candidateId]);

  useEffect(() => {
    void loadCandidate();
  }, [loadCandidate]);

  const refreshPreview = useCallback(
    async (nextSurvivor: string, nextLoser: string, fields: FieldChoice[], primaries: PrimaryChoice[]) => {
      if (!candidateId) {
        return null;
      }
      setPreviewBusy(true);
      setError(null);
      try {
        const next = await previewMerge({
          candidateId,
          survivorContactId: nextSurvivor,
          loserContactId: nextLoser,
          fieldChoices: fields,
          primaryChoices: primaries
        });
        setPreview(next);
        setStaleNotice(null);
        if (next.stale) {
          setStaleNotice("Preview reports a stale candidate. Refresh and review again.");
        }
        setFieldChoices(fieldChoicesFromPreview(next.fields));
        setPrimaryChoices(primaryChoicesFromPreview(next));
        return next;
      } catch (err) {
        setPreview(null);
        if (err instanceof ApiClientError) {
          if (err.status === 409) {
            setStaleNotice(
              "Identifiers changed or the preview is stale. Refresh the preview and review changes again."
            );
            setError(err.message);
          } else {
            setError(
              err.status === 401 || err.status === 403 || err.status === 404
                ? safeUnauthorizedMessage(err.status)
                : err.message
            );
          }
        } else {
          setError("Unable to load merge preview.");
        }
        return null;
      } finally {
        setPreviewBusy(false);
      }
    },
    [candidateId]
  );

  async function chooseSurvivor(id: string) {
    if (!candidate || submitBusy) {
      return;
    }
    const other = id === candidate.contactIdA ? candidate.contactIdB : candidate.contactIdA;
    setSurvivorId(id);
    setLoserId(other);
    setConfirmChecked(false);
    setIdempotencyKey(null);
    setIdempotencyFingerprint(null);
    const next = await refreshPreview(id, other, [], []);
    if (next) {
      setStep("fields");
    }
  }

  async function goNextFromFields() {
    if (!survivorId || !loserId) {
      return;
    }
    const next = await refreshPreview(survivorId, loserId, fieldChoices, primaryChoices);
    if (next && !hasBlockingConflicts(next)) {
      setStep("channels");
    }
  }

  async function goNextFromChannels() {
    if (!survivorId || !loserId) {
      return;
    }
    const next = await refreshPreview(survivorId, loserId, fieldChoices, primaryChoices);
    if (next) {
      setStep("consent");
    }
  }

  function setFieldSource(fieldKey: string, source: string) {
    setFieldChoices((prev) => {
      const others = prev.filter((f) => f.fieldKey !== fieldKey);
      return [...others, { fieldKey, source }];
    });
    setIdempotencyKey(null);
    setIdempotencyFingerprint(null);
  }

  function setPrimary(channelType: string, channelId: string) {
    setPrimaryChoices((prev) => {
      const others = prev.filter((p) => p.channelType !== channelType);
      return [...others, { channelType, channelId }];
    });
    setIdempotencyKey(null);
    setIdempotencyFingerprint(null);
  }

  const conflictingFields = useMemo(
    () => (preview ? fieldsNeedExplicitChoice(preview.fields) : []),
    [preview]
  );

  const primaryOptions = useMemo(() => {
    if (!preview) {
      return [] as Array<{ channelType: string; options: MergePreview["channels"] }>;
    }
    const types = new Map<string, MergePreview["channels"]>();
    for (const ch of preview.channels) {
      if (ch.action === "RETAINED" || ch.action === "MOVED") {
        const list = types.get(ch.channelType) ?? [];
        list.push(ch);
        types.set(ch.channelType, list);
      }
    }
    return [...types.entries()].map(([channelType, options]) => ({ channelType, options }));
  }, [preview]);

  async function onConfirmMerge() {
    if (!candidateId || !survivorId || !loserId || !preview || !confirmChecked || submitBusy) {
      return;
    }
    if (!preview.mergeAllowed || hasBlockingConflicts(preview)) {
      setError("Merge is blocked by conflicts. Resolve them or cancel.");
      return;
    }

    const body = {
      candidateId,
      survivorContactId: survivorId,
      loserContactId: loserId,
      fieldChoices,
      primaryChoices,
      previewHash: preview.previewHash,
      confirm: true as const
    };
    const fingerprint = mergeRequestFingerprint(body);
    const resolved = resolveIdempotencyKey(idempotencyKey, idempotencyFingerprint, fingerprint);
    setIdempotencyKey(resolved.key);
    setIdempotencyFingerprint(resolved.fingerprint);

    setSubmitBusy(true);
    setError(null);
    try {
      const merged = await executeMerge(body, resolved.key);
      setResult(merged);
      setStep("done");
    } catch (err) {
      if (err instanceof ApiClientError) {
        if (err.status === 409) {
          setStaleNotice(
            "Merge conflict (stale preview or idempotency mismatch). Refresh the preview and review again. A new confirmation will use a new request."
          );
          setPreview(null);
          setConfirmChecked(false);
          setIdempotencyKey(null);
          setIdempotencyFingerprint(null);
          setStep("fields");
          setError(err.message);
        } else {
          setError(
            err.status === 401 || err.status === 403 || err.status === 404
              ? safeUnauthorizedMessage(err.status)
              : err.message
          );
        }
      } else {
        setError("Merge failed.");
      }
    } finally {
      setSubmitBusy(false);
    }
  }

  async function onRetrySameRequest() {
    await onConfirmMerge();
  }

  if (loading) {
    return (
      <main className="shell wide">
        <LoadingState label="Loading merge wizard…" />
      </main>
    );
  }

  if (!canMerge || !candidate) {
    return (
      <main className="shell wide">
        <ErrorState message={error ?? "Unable to open merge wizard."} onRetry={() => void loadCandidate()} />
        <Link to="/app/contact-duplicates">Back</Link>
      </main>
    );
  }

  const nameA = candidate.contactA?.displayName ?? "Contact A";
  const nameB = candidate.contactB?.displayName ?? "Contact B";

  return (
    <main className="shell wide">
      <p className="eyebrow">Contact merge</p>
      <div className="row-between">
        <h1>Merge wizard</h1>
        <Link to={`/app/contact-duplicates/${candidate.id}`}>Cancel</Link>
      </div>

      {step !== "done" && (
        <WizardProgress
          steps={WIZARD_STEPS}
          current={step}
          labels={(s) => wizardStepLabel(s as WizardStep)}
        />
      )}

      {staleNotice && (
        <p className="banner-warn" role="status">
          {staleNotice}
        </p>
      )}
      {error && <ErrorState message={error} />}
      {previewBusy && <LoadingState label="Refreshing preview…" />}

      {step === "survivor" && (
        <section className="panel">
          <h2>Choose survivor</h2>
          <p className="lede">
            The survivor keeps the contact record. The loser is archived into the survivor. This
            operation cannot be undone.
          </p>
          <div className="two-col">
            <button
              type="button"
              className="choice-card"
              onClick={() => void chooseSurvivor(candidate.contactIdA)}
            >
              <strong>{nameA}</strong>
              <span className="lede">Keep as survivor</span>
            </button>
            <button
              type="button"
              className="choice-card"
              onClick={() => void chooseSurvivor(candidate.contactIdB)}
            >
              <strong>{nameB}</strong>
              <span className="lede">Keep as survivor</span>
            </button>
          </div>
        </section>
      )}

      {step === "fields" && preview && survivorId && loserId && (
        <section className="panel">
          <h2>Resolve fields</h2>
          <p className="lede">
            Survivor: {survivorId === candidate.contactIdA ? nameA : nameB}. Loser:{" "}
            {loserId === candidate.contactIdA ? nameA : nameB}.
          </p>
          {hasBlockingConflicts(preview) && (
            <div className="banner-error" role="alert">
              <p className="error">Merge is blocked:</p>
              <ul>
                {preview.blockingConflicts.map((c) => (
                  <li key={c.code}>
                    {c.code}: {c.message}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {preview.fields.length === 0 ? (
            <EmptyState>No field differences.</EmptyState>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>Field</th>
                  <th>Survivor</th>
                  <th>Loser</th>
                  <th>Keep</th>
                </tr>
              </thead>
              <tbody>
                {preview.fields.map((f) => {
                  const needsChoice = conflictingFields.some((c) => c.fieldKey === f.fieldKey);
                  const selected =
                    fieldChoices.find((c) => c.fieldKey === f.fieldKey)?.source ?? f.source;
                  return (
                    <tr key={f.fieldKey} className="no-row-nav">
                      <td>{f.fieldKey}</td>
                      <td>{displayValue(f.survivorValue)}</td>
                      <td>{displayValue(f.loserValue)}</td>
                      <td>
                        {needsChoice ? (
                          <select
                            aria-label={`Source for ${f.fieldKey}`}
                            value={selected}
                            onChange={(e) => setFieldSource(f.fieldKey, e.target.value)}
                          >
                            <option value="SURVIVOR">Survivor</option>
                            <option value="LOSER">Loser</option>
                          </select>
                        ) : (
                          selected
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          <div className="actions inline">
            <button type="button" onClick={() => setStep("survivor")}>
              Back
            </button>
            <button
              type="button"
              disabled={previewBusy || hasBlockingConflicts(preview)}
              onClick={() => void goNextFromFields()}
            >
              Continue
            </button>
            <button
              type="button"
              disabled={previewBusy}
              onClick={() => void refreshPreview(survivorId, loserId, fieldChoices, primaryChoices)}
            >
              Refresh preview
            </button>
          </div>
        </section>
      )}

      {step === "channels" && preview && survivorId && loserId && (
        <section className="panel">
          <h2>Review channels</h2>
          <p className="lede">
            Archived and invalid channels are never reactivated. Primaries may be chosen among
            retained or moved active channels.
          </p>
          <ChannelDispositionList channels={preview.channels} />
          {primaryOptions.length > 0 && (
            <div className="form" style={{ marginTop: "1rem" }}>
              <h3>Primary channels</h3>
              {primaryOptions.map(({ channelType, options }) => {
                const selected =
                  primaryChoices.find((p) => p.channelType === channelType)?.channelId ??
                  options.find((o) => o.primaryAfterMerge)?.channelId ??
                  options[0]?.channelId;
                return (
                  <label key={channelType} className="field-label">
                    Primary {channelType}
                    <select
                      value={selected}
                      onChange={(e) => setPrimary(channelType, e.target.value)}
                    >
                      {options.map((o) => (
                        <option key={o.channelId} value={o.channelId}>
                          {o.valueRaw || o.valueNormalized} ({o.action})
                        </option>
                      ))}
                    </select>
                  </label>
                );
              })}
            </div>
          )}
          <div className="actions inline">
            <button type="button" onClick={() => setStep("fields")}>
              Back
            </button>
            <button type="button" disabled={previewBusy} onClick={() => void goNextFromChannels()}>
              Continue
            </button>
          </div>
        </section>
      )}

      {step === "consent" && preview && (
        <section className="panel">
          <h2>Consent impact</h2>
          <p className="lede">
            Consent policy is enforced by the server. Newly introduced active addresses may force
            UNKNOWN until a fresh grant is recorded.
          </p>
          {preview.introducedAddresses.length > 0 && (
            <div className="banner-warn">
              <p>
                Newly introduced active addresses (
                {preview.introducedAddresses.map((a) => a.valueRaw || a.valueNormalized).join(", ")}
                ) can require fresh consent.
              </p>
            </div>
          )}
          <ConsentImpactList items={consentDowngrades(preview)} />
          <div className="actions inline">
            <button type="button" onClick={() => setStep("channels")}>
              Back
            </button>
            <button type="button" onClick={() => setStep("groups")}>
              Continue
            </button>
          </div>
        </section>
      )}

      {step === "groups" && preview && (
        <section className="panel">
          <h2>Groups and shares</h2>
          <h3>Groups</h3>
          {preview.groups.length === 0 ? (
            <EmptyState>No group changes.</EmptyState>
          ) : (
            <ul className="plain-list">
              {preview.groups.map((g) => (
                <li key={`${g.groupId}:${g.action}`}>
                  {g.groupType} · {groupActionLabel(g.action)} · {g.groupId.slice(0, 8)}…
                </li>
              ))}
            </ul>
          )}
          <h3>Shares</h3>
          <p className="lede">
            Active shares on the loser contact are revoked and are not transferred to the survivor.
          </p>
          {preview.sharesToRevoke.length === 0 ? (
            <EmptyState>No active loser shares to revoke.</EmptyState>
          ) : (
            <ul className="plain-list">
              {preview.sharesToRevoke.map((s) => (
                <li key={s.shareId}>
                  Share {s.shareId.slice(0, 8)}… · {s.permission}
                </li>
              ))}
            </ul>
          )}
          <div className="actions inline">
            <button type="button" onClick={() => setStep("consent")}>
              Back
            </button>
            <button type="button" onClick={() => setStep("confirm")}>
              Continue
            </button>
          </div>
        </section>
      )}

      {step === "confirm" && preview && survivorId && loserId && (
        <section className="panel">
          <h2>Final confirmation</h2>
          <p className="banner-warn">
            Merging contacts is irreversible. The loser will be archived into the survivor.
          </p>
          <ul className="plain-list">
            <li>
              Survivor: {survivorId === candidate.contactIdA ? nameA : nameB} ({survivorId})
            </li>
            <li>
              Loser: {loserId === candidate.contactIdA ? nameA : nameB} ({loserId})
            </li>
            <li>Field choices: {fieldChoices.length}</li>
            <li>Channel dispositions: {preview.channels.length}</li>
            <li>Consent cells changing: {consentDowngrades(preview).length}</li>
            <li>Shares to revoke: {preview.sharesToRevoke.length}</li>
          </ul>
          <label className="field-label">
            <input
              type="checkbox"
              checked={confirmChecked}
              onChange={(e) => setConfirmChecked(e.target.checked)}
            />{" "}
            I understand this merge cannot be undone.
          </label>
          <div className="actions inline">
            <button type="button" onClick={() => setStep("groups")} disabled={submitBusy}>
              Back
            </button>
            <button
              type="button"
              disabled={!confirmChecked || submitBusy || previewBusy}
              onClick={() => void onConfirmMerge()}
            >
              {submitBusy ? "Merging…" : "Confirm merge"}
            </button>
            {idempotencyKey && error && (
              <button type="button" disabled={submitBusy} onClick={() => void onRetrySameRequest()}>
                Retry same request
              </button>
            )}
          </div>
        </section>
      )}

      {step === "done" && result && (
        <section className="panel">
          <h2>Merge completed</h2>
          <p className="banner-ok" role="status">
            Merge {result.id} finished with status {result.status}.
          </p>
          <div className="actions inline">
            <Link to={`/app/contacts/${result.survivorContactId}`}>Open survivor</Link>
            <Link to={`/app/merges/${result.id}`}>Merge detail</Link>
            <Link to="/app/merges">Merge history</Link>
            <Link to="/app/contact-duplicates">Duplicates</Link>
          </div>
        </section>
      )}
    </main>
  );
}
