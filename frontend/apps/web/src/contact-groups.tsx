import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiFetch, getTenantId, getWorkspaceId } from "./auth";

type Group = {
  id: string;
  name: string;
  slug: string;
  groupType: "STATIC" | "DYNAMIC";
  status: string;
  memberCount: number;
  workspaceId?: string;
  materializationStatus?: string;
  materializedAt?: string | null;
  filterDefinition?: Record<string, unknown> | null;
};

type GroupShare = {
  id: string;
  targetWorkspaceId: string;
  permission: string;
  revokedAt?: string | null;
};

type PageResult = { items: Group[]; page: number; size: number; hasNext: boolean };

type Member = {
  contactId: string;
  displayName: string;
  status: string;
  source: string;
  primaryEmail?: string | null;
};

type FieldDef = { fieldKey: string; label: string; dataType: string; active: boolean };

type Condition = { field: string; op: string; value: string };

async function readError(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body.message ?? `Request failed (${res.status})`;
  } catch {
    return `Request failed (${res.status})`;
  }
}

export function ContactGroupsDirectoryPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("ACTIVE");
  const [groupType, setGroupType] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [canManage, setCanManage] = useState(false);

  useEffect(() => {
    void (async () => {
      const permRes = await apiFetch("customer", "/api/v1/app/permissions");
      if (permRes.ok) {
        const body = await permRes.json();
        setCanManage((body.permissions ?? []).includes("contacts.manage"));
      }
    })();
  }, []);

  useEffect(() => {
    if (!getTenantId() || !getWorkspaceId()) {
      setError("Select a tenant and workspace first.");
      setLoading(false);
      return;
    }
    void (async () => {
      setLoading(true);
      setError(null);
      const params = new URLSearchParams({ page: String(page), size: "25", status });
      if (query.trim()) params.set("query", query.trim());
      if (groupType) params.set("groupType", groupType);
      const res = await apiFetch("customer", `/api/v1/app/contact-groups?${params}`);
      if (!res.ok) {
        setError(await readError(res));
        setData(null);
        setLoading(false);
        return;
      }
      setData((await res.json()) as PageResult);
      setLoading(false);
    })();
  }, [query, status, groupType, page]);

  return (
    <main className="shell wide">
      <p className="eyebrow">Contact groups</p>
      <div className="row-between">
        <h1>Groups</h1>
        <div className="actions inline">
          <Link to="/app">Back</Link>
          <Link to="/app/contacts">Contacts</Link>
          {canManage && <Link to="/app/contact-groups/new">Create group</Link>}
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
            setGroupType(String(form.get("groupType") ?? ""));
          }}
        >
          <input name="query" placeholder="Search groups" defaultValue={query} />
          <select name="status" defaultValue={status}>
            <option value="ACTIVE">Active</option>
            <option value="ARCHIVED">Archived</option>
          </select>
          <select name="groupType" defaultValue={groupType}>
            <option value="">All types</option>
            <option value="STATIC">Static</option>
            <option value="DYNAMIC">Dynamic</option>
          </select>
          <button type="submit">Search</button>
        </form>
        {loading && <p className="lede">Loading groups…</p>}
        {error && <p className="error">{error}</p>}
        {!loading && !error && data && data.items.length === 0 && (
          <p className="lede">No groups match this filter.</p>
        )}
        {!loading && data && data.items.length > 0 && (
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Members</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((g) => (
                <tr key={g.id} onClick={() => navigate(`/app/contact-groups/${g.id}`)}>
                  <td>{g.name}</td>
                  <td>{g.groupType}</td>
                  <td>{g.memberCount}</td>
                  <td>{g.status}</td>
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
            <span className="lede">Page {page + 1}{data.hasNext ? "" : " (end)"}</span>
            <button type="button" disabled={!data.hasNext} onClick={() => setPage((p) => p + 1)}>
              Next
            </button>
          </div>
        )}
      </section>
    </main>
  );
}

export function ContactGroupFormPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [groupType, setGroupType] = useState<"STATIC" | "DYNAMIC">("STATIC");
  const [match, setMatch] = useState<"ALL" | "ANY">("ALL");
  const [conditions, setConditions] = useState<Condition[]>([
    { field: "status", op: "eq", value: "ACTIVE" }
  ]);
  const [fieldDefs, setFieldDefs] = useState<FieldDef[]>([]);
  const [previewCount, setPreviewCount] = useState<number | null>(null);

  useEffect(() => {
    void (async () => {
      const res = await apiFetch("customer", "/api/v1/app/contact-field-defs");
      if (res.ok) setFieldDefs((await res.json()) as FieldDef[]);
    })();
  }, []);

  function filterDefinition() {
    return {
      version: 1,
      match,
      conditions: conditions.map((c) =>
        c.op === "is_null" || c.op === "is_not_null"
          ? { field: c.field, op: c.op }
          : { field: c.field, op: c.op, value: c.value }
      )
    };
  }

  async function onPreview() {
    setError(null);
    const res = await apiFetch("customer", "/api/v1/app/contact-groups/preview", {
      method: "POST",
      body: JSON.stringify({ filterDefinition: filterDefinition(), page: 0, size: 5 })
    });
    if (!res.ok) {
      setError(await readError(res));
      setPreviewCount(null);
      return;
    }
    const body = await res.json();
    setPreviewCount(body.matchCount ?? 0);
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const body: Record<string, unknown> = { name, groupType };
    if (groupType === "DYNAMIC") {
      body.filterDefinition = filterDefinition();
    }
    const res = await apiFetch("customer", "/api/v1/app/contact-groups", {
      method: "POST",
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    const created = (await res.json()) as Group;
    navigate(`/app/contact-groups/${created.id}`);
  }

  const fieldOptions = [
    { value: "display_name", label: "Display name" },
    { value: "status", label: "Status" },
    { value: "primary_email_normalized", label: "Primary email" },
    { value: "primary_phone_e164", label: "Primary phone" },
    ...fieldDefs.filter((d) => d.active).map((d) => ({ value: `custom.${d.fieldKey}`, label: d.label }))
  ];

  return (
    <main className="shell">
      <p className="eyebrow">Contact groups</p>
      <h1>Create group</h1>
      <form className="form" onSubmit={onSubmit}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Group name" required />
        <select value={groupType} onChange={(e) => setGroupType(e.target.value as "STATIC" | "DYNAMIC")}>
          <option value="STATIC">Static</option>
          <option value="DYNAMIC">Dynamic</option>
        </select>
        {groupType === "DYNAMIC" && (
          <div className="panel">
            <h2>Filter</h2>
            <select value={match} onChange={(e) => setMatch(e.target.value as "ALL" | "ANY")}>
              <option value="ALL">Match ALL conditions</option>
              <option value="ANY">Match ANY condition</option>
            </select>
            {conditions.map((c, idx) => (
              <div key={idx} className="inline-form">
                <select
                  value={c.field}
                  onChange={(e) => {
                    const next = [...conditions];
                    next[idx] = { ...c, field: e.target.value };
                    setConditions(next);
                  }}
                >
                  {fieldOptions.map((f) => (
                    <option key={f.value} value={f.value}>
                      {f.label}
                    </option>
                  ))}
                </select>
                <select
                  value={c.op}
                  onChange={(e) => {
                    const next = [...conditions];
                    next[idx] = { ...c, op: e.target.value };
                    setConditions(next);
                  }}
                >
                  <option value="eq">equals</option>
                  <option value="neq">not equals</option>
                  <option value="contains">contains</option>
                  <option value="starts_with">starts with</option>
                  <option value="is_null">is empty</option>
                  <option value="is_not_null">is not empty</option>
                </select>
                {c.op !== "is_null" && c.op !== "is_not_null" && (
                  <input
                    value={c.value}
                    onChange={(e) => {
                      const next = [...conditions];
                      next[idx] = { ...c, value: e.target.value };
                      setConditions(next);
                    }}
                    placeholder="Value"
                  />
                )}
                <button
                  type="button"
                  onClick={() => setConditions(conditions.filter((_, i) => i !== idx))}
                  disabled={conditions.length === 1}
                >
                  Remove
                </button>
              </div>
            ))}
            <button
              type="button"
              onClick={() => setConditions([...conditions, { field: "display_name", op: "contains", value: "" }])}
            >
              Add condition
            </button>
            <button type="button" onClick={() => void onPreview()}>
              Preview matches
            </button>
            {previewCount !== null && <p className="lede">Estimated matches: {previewCount}</p>}
          </div>
        )}
        <button type="submit">Create</button>
      </form>
      {error && <p className="error">{error}</p>}
      <Link to="/app/contact-groups">Cancel</Link>
    </main>
  );
}

export function ContactGroupDetailPage() {
  const { groupId } = useParams();
  const navigate = useNavigate();
  const [group, setGroup] = useState<Group | null>(null);
  const [members, setMembers] = useState<Member[]>([]);
  const [shares, setShares] = useState<GroupShare[]>([]);
  const [shareTarget, setShareTarget] = useState("");
  const [sharePermission, setSharePermission] = useState("READ");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [canManage, setCanManage] = useState(false);
  const [contactIdInput, setContactIdInput] = useState("");
  const [busy, setBusy] = useState(false);

  async function load() {
    if (!groupId) return;
    setLoading(true);
    setError(null);
    const [permRes, groupRes, memberRes] = await Promise.all([
      apiFetch("customer", "/api/v1/app/permissions"),
      apiFetch("customer", `/api/v1/app/contact-groups/${groupId}`),
      apiFetch("customer", `/api/v1/app/contact-groups/${groupId}/members?page=0&size=50`)
    ]);
    if (permRes.ok) {
      const body = await permRes.json();
      setCanManage((body.permissions ?? []).includes("contacts.manage"));
    }
    if (!groupRes.ok) {
      setError(await readError(groupRes));
      setGroup(null);
      setLoading(false);
      return;
    }
    const loaded = (await groupRes.json()) as Group;
    setGroup(loaded);
    if (memberRes.ok) {
      const body = await memberRes.json();
      setMembers(body.items ?? []);
    }
    const isHome = Boolean(loaded.workspaceId) && loaded.workspaceId === getWorkspaceId();
    if (isHome) {
      const sharesRes = await apiFetch("customer", `/api/v1/app/contact-groups/${groupId}/shares`);
      if (sharesRes.ok) {
        const body = await sharesRes.json();
        setShares((body.items ?? []) as GroupShare[]);
      } else if (sharesRes.status !== 403 && sharesRes.status !== 404) {
        setError(await readError(sharesRes));
        setShares([]);
      } else {
        setShares([]);
      }
    } else {
      setShares([]);
    }
    setLoading(false);
  }

  useEffect(() => {
    void load();
  }, [groupId]);

  async function addMember(event: FormEvent) {
    event.preventDefault();
    if (!groupId || !contactIdInput.trim()) return;
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contact-groups/${groupId}/members`, {
      method: "POST",
      body: JSON.stringify({ contactIds: [contactIdInput.trim()] })
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setContactIdInput("");
    await load();
  }

  async function removeMember(contactId: string) {
    if (!groupId) return;
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contact-groups/${groupId}/members`, {
      method: "DELETE",
      body: JSON.stringify({ contactIds: [contactId] })
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    await load();
  }

  async function refresh() {
    if (!groupId) return;
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contact-groups/${groupId}/refresh`, {
      method: "POST"
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    await load();
  }

  async function archive() {
    if (!groupId) return;
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contact-groups/${groupId}/archive`, {
      method: "POST"
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    navigate("/app/contact-groups");
  }

  async function createShare(event: FormEvent) {
    event.preventDefault();
    if (!groupId || !shareTarget.trim()) return;
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contact-groups/${groupId}/shares`, {
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
    if (!groupId) return;
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/contact-groups/${groupId}/shares/${shareId}`, {
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
        <p className="lede">Loading group…</p>
      </main>
    );
  }
  if (!group) {
    return (
      <main className="shell">
        <p className="error">{error ?? "Group not found"}</p>
        <Link to="/app/contact-groups">Back</Link>
      </main>
    );
  }

  const isHome = Boolean(group.workspaceId) && group.workspaceId === getWorkspaceId();

  return (
    <main className="shell wide">
      <p className="eyebrow">Contact groups</p>
      <div className="row-between">
        <h1>{group.name}</h1>
        <Link to="/app/contact-groups">Directory</Link>
      </div>
      <section className="panel">
        {!isHome && (
          <p className="lede">Shared into this workspace. Membership is read-only; materialization stays in the home workspace.</p>
        )}
        <p>
          <strong>Type:</strong> {group.groupType}
        </p>
        <p>
          <strong>Status:</strong> {group.status}
        </p>
        <p>
          <strong>Members:</strong> {group.memberCount}
        </p>
        {group.groupType === "DYNAMIC" && (
          <>
            <p>
              <strong>Materialization:</strong> {group.materializationStatus ?? "—"}
              {group.materializedAt ? ` · last ${group.materializedAt}` : ""}
            </p>
            <pre className="lede">{JSON.stringify(group.filterDefinition, null, 2)}</pre>
          </>
        )}
        {canManage && isHome && group.status === "ACTIVE" && (
          <div className="actions inline">
            {group.groupType === "DYNAMIC" && (
              <button type="button" disabled={busy} onClick={() => void refresh()}>
                Refresh membership
              </button>
            )}
            <button type="button" disabled={busy} onClick={() => void archive()}>
              Archive
            </button>
          </div>
        )}
      </section>
      {canManage && isHome && (
        <section className="panel">
          <h2>Shares</h2>
          <p className="lede">Share this group into another workspace. Consent is never copied with the group.</p>
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
      <section className="panel">
        <h2>Members</h2>
        {canManage && isHome && group.status === "ACTIVE" && (
          <form className="form inline-form" onSubmit={addMember}>
            <input
              value={contactIdInput}
              onChange={(e) => setContactIdInput(e.target.value)}
              placeholder="Contact UUID to add"
              required
            />
            <button type="submit" disabled={busy}>
              Add member
            </button>
          </form>
        )}
        {members.length === 0 ? (
          <p className="lede">No members yet.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Source</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {members.map((m) => (
                <tr key={m.contactId}>
                  <td>
                    <Link to={`/app/contacts/${m.contactId}`}>{m.displayName}</Link>
                  </td>
                  <td>{m.primaryEmail ?? "—"}</td>
                  <td>{m.source}</td>
                  <td>
                    {canManage && isHome && group.status === "ACTIVE" && (
                      <button type="button" disabled={busy} onClick={() => void removeMember(m.contactId)}>
                        Remove
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {error && <p className="error">{error}</p>}
      </section>
    </main>
  );
}
