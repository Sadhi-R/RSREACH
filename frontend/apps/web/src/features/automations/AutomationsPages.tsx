import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiFetch, getTenantId, getWorkspaceId } from "../../auth";

type Workflow = {
  id: string;
  name: string;
  status: string;
  triggerType: string;
  updatedAt: string;
};

type WorkflowDetail = Workflow & {
  latestVersionId: string;
  triggerConfigJson: string;
  conditionsJson: string;
  actionsJson: string;
};

type Run = {
  id: string;
  status: string;
  triggerType: string;
  createdAt: string;
  finishedAt?: string;
  errorCode?: string;
};

async function readError(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body.message ?? `Request failed (${res.status})`;
  } catch {
    return `Request failed (${res.status})`;
  }
}

export function AutomationsDirectoryPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Workflow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [canManage, setCanManage] = useState(false);

  useEffect(() => {
    void (async () => {
      if (!getTenantId() || !getWorkspaceId()) {
        setError("Select a tenant and workspace first.");
        return;
      }
      const permRes = await apiFetch("customer", "/api/v1/app/permissions");
      if (permRes.ok) {
        const body = await permRes.json();
        const perms: string[] = body.permissions ?? [];
        setCanManage(perms.includes("automations.manage"));
      }
      const res = await apiFetch("customer", "/api/v1/app/automations?page=0&size=50");
      if (!res.ok) {
        setError(await readError(res));
        return;
      }
      const body = await res.json();
      setItems(body.items ?? []);
    })();
  }, []);

  return (
    <main className="shell wide">
      <p className="eyebrow">Automations</p>
      <div className="row-between">
        <h1>Workflows</h1>
        <div className="actions">
          {canManage && (
            <button type="button" onClick={() => navigate("/app/automations/new")}>
              New workflow
            </button>
          )}
          <Link to="/app">Back</Link>
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      <section className="panel">
        {items.length === 0 ? (
          <p className="lede">No automation workflows yet.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Trigger</th>
                <th>Status</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {items.map((w) => (
                <tr key={w.id} onClick={() => navigate(`/app/automations/${w.id}`)}>
                  <td>{w.name}</td>
                  <td>{w.triggerType}</td>
                  <td>{w.status}</td>
                  <td>{w.updatedAt?.slice(0, 10)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}

const TRIGGER_TYPES = [
  "CONTACT_CREATED",
  "CONTACT_UPDATED",
  "CONTACT_GROUP_ADDED",
  "CONSENT_CHANGED",
  "INBOUND_MESSAGE",
  "CAMPAIGN_COMPLETED",
  "SCHEDULE",
  "INBOUND_WEBHOOK"
];

export function AutomationFormPage() {
  const navigate = useNavigate();
  const { workflowId } = useParams();
  const isEdit = Boolean(workflowId);

  const [name, setName] = useState("");
  const [triggerType, setTriggerType] = useState<string>("");
  const [triggerConfigJson, setTriggerConfigJson] = useState("{}");
  const [conditionsJson, setConditionsJson] = useState("[]");
  const [actionsJson, setActionsJson] = useState("[]");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isEdit) return;
    void (async () => {
      const res = await apiFetch("customer", `/api/v1/app/automations/${workflowId}`);
      if (!res.ok) {
        setError(await readError(res));
        return;
      }
      const w = (await res.json()) as WorkflowDetail;
      setName(w.name);
      setTriggerType(w.triggerType);
      setTriggerConfigJson(w.triggerConfigJson || "{}");
      setConditionsJson(w.conditionsJson || "[]");
      setActionsJson(w.actionsJson || "[]");
    })();
  }, [workflowId, isEdit]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!triggerType) {
      setError("Trigger type is required.");
      return;
    }
    const body = { name, triggerType, triggerConfigJson, conditionsJson, actionsJson };
    const res = isEdit
      ? await apiFetch("customer", `/api/v1/app/automations/${workflowId}`, {
          method: "PUT",
          body: JSON.stringify(body)
        })
      : await apiFetch("customer", "/api/v1/app/automations", {
          method: "POST",
          body: JSON.stringify(body)
        });
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    const created = (await res.json()) as WorkflowDetail;
    navigate(`/app/automations/${created.id}`);
  }

  return (
    <main className="shell">
      <p className="eyebrow">Automations</p>
      <div className="row-between">
        <h1>{isEdit ? "Edit workflow" : "New workflow"}</h1>
        <Link to="/app/automations">Back</Link>
      </div>
      {error && <p className="error">{error}</p>}
      <section className="panel">
        <form className="form" onSubmit={(e) => void onSubmit(e)}>
          <label>
            Name
            <input value={name} onChange={(e) => setName(e.target.value)} required />
          </label>
          <label>
            Trigger type
            <select
              value={triggerType}
              onChange={(e) => setTriggerType(e.target.value)}
              required
            >
              <option value="">— select trigger type —</option>
              {TRIGGER_TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </label>
          <label>
            Trigger config (JSON)
            <textarea
              value={triggerConfigJson}
              onChange={(e) => setTriggerConfigJson(e.target.value)}
              rows={3}
            />
          </label>
          <label>
            Conditions (JSON array of {`{field, op, value}`})
            <textarea
              value={conditionsJson}
              onChange={(e) => setConditionsJson(e.target.value)}
              rows={4}
            />
          </label>
          <label>
            Actions (JSON array of {`{type, params}`})
            <textarea
              value={actionsJson}
              onChange={(e) => setActionsJson(e.target.value)}
              rows={6}
            />
          </label>
          <button type="submit">{isEdit ? "Save draft" : "Create"}</button>
        </form>
      </section>
    </main>
  );
}

export function AutomationDetailPage() {
  const navigate = useNavigate();
  const { workflowId } = useParams();
  const [workflow, setWorkflow] = useState<WorkflowDetail | null>(null);
  const [runs, setRuns] = useState<Run[]>([]);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    if (!workflowId) return;
    const [wRes, rRes] = await Promise.all([
      apiFetch("customer", `/api/v1/app/automations/${workflowId}`),
      apiFetch("customer", `/api/v1/app/automations/${workflowId}/runs?page=0&size=25`)
    ]);
    if (!wRes.ok) {
      setError(await readError(wRes));
      return;
    }
    setWorkflow((await wRes.json()) as WorkflowDetail);
    if (rRes.ok) {
      const body = await rRes.json();
      setRuns(body.items ?? []);
    }
  }

  useEffect(() => {
    void load();
  }, [workflowId]);

  async function lifecycle(action: "activate" | "pause" | "archive") {
    if (!workflowId) return;
    const res = await apiFetch("customer", `/api/v1/app/automations/${workflowId}/${action}`, {
      method: "POST"
    });
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    await load();
  }

  return (
    <main className="shell">
      <p className="eyebrow">Automations</p>
      <div className="row-between">
        <h1>{workflow?.name ?? "Workflow"}</h1>
        <div className="actions">
          <Link to="/app/automations">Back</Link>
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      {workflow && (
        <>
          <section className="panel">
            <p>
              Status: <strong>{workflow.status}</strong> · Trigger: {workflow.triggerType}
            </p>
            <div className="actions">
              {workflow.status === "DRAFT" && (
                <>
                  <button type="button" onClick={() => navigate(`/app/automations/${workflowId}/edit`)}>
                    Edit
                  </button>
                  <button type="button" onClick={() => void lifecycle("activate")}>
                    Activate
                  </button>
                </>
              )}
              {workflow.status === "ACTIVE" && (
                <button type="button" onClick={() => void lifecycle("pause")}>
                  Pause
                </button>
              )}
              {workflow.status === "PAUSED" && (
                <>
                  <button type="button" onClick={() => void lifecycle("activate")}>
                    Re-activate
                  </button>
                  <button type="button" onClick={() => void lifecycle("archive")}>
                    Archive
                  </button>
                </>
              )}
              {!["ARCHIVED"].includes(workflow.status) && (
                <button type="button" onClick={() => void lifecycle("archive")}>
                  Archive
                </button>
              )}
            </div>
          </section>
          <section className="panel">
            <h2>Run history</h2>
            {runs.length === 0 ? (
              <p className="lede">No runs yet.</p>
            ) : (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Status</th>
                    <th>Trigger</th>
                    <th>Created</th>
                    <th>Finished</th>
                    <th>Error</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.map((r) => (
                    <tr key={r.id}>
                      <td>{r.status}</td>
                      <td>{r.triggerType}</td>
                      <td>{r.createdAt?.slice(0, 16)}</td>
                      <td>{r.finishedAt?.slice(0, 16) ?? "—"}</td>
                      <td>{r.errorCode ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </main>
  );
}
