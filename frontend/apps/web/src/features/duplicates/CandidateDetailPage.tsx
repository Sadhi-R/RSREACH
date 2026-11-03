import { FormEvent, useCallback, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getTenantId, getWorkspaceId } from "../../auth";
import {
  ApiClientError,
  dismissCandidate,
  getCandidate,
  loadPermissions,
  reopenCandidate
} from "./api";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "./components";
import { formatInstant, safeUnauthorizedMessage } from "./helpers";
import type { CandidateDetail, ContactSummary } from "./types";

function ContactCard({
  title,
  contact,
  contactId
}: {
  title: string;
  contact: ContactSummary | null;
  contactId: string;
}) {
  if (!contact) {
    return (
      <div className="panel nested">
        <h3>{title}</h3>
        <p className="lede">Contact unavailable in this workspace.</p>
        <p className="mono-small">{contactId}</p>
      </div>
    );
  }
  return (
    <div className="panel nested">
      <h3>{title}</h3>
      <p>
        <Link to={`/app/contacts/${contact.id}`}>{contact.displayName}</Link>
      </p>
      <p className="lede">
        Status: {contact.status}
        {contact.archivedAt ? ` · archived ${formatInstant(contact.archivedAt)}` : ""}
      </p>
      <p className="lede">
        Email: {contact.primaryEmail ?? "—"} · Phone: {contact.primaryPhone ?? "—"}
      </p>
      {contact.channels?.length > 0 && (
        <ul className="plain-list">
          {contact.channels.map((ch) => (
            <li key={ch.id ?? `${ch.channelType}:${ch.value}`}>
              {ch.channelType} · {ch.value} · {ch.status ?? "ACTIVE"}
              {ch.primary ? " · primary" : ""}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function CandidateDetailPage() {
  const { candidateId } = useParams();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<CandidateDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [canManage, setCanManage] = useState(false);
  const [canMerge, setCanMerge] = useState(false);
  const [busy, setBusy] = useState(false);
  const [dismissReason, setDismissReason] = useState("");

  const load = useCallback(async () => {
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
      setCanManage(perms.includes("contacts.manage"));
      setCanMerge(
        perms.includes("contacts.manage") && perms.includes("contacts.consent.manage")
      );
      setDetail(await getCandidate(candidateId));
    } catch (err) {
      setDetail(null);
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
    void load();
  }, [load]);

  async function onDismiss(event: FormEvent) {
    event.preventDefault();
    if (!candidateId || busy || !dismissReason.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setDetail(await dismissCandidate(candidateId, dismissReason.trim()));
      setDismissReason("");
    } catch (err) {
      if (err instanceof ApiClientError) {
        setError(err.message);
      } else {
        setError("Dismiss failed.");
      }
    } finally {
      setBusy(false);
    }
  }

  async function onReopen() {
    if (!candidateId || busy) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setDetail(await reopenCandidate(candidateId));
    } catch (err) {
      if (err instanceof ApiClientError) {
        setError(
          err.status === 409
            ? `${err.message} Refresh or re-scan if identifiers changed.`
            : err.message
        );
      } else {
        setError("Reopen failed.");
      }
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <main className="shell wide">
        <LoadingState label="Loading candidate…" />
      </main>
    );
  }

  if (error && !detail) {
    return (
      <main className="shell wide">
        <ErrorState message={error} onRetry={() => void load()} />
        <Link to="/app/contact-duplicates">Back to list</Link>
      </main>
    );
  }

  if (!detail) {
    return (
      <main className="shell wide">
        <EmptyState>Candidate not found.</EmptyState>
        <Link to="/app/contact-duplicates">Back to list</Link>
      </main>
    );
  }

  const canStartMerge =
    canMerge && detail.status === "OPEN" && detail.mergeAllowed && !detail.stale;

  return (
    <main className="shell wide">
      <p className="eyebrow">Duplicate candidate</p>
      <div className="row-between">
        <h1>Review match</h1>
        <div className="actions inline">
          <Link to="/app/contact-duplicates">Back to list</Link>
          {canStartMerge && (
            <Link to={`/app/contact-duplicates/${detail.id}/merge`}>Start merge</Link>
          )}
        </div>
      </div>

      <section className="panel">
        <div className="row-between">
          <StatusBadge status={detail.status} stale={detail.stale} />
          <span className="lede">
            Score {detail.score} · {detail.confidence}
          </span>
        </div>
        <p className="lede">
          Match types: {detail.matchTypes.join(", ") || "—"}
        </p>
        <p className="lede">
          Detected {formatInstant(detail.detectedAt)} · Last{" "}
          {formatInstant(detail.lastDetectedAt)}
        </p>
        {detail.stale && (
          <p className="banner-warn" role="status">
            This candidate is stale. Matching identifiers may have changed. Re-scan or dismiss;
            merge is blocked until evidence is valid.
          </p>
        )}
        {detail.status === "DISMISSED" && (
          <p className="lede">
            Dismissed {formatInstant(detail.dismissedAt)}
            {detail.dismissReason ? `: ${detail.dismissReason}` : ""}
          </p>
        )}
        {detail.status === "SUPERSEDED" && (
          <p className="banner-warn">This candidate was superseded because the match is no longer valid.</p>
        )}
        {error && <ErrorState message={error} />}
      </section>

      <div className="two-col">
        <ContactCard title="Contact A" contact={detail.contactA} contactId={detail.contactIdA} />
        <ContactCard title="Contact B" contact={detail.contactB} contactId={detail.contactIdB} />
      </div>

      <section className="panel">
        <h2>Evidence</h2>
        {detail.matches.length === 0 ? (
          <EmptyState>No resolved matches.</EmptyState>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Channel</th>
                <th>Value A</th>
                <th>Value B</th>
                <th>Valid</th>
              </tr>
            </thead>
            <tbody>
              {detail.matches.map((m) => (
                <tr
                  key={`${m.channelIdA}-${m.channelIdB}-${m.matchType}`}
                  className="no-row-nav"
                >
                  <td>{m.matchType}</td>
                  <td>{m.channelType}</td>
                  <td>{m.valueRawA}</td>
                  <td>{m.valueRawB}</td>
                  <td>{m.stillValid ? "Yes" : "No"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {canManage && detail.status === "OPEN" && (
        <section className="panel">
          <h2>Dismiss</h2>
          <form className="form" onSubmit={onDismiss}>
            <input
              value={dismissReason}
              onChange={(e) => setDismissReason(e.target.value)}
              placeholder="Reason (required)"
              required
              maxLength={500}
            />
            <button type="submit" disabled={busy || !dismissReason.trim()}>
              Dismiss candidate
            </button>
          </form>
        </section>
      )}

      {canManage && detail.status === "DISMISSED" && (
        <section className="panel">
          <h2>Reopen</h2>
          <button type="button" disabled={busy} onClick={() => void onReopen()}>
            Reopen candidate
          </button>
        </section>
      )}

      {!canStartMerge && detail.status === "OPEN" && canMerge && detail.stale && (
        <p className="lede">Merge is unavailable while the candidate is stale.</p>
      )}
      {!canMerge && (
        <p className="lede">
          Merge requires contacts.manage and contacts.consent.manage permissions.
        </p>
      )}

      <div className="actions inline">
        <button type="button" onClick={() => navigate(`/app/contacts/${detail.contactIdA}`)}>
          Open contact A
        </button>
        <button type="button" onClick={() => navigate(`/app/contacts/${detail.contactIdB}`)}>
          Open contact B
        </button>
      </div>
    </main>
  );
}
