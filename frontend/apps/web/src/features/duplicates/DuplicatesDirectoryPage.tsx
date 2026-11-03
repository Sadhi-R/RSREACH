import { FormEvent, useCallback, useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { getTenantId, getWorkspaceId } from "../../auth";
import { ApiClientError, listCandidates, loadPermissions, startDuplicateScan } from "./api";
import { CandidateTable, EmptyState, ErrorState, LoadingState } from "./components";
import { safeUnauthorizedMessage } from "./helpers";
import type { CandidatePage } from "./types";

export function DuplicatesDirectoryPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const statusFilter = searchParams.get("status") ?? "";
  const contactIdFilter = searchParams.get("contactId") ?? "";
  const page = Number(searchParams.get("page") ?? "0") || 0;

  const [data, setData] = useState<CandidatePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [canManage, setCanManage] = useState(false);
  const [scanBusy, setScanBusy] = useState(false);
  const [scanMessage, setScanMessage] = useState<string | null>(null);

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
      setCanManage(perms.includes("contacts.manage"));
      const pageData = await listCandidates({
        status: statusFilter || undefined,
        contactId: contactIdFilter || undefined,
        page
      });
      setData(pageData);
    } catch (err) {
      setData(null);
      if (err instanceof ApiClientError) {
        setError(
          err.status === 401 || err.status === 403 || err.status === 404
            ? safeUnauthorizedMessage(err.status)
            : err.message
        );
      } else {
        setError("Unable to load duplicate candidates.");
      }
    } finally {
      setLoading(false);
    }
  }, [statusFilter, contactIdFilter, page]);

  useEffect(() => {
    void load();
  }, [load]);

  function onFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    const status = String(form.get("status") ?? "");
    const contactId = String(form.get("contactId") ?? "").trim();
    if (status) {
      next.set("status", status);
    }
    if (contactId) {
      next.set("contactId", contactId);
    }
    next.set("page", "0");
    setSearchParams(next);
  }

  async function onScan() {
    if (scanBusy) {
      return;
    }
    setScanBusy(true);
    setScanMessage(null);
    setError(null);
    try {
      const accepted = await startDuplicateScan();
      setScanMessage(`Scan accepted (${accepted.status}). Refresh the list shortly.`);
      await load();
    } catch (err) {
      if (err instanceof ApiClientError) {
        setError(
          err.status === 401 || err.status === 403
            ? safeUnauthorizedMessage(err.status)
            : err.message
        );
      } else {
        setError("Scan failed.");
      }
    } finally {
      setScanBusy(false);
    }
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Contacts</p>
      <div className="row-between">
        <h1>Duplicate candidates</h1>
        <div className="actions inline">
          <Link to="/app">Back</Link>
          <Link to="/app/contacts">Directory</Link>
          <Link to="/app/merges">Merge history</Link>
        </div>
      </div>

      <section className="panel">
        <form className="form inline-form" onSubmit={onFilter}>
          <select name="status" defaultValue={statusFilter} aria-label="Status filter">
            <option value="">All statuses</option>
            <option value="OPEN">OPEN</option>
            <option value="DISMISSED">DISMISSED</option>
            <option value="SUPERSEDED">SUPERSEDED</option>
            <option value="MERGED">MERGED</option>
          </select>
          <input
            name="contactId"
            placeholder="Filter by contact id"
            defaultValue={contactIdFilter}
            aria-label="Contact id filter"
          />
          <button type="submit">Apply</button>
          {canManage && (
            <button type="button" disabled={scanBusy} onClick={() => void onScan()}>
              {scanBusy ? "Starting scan…" : "Scan workspace"}
            </button>
          )}
        </form>

        {scanMessage && <p className="banner-ok" role="status">{scanMessage}</p>}
        {loading && <LoadingState label="Loading candidates…" />}
        {error && <ErrorState message={error} onRetry={() => void load()} />}
        {!loading && !error && data && data.items.length === 0 && (
          <EmptyState>No duplicate candidates for this filter.</EmptyState>
        )}
        {!loading && data && data.items.length > 0 && (
          <CandidateTable
            items={data.items}
            onOpen={(id) => navigate(`/app/contact-duplicates/${id}`)}
          />
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
