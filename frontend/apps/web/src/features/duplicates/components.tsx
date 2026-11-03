import type { ReactNode } from "react";
import type { ChannelDisposition, ConsentCellDiff, CandidateSummary } from "./types";
import { channelActionLabel, formatInstant, statusBadgeClass } from "./helpers";

export function LoadingState({ label = "Loading…" }: { label?: string }) {
  return <p className="lede" role="status">{label}</p>;
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="lede">{children}</p>;
}

export function ErrorState({
  message,
  onRetry
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div className="banner-error" role="alert">
      <p className="error">{message}</p>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}

export function StatusBadge({ status, stale }: { status: string; stale?: boolean }) {
  return (
    <span className={statusBadgeClass(status, stale)}>
      {stale ? "STALE" : status}
      {stale && status !== "SUPERSEDED" ? ` · ${status}` : ""}
    </span>
  );
}

export function CandidateTable({
  items,
  onOpen
}: {
  items: CandidateSummary[];
  onOpen: (id: string) => void;
}) {
  return (
    <table className="data-table">
      <caption className="sr-only">Duplicate candidates</caption>
      <thead>
        <tr>
          <th>Status</th>
          <th>Match</th>
          <th>Score</th>
          <th>Contacts</th>
          <th>Last detected</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <tr key={item.id} onClick={() => onOpen(item.id)}>
            <td>
              <StatusBadge status={item.status} stale={item.stale} />
            </td>
            <td>{item.matchTypes.join(", ") || "—"}</td>
            <td>
              {item.score} · {item.confidence}
            </td>
            <td className="mono-small">
              {item.contactIdA.slice(0, 8)}… / {item.contactIdB.slice(0, 8)}…
            </td>
            <td>{formatInstant(item.lastDetectedAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function ChannelDispositionList({ channels }: { channels: ChannelDisposition[] }) {
  if (channels.length === 0) {
    return <EmptyState>No channel changes in this preview.</EmptyState>;
  }
  return (
    <table className="data-table">
      <caption className="sr-only">Channel dispositions</caption>
      <thead>
        <tr>
          <th>Type</th>
          <th>Value</th>
          <th>Status</th>
          <th>Disposition</th>
          <th>Primary</th>
        </tr>
      </thead>
      <tbody>
        {channels.map((ch) => (
          <tr key={ch.channelId} className="no-row-nav">
            <td>{ch.channelType}</td>
            <td>{ch.valueRaw || ch.valueNormalized}</td>
            <td>{ch.status}</td>
            <td>{channelActionLabel(ch.action)}</td>
            <td>{ch.primaryAfterMerge ? "Yes" : "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function ConsentImpactList({ items }: { items: ConsentCellDiff[] }) {
  if (items.length === 0) {
    return <EmptyState>No consent projection changes.</EmptyState>;
  }
  return (
    <table className="data-table">
      <caption className="sr-only">Consent impact</caption>
      <thead>
        <tr>
          <th>Channel</th>
          <th>Purpose</th>
          <th>Survivor</th>
          <th>Loser</th>
          <th>After merge</th>
        </tr>
      </thead>
      <tbody>
        {items.map((c) => (
          <tr key={`${c.channelType}:${c.purpose}`} className="no-row-nav">
            <td>{c.channelType}</td>
            <td>{c.purpose}</td>
            <td>{c.survivorBefore}</td>
            <td>{c.loserBefore}</td>
            <td>
              <strong>{c.after}</strong>
              {c.requiresFreshConsent ? " · fresh consent required" : ""}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function WizardProgress({
  steps,
  current,
  labels
}: {
  steps: string[];
  current: string;
  labels: (step: string) => string;
}) {
  const idx = steps.indexOf(current);
  return (
    <ol className="wizard-steps" aria-label="Merge wizard progress">
      {steps.map((step, i) => (
        <li key={step} className={i === idx ? "active" : i < idx ? "done" : ""}>
          <span className="wizard-step-index">{i + 1}</span>
          <span>{labels(step)}</span>
        </li>
      ))}
    </ol>
  );
}
