import { FormEvent, useCallback, useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { getTenantId, getWorkspaceId } from "../../auth";
import { ApiClientError, listMerges, loadPermissions } from "./api";
import { EmptyState, ErrorState, LoadingState } from "./components";
import { formatInstant, safeUnauthorizedMessage } from "./helpers";
import type { MergePage } from "./types";

export function MergeHistoryPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const contactId = searchParams.get("contactId") ?? "";
  const page = Number(searchParams.get("page") ?? "0") || 0;

  const [data, setData] = useState<MergePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [canRead, setCanRead] = useState(false);

  const load = useCallback(async () => {
    if (!getTenantId() || !getWorkspaceId()) {
      setError("Select a tenant and workspace first.");
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const perms = await loadPermissions();
      const allowed = perms.includes("contacts.read");
      setCanRead(allowed);
      if (!allowed) {
        setError(safeUnauthorizedMessage(403));
        setData(null);
        return;
      }
      setData(
        await listMerges({
          contactId: contactId || undefined,
          page
        })
      );
    } catch (err) {
      setData(null);
      if (err instanceof ApiClientError) {
        setError(
          err.status === 401 || err.status === 403 || err.status === 404
            ? safeUnauthorizedMessage(err.status)
            : err.message
        );
      } else {
        setError("Unable to load merge history.");
      }
    } finally {
      setLoading(false);
    }
  }, [contactId, page]);

  useEffect(() => {
    void load();
  }, [load]);

  function onFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    const id = String(form.get("contactId") ?? "").trim();
    if (id) {
      next.set("contactId", id);
    }
    next.set("page", "0");
    setSearchParams(next);
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Contacts</p>
      <div className="row-between">
        <h1>Merge history</h1>
        <div className="actions inline">
          <Link to="/app">Back</Link>
          <Link to="/app/contact-duplicates">Duplicates</Link>
        </div>
      </div>

      <section className="panel">
        <form className="form inline-form" onSubmit={onFilter}>
          <input
            name="contactId"
            placeholder="Filter by contact id"
            defaultValue={contactId}
            aria-label="Contact id filter"
          />
          <button type="submit">Apply</button>
        </form>

        {loading && <LoadingState label="Loading merges…" />}
        {error && <ErrorState message={error} onRetry={() => void load()} />}
        {!loading && !error && canRead && data && data.items.length === 0 && (
          <EmptyState>No merges found.</EmptyState>
        )}
        {!loading && data && data.items.length > 0 && (
          <table className="data-table">
            <thead>
              <tr>
                <th>Completed</th>
                <th>Status</th>
                <th>Survivor</th>
                <th>Loser</th>
                <th>Actor</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((m) => (
                <tr key={m.id} onClick={() => navigate(`/app/merges/${m.id}`)}>
                  <td>{formatInstant(m.completedAt)}</td>
                  <td>{m.status}</td>
                  <td className="mono-small">{m.survivorContactId.slice(0, 8)}…</td>
                  <td className="mono-small">{m.loserContactId.slice(0, 8)}…</td>
                  <td className="mono-small">{m.actorId.slice(0, 8)}…</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && (
          <div className="actions inline">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => {
                const next = new URLSearchParams(searchParams);
                next.set("page", String(Math.max(0, page - 1)));
                setSearchParams(next);
              }}
            >
              Previous
            </button>
            <span className="lede">
              Page {page + 1}
              {data.hasNext ? "" : " (end)"}
            </span>
            <button
              type="button"
              disabled={!data.hasNext}
              onClick={() => {
                const next = new URLSearchParams(searchParams);
                next.set("page", String(page + 1));
                setSearchParams(next);
              }}
            >
              Next
            </button>
          </div>
        )}
      </section>
    </main>
  );
}
