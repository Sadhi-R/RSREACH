import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch, getTenantId, getWorkspaceId } from "../../auth";

type Account = {
  id: string;
  provider: string;
  displayName: string;
  status: string;
};

async function readError(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body.message ?? `Request failed (${res.status})`;
  } catch {
    return `Request failed (${res.status})`;
  }
}

export function IntegrationsPage() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [provider, setProvider] = useState("whatsapp");
  const [displayName, setDisplayName] = useState("");
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [canManage, setCanManage] = useState(false);

  async function load() {
    if (!getTenantId() || !getWorkspaceId()) {
      setError("Select a tenant and workspace first.");
      return;
    }
    const permRes = await apiFetch("customer", "/api/v1/app/permissions");
    if (permRes.ok) {
      const body = await permRes.json();
      setCanManage((body.permissions ?? []).includes("integrations.manage"));
    }
    const res = await apiFetch("customer", "/api/v1/app/connected-accounts");
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setAccounts((await res.json()) as Account[]);
  }

  useEffect(() => {
    void load();
  }, []);

  async function connect(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const res = await apiFetch("customer", "/api/v1/app/connected-accounts", {
      method: "POST",
      body: JSON.stringify({
        provider,
        displayName: displayName || provider,
        accessToken: token || undefined,
        ownershipMode: "CUSTOMER"
      })
    });
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setToken("");
    setDisplayName("");
    await load();
  }

  if (!canManage) {
    return (
      <main className="shell">
        <p className="error">You need integrations.manage permission.</p>
        <Link to="/app">Back</Link>
      </main>
    );
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Communications</p>
      <div className="row-between">
        <h1>Integrations</h1>
        <Link to="/app/inbox">Inbox</Link>
      </div>
      {error && <p className="error">{error}</p>}
      <section className="panel">
        <h2>Connect account</h2>
        <p className="lede">Live Meta/Google OAuth requires operator credentials. Tokens are encrypted at rest.</p>
        <form className="form" onSubmit={(e) => void connect(e)}>
          <select value={provider} onChange={(e) => setProvider(e.target.value)}>
            <option value="whatsapp">WhatsApp</option>
            <option value="gmail">Gmail</option>
            <option value="microsoft_graph">Microsoft Graph</option>
            <option value="sendgrid">SendGrid</option>
            <option value="mock">Mock</option>
          </select>
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Display name"
          />
          <input
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="Access token (optional for mock)"
            type="password"
          />
          <button type="submit">Connect</button>
        </form>
      </section>
      <section className="panel">
        <h2>Connected</h2>
        {accounts.length === 0 ? (
          <p className="lede">No accounts connected.</p>
        ) : (
          <ul>
            {accounts.map((a) => (
              <li key={a.id}>
                {a.provider} — {a.displayName} ({a.status})
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
