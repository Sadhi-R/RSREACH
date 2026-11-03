import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiFetch, getTenantId, getWorkspaceId } from "./auth";

type Channel = {
  id?: string;
  channelType: string;
  value: string;
  valueNormalized?: string;
  primary: boolean;
  label?: string | null;
  status?: string;
};

type Contact = {
  id: string;
  displayName: string;
  status: string;
  homeWorkspaceId?: string;
  primaryEmail?: string | null;
  primaryPhone?: string | null;
  customFields?: Record<string, unknown>;
  channels: Channel[];
  createdAt?: string;
  updatedAt?: string;
  archivedAt?: string | null;
  /** HOME | READ | WRITE — present on detail GET */
  access?: string | null;
};

type ContactShare = {
  id: string;
  targetWorkspaceId: string;
  permission: string;
  revokedAt?: string | null;
};

type PageResult = {
  items: Contact[];
  page: number;
  size: number;
  hasNext: boolean;
};

type FieldDef = {
  id: string;
  fieldKey: string;
  label: string;
  dataType: string;
  required: boolean;
  active: boolean;
};

async function readError(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body.message ?? `Request failed (${res.status})`;
  } catch {
    return `Request failed (${res.status})`;
  }
}

export function ContactsDirectoryPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("ACTIVE");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [canManage, setCanManage] = useState(false);
  const [canImport, setCanImport] = useState(false);
  const [canExport, setCanExport] = useState(false);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    if (!getTenantId() || !getWorkspaceId()) {
      setError("Select a tenant and workspace first.");
      setLoading(false);
      return;
    }
    void (async () => {
      const permRes = await apiFetch("customer", "/api/v1/app/permissions");
      if (permRes.ok) {
        const body = await permRes.json();
        const perms: string[] = body.permissions ?? [];
        setCanManage(perms.includes("contacts.manage"));
        setCanImport(perms.includes("contacts.import"));
        setCanExport(perms.includes("contacts.export"));
      }
    })();
  }, []);

  useEffect(() => {
    if (!getTenantId() || !getWorkspaceId()) {
      return;
    }
    void (async () => {
      setLoading(true);
      setError(null);
      const params = new URLSearchParams({
        page: String(page),
        size: "25",
        status
      });
      if (query.trim()) {
        params.set("query", query.trim());
      }
      const res = await apiFetch("customer", `/api/v1/app/contacts?${params}`);
      if (!res.ok) {
        setError(await readError(res));
        setData(null);
        setLoading(false);
        return;
      }
      setData((await res.json()) as PageResult);
      setLoading(false);
    })();
  }, [query, status, page]);

  return (
    <main className="shell wide">
      <p className="eyebrow">Contacts</p>
      <div className="row-between">
        <h1>Directory</h1>
        <div className="actions inline">
          <Link to="/app">Back</Link>
          <Link to="/app/contact-duplicates">Duplicates</Link>
          <Link to="/app/merges">Merges</Link>
          {canImport && <Link to="/app/contacts/import">Import CSV</Link>}
          {canExport && (
            <button
              type="button"
              disabled={exporting}
              onClick={() => {
                void (async () => {
                  setExporting(true);
                  setError(null);
                  try {
                    const params = new URLSearchParams({ status });
                    if (query.trim()) {
                      params.set("query", query.trim());
                    }
                    const res = await apiFetch("customer", `/api/v1/app/contacts/export?${params}`);
                    if (!res.ok && res.status !== 202) {
                      setError(await readError(res));
                      return;
                    }
                    const contentType = res.headers.get("Content-Type") ?? "";
                    if (res.status === 202 || contentType.includes("application/json")) {
                      const body = (await res.json()) as { jobId?: string; async?: boolean };
                      if (!body.jobId) {
                        setError("Export job was not created.");
                        return;
                      }
                      let ready = false;
                      for (let attempt = 0; attempt < 40 && !ready; attempt++) {
                        await new Promise((r) => window.setTimeout(r, 750));
                        const poll = await apiFetch(
                          "customer",
                          `/api/v1/app/contacts/export/jobs/${body.jobId}`
                        );
                        if (!poll.ok && poll.status !== 202) {
                          setError(await readError(poll));
                          return;
                        }
                        const statusBody = (await poll.json()) as { ready?: boolean };
                        ready = Boolean(statusBody.ready);
                      }
                      if (!ready) {
                        setError("Export is still processing. Try again shortly.");
                        return;
                      }
                      const download = await apiFetch(
                        "customer",
                        `/api/v1/app/contacts/export/jobs/${body.jobId}?download=true`
                      );
                      if (!download.ok) {
                        setError(await readError(download));
                        return;
                      }
                      const blob = await download.blob();
                      const url = URL.createObjectURL(blob);
                      const a = document.createElement("a");
                      a.href = url;
                      a.download = "contacts-export.csv";
                      a.click();
                      URL.revokeObjectURL(url);
                      return;
                    }
                    const blob = await res.blob();
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement("a");
                    a.href = url;
                    a.download = "contacts-export.csv";
                    a.click();
                    URL.revokeObjectURL(url);
                  } finally {
                    setExporting(false);
                  }
                })();
              }}
            >
              {exporting ? "Exporting…" : "Export CSV"}
            </button>
          )}
          {canManage && <Link to="/app/contacts/new">Create contact</Link>}
        </div>
      </div>

      <section className="panel">
        <form
          className="form inline-form"
          onSubmit={(e) => {
            e.preventDefault();
            const form = new FormData(e.currentTarget);
            setPage(0);
            setQuery(String(form.get("query") ?? ""));
            setStatus(String(form.get("status") ?? "ACTIVE"));
          }}
        >
          <input name="query" placeholder="Search name, email, phone" defaultValue={query} />
          <select name="status" defaultValue={status}>
            <option value="ACTIVE">Active</option>
            <option value="ARCHIVED">Archived</option>
          </select>
          <button type="submit">Search</button>
        </form>

        {loading && <p className="lede">Loading contacts…</p>}
        {error && <p className="error">{error}</p>}
        {!loading && !error && data && data.items.length === 0 && (
          <p className="lede">No contacts match this filter.</p>
        )}
        {!loading && data && data.items.length > 0 && (
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Phone</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((c) => (
                <tr key={c.id} onClick={() => navigate(`/app/contacts/${c.id}`)}>
                  <td>
                    {c.displayName}
                    {c.homeWorkspaceId &&
                      getWorkspaceId() &&
                      c.homeWorkspaceId !== getWorkspaceId() &&
                      " (shared)"}
                  </td>
                  <td>{c.primaryEmail ?? "—"}</td>
                  <td>{c.primaryPhone ?? "—"}</td>
                  <td>{c.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && (
          <div className="actions inline">
            <button type="button" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </button>
            <span className="lede">
              Page {page + 1}
              {data.hasNext ? "" : " (end)"}
            </span>
            <button type="button" disabled={!data.hasNext} onClick={() => setPage((p) => p + 1)}>
              Next
            </button>
          </div>
        )}
      </section>
    </main>
  );
}

export function ContactFormPage() {
  const navigate = useNavigate();
  const { contactId } = useParams();
  const isEdit = Boolean(contactId);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(isEdit);
  const [fieldDefs, setFieldDefs] = useState<FieldDef[]>([]);
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [customFields, setCustomFields] = useState<Record<string, string>>({});

  useEffect(() => {
    void (async () => {
      const defsRes = await apiFetch("customer", "/api/v1/app/contact-field-defs");
      if (defsRes.ok) {
        setFieldDefs((await defsRes.json()) as FieldDef[]);
      }
      if (!isEdit || !contactId) {
        setLoading(false);
        return;
      }
      const res = await apiFetch("customer", `/api/v1/app/contacts/${contactId}`);
      if (!res.ok) {
        setError(await readError(res));
        setLoading(false);
        return;
      }
      const contact = (await res.json()) as Contact;
      setDisplayName(contact.displayName);
      setEmail(contact.primaryEmail ?? "");
      setPhone(contact.primaryPhone ?? "");
      const fields: Record<string, string> = {};
      Object.entries(contact.customFields ?? {}).forEach(([k, v]) => {
        fields[k] = String(v);
      });
      setCustomFields(fields);
      setLoading(false);
    })();
  }, [contactId, isEdit]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const channels: Array<Record<string, unknown>> = [];
    if (email.trim()) {
      channels.push({ channelType: "EMAIL", value: email.trim(), primary: true });
    }
    if (phone.trim()) {
      channels.push({ channelType: "PHONE", value: phone.trim(), primary: true });
    }
    const body = {
      displayName,
      channels,
      customFields: Object.fromEntries(
        Object.entries(customFields).filter(([, v]) => v !== undefined && v !== "")
      )
    };
    const res = await apiFetch(
      "customer",
      isEdit ? `/api/v1/app/contacts/${contactId}` : "/api/v1/app/contacts",
      {
        method: isEdit ? "PATCH" : "POST",
        body: JSON.stringify(body)
      }
    );
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    const saved = (await res.json()) as Contact;
    navigate(`/app/contacts/${saved.id}`);
  }

  if (loading) {
    return (
      <main className="shell">
        <p className="lede">Loading…</p>
      </main>
    );
  }

  return (
    <main className="shell">
      <p className="eyebrow">Contacts</p>
      <h1>{isEdit ? "Edit contact" : "Create contact"}</h1>
      <form className="form" onSubmit={onSubmit}>
        <input
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          placeholder="Display name"
          required
        />
        <input
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="Email"
          type="email"
        />
        <input
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          placeholder="Phone (E.164, e.g. +9198…)"
        />
        {fieldDefs
          .filter((d) => d.active)
          .map((def) => (
            <label key={def.id} className="field-label">
              {def.label}
              {def.required ? " *" : ""}
              <input
                value={customFields[def.fieldKey] ?? ""}
                onChange={(e) =>
                  setCustomFields((prev) => ({ ...prev, [def.fieldKey]: e.target.value }))
                }
                required={def.required}
                placeholder={def.dataType}
              />
            </label>
          ))}
        <button type="submit">{isEdit ? "Save changes" : "Create"}</button>
      </form>
      {error && <p className="error">{error}</p>}
      <Link to={isEdit ? `/app/contacts/${contactId}` : "/app/contacts"}>Cancel</Link>
    </main>
  );
}

type ConsentItem = {
  channelType: string;
  purpose: string;
  status: string;
  source?: string;
  updatedAt?: string;
};

type ConsentEvent = {
  id: string;
  channelType: string;
  purpose: string;
  previousStatus?: string | null;
  newStatus: string;
  eventType: string;
  occurredAt: string;
};

type Suppression = {
  id: string;
  channelType: string;
  valueRaw: string;
  reason: string;
  releasedAt?: string | null;
};

export function ContactDetailPage() {
  const { contactId } = useParams();
  const navigate = useNavigate();
  const [contact, setContact] = useState<Contact | null>(null);
  const [shares, setShares] = useState<ContactShare[]>([]);
  const [shareTarget, setShareTarget] = useState("");
  const [sharePermission, setSharePermission] = useState("READ");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [canManage, setCanManage] = useState(false);
  const [canConsentManage, setCanConsentManage] = useState(false);
  const [busy, setBusy] = useState(false);
  const [consents, setConsents] = useState<ConsentItem[]>([]);
  const [history, setHistory] = useState<ConsentEvent[]>([]);
  const [suppressions, setSuppressions] = useState<Suppression[]>([]);
  const [evidenceNote, setEvidenceNote] = useState("");
  const [suppressionValue, setSuppressionValue] = useState("");
  const [suppressionReason, setSuppressionReason] = useState("");

  async function load() {
    if (!contactId) {
      return;
    }
    setLoading(true);
    setError(null);
    const [permRes, res] = await Promise.all([
      apiFetch("customer", "/api/v1/app/permissions"),
      apiFetch("customer", `/api/v1/app/contacts/${contactId}`)
    ]);
    if (permRes.ok) {
      const body = await permRes.json();
      const perms: string[] = body.permissions ?? [];
      setCanManage(perms.includes("contacts.manage"));
      setCanConsentManage(perms.includes("contacts.consent.manage"));
    }
    if (!res.ok) {
      setError(await readError(res));
      setContact(null);
      setLoading(false);
      return;
    }
    const loaded = (await res.json()) as Contact;
    setContact(loaded);
    const isHome =
      Boolean(loaded.homeWorkspaceId) && loaded.homeWorkspaceId === getWorkspaceId();

    const suppressionRes = await apiFetch(
      "customer",
      "/api/v1/app/suppressions?activeOnly=true&page=0&size=50"
    );
    if (suppressionRes.ok) {
      const body = await suppressionRes.json();
      setSuppressions(body.items ?? []);
    }

    if (isHome) {
      const [consentRes, historyRes, sharesRes] = await Promise.all([
        apiFetch("customer", `/api/v1/app/contacts/${contactId}/consents`),
        apiFetch("customer", `/api/v1/app/contacts/${contactId}/consents/history?page=0&size=20`),
        apiFetch("customer", `/api/v1/app/contacts/${contactId}/shares`)
      ]);
      if (sharesRes.ok) {
        const body = await sharesRes.json();
        setShares((body.items ?? []) as ContactShare[]);
      } else if (sharesRes.status !== 403 && sharesRes.status !== 404) {
        setError(await readError(sharesRes));
        setShares([]);
      } else {
        setShares([]);
      }
      if (consentRes.ok) {
        const body = await consentRes.json();
        setConsents(body.items ?? []);
      } else {
        setConsents([]);
      }
      if (historyRes.ok) {
        const body = await historyRes.json();
        setHistory(body.items ?? []);
      } else {
        setHistory([]);
      }
    } else {
      setShares([]);
      setConsents([]);
      setHistory([]);
    }
    setLoading(false);
  }

  useEffect(() => {
    void load();
  }, [contactId]);

  async function archiveOrRestore() {
    if (!contact || !contactId) {
      return;
    }
    setBusy(true);
    setError(null);
    const action = contact.status === "ARCHIVED" ? "restore" : "archive";
    const res = await apiFetch("customer", `/api/v1/app/contacts/${contactId}/${action}`, {
      method: "POST"
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setContact((await res.json()) as Contact);
  }

  async function changeConsent(channelType: string, purpose: string, status: string) {
    if (!contactId) return;
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contacts/${contactId}/consents`, {
      method: "PUT",
      body: JSON.stringify({
        channelType,
        purpose,
        status,
        source: "MANUAL",
        evidenceNote: evidenceNote || undefined
      })
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    await load();
  }

  async function createSuppression() {
    setBusy(true);
    setError(null);
    const channelType = suppressionValue.includes("@") ? "EMAIL" : "WHATSAPP";
    const res = await apiFetch("customer", "/api/v1/app/suppressions", {
      method: "POST",
      body: JSON.stringify({
        channelType,
        value: suppressionValue,
        reason: suppressionReason
      })
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setSuppressionValue("");
    setSuppressionReason("");
    await load();
  }

  async function releaseSuppression(id: string) {
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/suppressions/${id}`, { method: "DELETE" });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    await load();
  }

  async function createShare(e: FormEvent) {
    e.preventDefault();
    if (!contactId || !shareTarget.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contacts/${contactId}/shares`, {
      method: "POST",
      body: JSON.stringify({
        targetWorkspaceId: shareTarget.trim(),
        permission: sharePermission
      })
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setShareTarget("");
    await load();
  }

  async function revokeShare(shareId: string) {
    if (!contactId) {
      return;
    }
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contacts/${contactId}/shares/${shareId}`, {
      method: "DELETE"
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    await load();
  }

  if (loading) {
    return (
      <main className="shell">
        <p className="lede">Loading contact…</p>
      </main>
    );
  }

  if (!contact) {
    return (
      <main className="shell">
        <p className="error">{error ?? "Contact not found"}</p>
        <Link to="/app/contacts">Back to directory</Link>
      </main>
    );
  }

  const isHome =
    Boolean(contact.homeWorkspaceId) && contact.homeWorkspaceId === getWorkspaceId();
  const canMutateContact =
    canManage && (isHome || contact.access === "WRITE" || contact.access === "HOME");

  return (
    <main className="shell">
      <p className="eyebrow">Contacts</p>
      <div className="row-between">
        <h1>{contact.displayName}</h1>
        <div className="actions inline">
          <Link to="/app/contacts">Directory</Link>
          <Link to={`/app/contact-duplicates?contactId=${contact.id}`}>Duplicates</Link>
          <Link to={`/app/merges?contactId=${contact.id}`}>Merges</Link>
        </div>
      </div>
      <section className="panel">
        {!isHome && (
          <p className="lede">Shared into this workspace (home workspace owns consent).</p>
        )}
        <p>
          <strong>Status:</strong> {contact.status}
        </p>
        <p>
          <strong>Email:</strong> {contact.primaryEmail ?? "—"}
        </p>
        <p>
          <strong>Phone:</strong> {contact.primaryPhone ?? "—"}
        </p>
        {contact.channels?.length > 0 && (
          <div>
            <h2>Channels</h2>
            <ul>
              {contact.channels.map((ch) => (
                <li key={ch.id ?? `${ch.channelType}-${ch.value}`}>
                  {ch.channelType}: {ch.value}
                  {ch.primary ? " (primary)" : ""}
                </li>
              ))}
            </ul>
          </div>
        )}
        {contact.customFields && Object.keys(contact.customFields).length > 0 && (
          <div>
            <h2>Custom fields</h2>
            <ul>
              {Object.entries(contact.customFields).map(([k, v]) => (
                <li key={k}>
                  {k}: {String(v)}
                </li>
              ))}
            </ul>
          </div>
        )}
        {canMutateContact && (
          <div className="actions inline">
            <button type="button" onClick={() => navigate(`/app/contacts/${contact.id}/edit`)}>
              Edit
            </button>
            <button type="button" disabled={busy} onClick={() => void archiveOrRestore()}>
              {contact.status === "ARCHIVED" ? "Restore" : "Archive"}
            </button>
          </div>
        )}
        {error && <p className="error">{error}</p>}
      </section>

      {canManage && isHome && (
          <section className="panel">
            <h2>Shares</h2>
            <p className="lede">Share this contact into another workspace in the same tenant. Consent is never copied.</p>
            <form className="form inline-form" onSubmit={(e) => void createShare(e)}>
              <input
                value={shareTarget}
                onChange={(e) => setShareTarget(e.target.value)}
                placeholder="Target workspace UUID"
                required
              />
              <select value={sharePermission} onChange={(e) => setSharePermission(e.target.value)}>
                <option value="READ">READ</option>
                <option value="WRITE">WRITE</option>
              </select>
              <button type="submit" disabled={busy}>
                Share
              </button>
            </form>
            {shares.filter((s) => !s.revokedAt).length === 0 ? (
              <p className="lede">No active shares.</p>
            ) : (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Target workspace</th>
                    <th>Permission</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {shares
                    .filter((s) => !s.revokedAt)
                    .map((s) => (
                      <tr key={s.id}>
                        <td>{s.targetWorkspaceId}</td>
                        <td>{s.permission}</td>
                        <td>
                          <button type="button" disabled={busy} onClick={() => void revokeShare(s.id)}>
                            Revoke
                          </button>
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            )}
          </section>
      )}

      {isHome ? (
        <section className="panel">
          <h2>Consent</h2>
          <p className="lede">
            Consent is channel- and purpose-specific. Group membership never grants consent. Missing consent is not
            treated as granted. Consent can only be managed in the contact home workspace.
          </p>
          {canConsentManage && (
            <label className="field-label">
              Evidence note (required for MANUAL opt-in)
              <input
                value={evidenceNote}
                onChange={(e) => setEvidenceNote(e.target.value)}
                placeholder="Short operator note / source reference"
              />
            </label>
          )}
          {consents.length === 0 ? (
            <p className="lede">No consent rows loaded.</p>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>Channel</th>
                  <th>Purpose</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {consents.map((c) => (
                  <tr key={`${c.channelType}-${c.purpose}`}>
                    <td>{c.channelType}</td>
                    <td>{c.purpose}</td>
                    <td>{c.status}</td>
                    <td>
                      {canConsentManage && (
                        <div className="actions inline">
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => void changeConsent(c.channelType, c.purpose, "OPTED_IN")}
                          >
                            Grant
                          </button>
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => void changeConsent(c.channelType, c.purpose, "OPTED_OUT")}
                          >
                            Withdraw
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <h2>Consent history</h2>
          {history.length === 0 ? (
            <p className="lede">No consent events yet.</p>
          ) : (
            <ul>
              {history.map((h) => (
                <li key={h.id}>
                  {h.occurredAt}: {h.channelType}/{h.purpose} {h.previousStatus ?? "∅"} → {h.newStatus} ({h.eventType})
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : (
        <section className="panel">
          <h2>Consent</h2>
          <p className="lede">Consent is owned by the home workspace and is not available for shared-in contacts.</p>
        </section>
      )}

      <section className="panel">
        <h2>Suppressions (workspace)</h2>
        <p className="lede">Active suppressions block eligibility regardless of consent or group membership.</p>
        {canConsentManage && (
          <form
            className="form inline-form"
            onSubmit={(e) => {
              e.preventDefault();
              void createSuppression();
            }}
          >
            <input
              value={suppressionValue}
              onChange={(e) => setSuppressionValue(e.target.value)}
              placeholder="Email or +E.164 phone"
              required
            />
            <input
              value={suppressionReason}
              onChange={(e) => setSuppressionReason(e.target.value)}
              placeholder="Reason"
              required
            />
            <button type="submit" disabled={busy}>
              Suppress email
            </button>
          </form>
        )}
        {suppressions.length === 0 ? (
          <p className="lede">No active suppressions.</p>
        ) : (
          <ul>
            {suppressions.map((s) => (
              <li key={s.id}>
                {s.channelType}: {s.valueRaw} — {s.reason}
                {canConsentManage && !s.releasedAt && (
                  <>
                    {" "}
                    <button type="button" disabled={busy} onClick={() => void releaseSuppression(s.id)}>
                      Release
                    </button>
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
