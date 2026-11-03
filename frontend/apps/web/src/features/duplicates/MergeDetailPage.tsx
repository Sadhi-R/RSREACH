import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getTenantId, getWorkspaceId } from "../../auth";
import { ApiClientError, getMerge, loadPermissions } from "./api";
import { EmptyState, ErrorState, LoadingState } from "./components";
import { formatInstant, safeUnauthorizedMessage } from "./helpers";
import type { MergeView } from "./types";

export function MergeDetailPage() {
  const { mergeId } = useParams();
  const [merge, setMerge] = useState<MergeView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!mergeId) {
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
      if (!perms.includes("contacts.read")) {
        setError(safeUnauthorizedMessage(403));
        setMerge(null);
        return;
      }
      setMerge(await getMerge(mergeId));
    } catch (err) {
      setMerge(null);
      if (err instanceof ApiClientError) {
        setError(
          err.status === 401 || err.status === 403 || err.status === 404
            ? safeUnauthorizedMessage(err.status)
            : err.message
        );
      } else {
        setError("Unable to load merge.");
      }
    } finally {
      setLoading(false);
    }
  }, [mergeId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading) {
    return (
      <main className="shell wide">
        <LoadingState label="Loading merge…" />
      </main>
    );
  }

  if (error || !merge) {
    return (
      <main className="shell wide">
        <ErrorState message={error ?? "Merge not found."} onRetry={() => void load()} />
        <Link to="/app/merges">Back to history</Link>
      </main>
    );
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Merge detail</p>
      <div className="row-between">
        <h1>{merge.status}</h1>
        <div className="actions inline">
          <Link to="/app/merges">History</Link>
          <Link to={`/app/contacts/${merge.survivorContactId}`}>Survivor</Link>
        </div>
      </div>

      <section className="panel">
        <p className="lede">Completed {formatInstant(merge.completedAt)}</p>
        <ul className="plain-list">
          <li>
            Survivor:{" "}
            <Link to={`/app/contacts/${merge.survivorContactId}`}>{merge.survivorContactId}</Link>
          </li>
          <li>Loser: {merge.loserContactId}</li>
          <li>Actor: {merge.actorId}</li>
          <li>Preview hash: {merge.previewHash.slice(0, 16)}…</li>
        </ul>
      </section>

      <section className="panel">
        <h2>Merge items</h2>
        {merge.items.length === 0 ? (
          <EmptyState>No merge items returned.</EmptyState>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Key</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {merge.items.map((item) => (
                <tr key={item.id} className="no-row-nav">
                  <td>{item.itemType}</td>
                  <td>{item.itemKey}</td>
                  <td>{item.action}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
