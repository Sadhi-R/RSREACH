import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiFetch, getTenantId, getWorkspaceId } from "../../auth";

type Campaign = {
  id: string;
  name: string;
  channel: string;
  status: string;
  purpose: string;
};

async function readError(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body.message ?? `Request failed (${res.status})`;
  } catch {
    return `Request failed (${res.status})`;
  }
}

function isEntitlementError(msg: string): boolean {
  const lower = msg.toLowerCase();
  return lower.includes("entitlement") || lower.includes("limit exceeded");
}

export function CampaignsDirectoryPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Campaign[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [canRead, setCanRead] = useState(false);
  const [canManage, setCanManage] = useState(false);
  const [name, setName] = useState("");
  const [channel, setChannel] = useState("WHATSAPP");
  const [bodyText, setBodyText] = useState("");

  async function load() {
    if (!getTenantId() || !getWorkspaceId()) {
      setError("Select a tenant and workspace first.");
      return;
    }
    const permRes = await apiFetch("customer", "/api/v1/app/permissions");
    if (permRes.ok) {
      const body = await permRes.json();
      const perms: string[] = body.permissions ?? [];
      setCanRead(perms.includes("campaigns.read"));
      setCanManage(perms.includes("campaigns.manage"));
    }
    const res = await apiFetch("customer", "/api/v1/app/campaigns?page=0&size=50");
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    const body = await res.json();
    setItems(body.items ?? []);
  }

  useEffect(() => {
    void load();
  }, []);

  async function create(e: FormEvent) {
    e.preventDefault();
    const res = await apiFetch("customer", "/api/v1/app/campaigns", {
      method: "POST",
      body: JSON.stringify({ name, channel, purpose: "MARKETING", bodyText })
    });
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    const created = (await res.json()) as Campaign;
    navigate(`/app/campaigns/${created.id}`);
  }

  if (!canRead) {
    return (
      <main className="shell">
        <p className="error">You need campaigns.read permission.</p>
        <Link to="/app">Back</Link>
      </main>
    );
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Communications</p>
      <div className="row-between">
        <h1>Campaigns</h1>
        <Link to="/app/inbox">Inbox</Link>
      </div>
      {error && (
        <>
          <p className="error">{error}</p>
          {isEntitlementError(error) && (
            <p className="error">
              <Link to="/app/billing/plans">Upgrade your plan →</Link>
            </p>
          )}
        </>
      )}
      {canManage && (
        <section className="panel">
          <h2>New campaign</h2>
          <form className="form" onSubmit={(e) => void create(e)}>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name" required />
            <select value={channel} onChange={(e) => setChannel(e.target.value)}>
              <option value="WHATSAPP">WhatsApp</option>
              <option value="EMAIL">Email</option>
            </select>
            <textarea value={bodyText} onChange={(e) => setBodyText(e.target.value)} rows={3} required />
            <button type="submit">Create</button>
          </form>
        </section>
      )}
      <section className="panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Channel</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {items.map((c) => (
              <tr key={c.id} onClick={() => navigate(`/app/campaigns/${c.id}`)}>
                <td>{c.name}</td>
                <td>{c.channel}</td>
                <td>{c.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </main>
  );
}

export function CampaignDetailPage() {
  const { campaignId } = useParams();
  const [campaign, setCampaign] = useState<Campaign | null>(null);
  const [stats, setStats] = useState<Record<string, number> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [canSend, setCanSend] = useState(false);

  async function load() {
    if (!campaignId) return;
    const permRes = await apiFetch("customer", "/api/v1/app/permissions");
    if (permRes.ok) {
      const body = await permRes.json();
      setCanSend((body.permissions ?? []).includes("campaigns.send"));
    }
    const [cRes, sRes] = await Promise.all([
      apiFetch("customer", `/api/v1/app/campaigns/${campaignId}`),
      apiFetch("customer", `/api/v1/app/campaigns/${campaignId}/stats`)
    ]);
    if (!cRes.ok) {
      setError(await readError(cRes));
      return;
    }
    setCampaign((await cRes.json()) as Campaign);
    if (sRes.ok) {
      setStats((await sRes.json()) as Record<string, number>);
    }
  }

  useEffect(() => {
    void load();
  }, [campaignId]);

  async function start() {
    if (!campaignId) return;
    const res = await apiFetch("customer", `/api/v1/app/campaigns/${campaignId}/start`, { method: "POST" });
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    await load();
  }

  return (
    <main className="shell">
      <p className="eyebrow">Campaigns</p>
      <div className="row-between">
        <h1>{campaign?.name ?? "Campaign"}</h1>
        <Link to="/app/campaigns">Back</Link>
      </div>
      {error && (
        <>
          <p className="error">{error}</p>
          {isEntitlementError(error) && (
            <p className="error">
              <Link to="/app/billing/plans">Upgrade your plan →</Link>
            </p>
          )}
        </>
      )}
      <section className="panel">
        <p>
          {campaign?.channel} · {campaign?.status} · {campaign?.purpose}
        </p>
        {stats && (
          <p className="lede">
            Recipients total {stats.total}, queued {stats.queued}, skipped {stats.skipped}, sent {stats.sent}
          </p>
        )}
        {canSend && campaign && ["DRAFT", "SCHEDULED", "PAUSED"].includes(campaign.status) && (
          <button type="button" onClick={() => void start()}>
            Start
          </button>
        )}
      </section>
    </main>
  );
}
