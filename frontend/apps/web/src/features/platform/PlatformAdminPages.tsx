import { FormEvent, useEffect, useState } from "react";
import { apiFetch } from "../../auth";

/* -----------------------------------------------------------------------
   Platform Grants Page
   ----------------------------------------------------------------------- */
type Grant = {
  id: string;
  staffId: string;
  tenantId: string;
  workspaceId?: string;
  justification: string;
  status: string;
  expiresAt: string;
  requestedAt: string;
};

export function PlatformGrantsPage() {
  const [grants, setGrants] = useState<Grant[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionMsg, setActionMsg] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    const res = await apiFetch("staff", "/api/v1/platform/grants");
    if (!res.ok) {
      setError("Failed to load grants — ensure you have platform.grants.manage and MFA verified.");
      setLoading(false);
      return;
    }
    setGrants(await res.json());
    setLoading(false);
  }

  useEffect(() => {
    void load();
  }, []);

  async function approve(id: string) {
    const res = await apiFetch("staff", `/api/v1/platform/grants/${id}/approve`, {
      method: "POST",
    });
    if (res.ok) {
      setActionMsg("Grant approved.");
      void load();
    } else {
      setActionMsg("Approve failed.");
    }
  }

  async function revoke(id: string) {
    const res = await apiFetch("staff", `/api/v1/platform/grants/${id}/revoke`, {
      method: "POST",
    });
    if (res.ok) {
      setActionMsg("Grant revoked.");
      void load();
    } else {
      setActionMsg("Revoke failed.");
    }
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Platform admin</p>
      <h1>Staff Access Grants</h1>
      {loading && <p className="lede">Loading…</p>}
      {error && <p className="error">{error}</p>}
      {actionMsg && <p className="banner-ok">{actionMsg}</p>}
      {!loading && !error && grants.length === 0 && (
        <p className="lede">No grants found.</p>
      )}
      {!loading && grants.length > 0 && (
        <section className="panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Tenant</th>
                <th>Justification</th>
                <th>Status</th>
                <th>Expires</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {grants.map((g) => (
                <tr key={g.id} className="no-row-nav">
                  <td className="mono-small">{String(g.id).slice(0, 8)}…</td>
                  <td className="mono-small">{String(g.tenantId).slice(0, 8)}…</td>
                  <td>{g.justification}</td>
                  <td>
                    <span
                      className={
                        g.status === "APPROVED"
                          ? "badge badge-ok"
                          : g.status === "PENDING"
                          ? "badge badge-warn"
                          : "badge badge-muted"
                      }
                    >
                      {g.status}
                    </span>
                  </td>
                  <td className="mono-small">
                    {g.expiresAt ? new Date(g.expiresAt).toLocaleString() : "—"}
                  </td>
                  <td>
                    <div className="actions inline">
                      {g.status === "PENDING" && (
                        <button type="button" onClick={() => void approve(g.id)}>
                          Approve
                        </button>
                      )}
                      {g.status !== "REVOKED" && (
                        <button type="button" onClick={() => void revoke(g.id)}>
                          Revoke
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
      <RequestGrantForm onDone={() => void load()} />
    </main>
  );
}

function RequestGrantForm({ onDone }: { onDone: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    const data = new FormData(e.currentTarget);
    const body: Record<string, unknown> = {
      tenantId: data.get("tenantId"),
      justification: data.get("justification"),
    };
    if (data.get("workspaceId")) body.workspaceId = data.get("workspaceId");
    const res = await apiFetch("staff", "/api/v1/platform/grants", {
      method: "POST",
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      setError("Request failed");
      return;
    }
    setSuccess(true);
    onDone();
    (e.target as HTMLFormElement).reset();
  }

  return (
    <section className="panel" style={{ marginTop: "1.5rem" }}>
      <h2>Request new grant</h2>
      {success && <p className="banner-ok">Grant requested.</p>}
      {error && <p className="error">{error}</p>}
      <form className="form" onSubmit={onSubmit}>
        <input name="tenantId" placeholder="Tenant UUID" required />
        <input name="workspaceId" placeholder="Workspace UUID (optional)" />
        <input name="justification" placeholder="Justification" required />
        <button type="submit">Request grant</button>
      </form>
    </section>
  );
}

/* -----------------------------------------------------------------------
   Platform Audit Page
   ----------------------------------------------------------------------- */
type AuditEvent = {
  id: string;
  occurredAt: string;
  actorRealm: string;
  actorId?: string;
  tenantId?: string;
  action: string;
  resourceType?: string;
  resourceId?: string;
};

export function PlatformAuditPage() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      const res = await apiFetch("staff", "/api/v1/platform/audit-events");
      if (!res.ok) {
        setError("Failed to load audit events — ensure platform.audit.read and MFA verified.");
        setLoading(false);
        return;
      }
      setEvents(await res.json());
      setLoading(false);
    })();
  }, []);

  return (
    <main className="shell wide">
      <p className="eyebrow">Platform admin</p>
      <h1>Audit Events</h1>
      {loading && <p className="lede">Loading…</p>}
      {error && <p className="error">{error}</p>}
      {!loading && !error && events.length === 0 && (
        <p className="lede">No audit events found.</p>
      )}
      {!loading && events.length > 0 && (
        <section className="panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Actor</th>
                <th>Action</th>
                <th>Resource</th>
                <th>Tenant</th>
              </tr>
            </thead>
            <tbody>
              {events.map((ev) => (
                <tr key={String(ev.id)} className="no-row-nav">
                  <td className="mono-small" style={{ whiteSpace: "nowrap" }}>
                    {new Date(ev.occurredAt).toLocaleString()}
                  </td>
                  <td className="mono-small" style={{ fontSize: "0.8rem" }}>
                    <span className="badge badge-muted">{ev.actorRealm}</span>{" "}
                    {ev.actorId ? String(ev.actorId).slice(0, 8) + "…" : "—"}
                  </td>
                  <td>{ev.action}</td>
                  <td className="mono-small" style={{ fontSize: "0.82rem" }}>
                    {ev.resourceType ? `${ev.resourceType}:${String(ev.resourceId).slice(0, 8)}` : "—"}
                  </td>
                  <td className="mono-small" style={{ fontSize: "0.8rem" }}>
                    {ev.tenantId ? String(ev.tenantId).slice(0, 8) + "…" : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </main>
  );
}
