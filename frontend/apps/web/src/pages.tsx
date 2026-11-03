import { FormEvent, useEffect, useState } from "react";
import { Link, Navigate, useNavigate, useSearchParams } from "react-router-dom";
import {
  apiFetch,
  clearCustomerSession,
  clearStaffSession,
  getAccessToken,
  getTenantId,
  getWorkspaceId,
  saveCustomerSession,
  saveStaffSession,
  setTenantWorkspace
} from "./auth";

export function HomePage() {
  return (
    <main className="shell">
      <p className="eyebrow">RSReach</p>
      <h1>Unified Communication Platform</h1>
      <p className="lede">Phase 1 identity and multi-tenancy portals.</p>
      <div className="actions">
        <Link to="/app/login">Customer login</Link>
        <Link to="/app/register">Customer register</Link>
        <Link to="/platform/login">Staff login</Link>
      </div>
    </main>
  );
}

export function CustomerRegisterPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const response = await fetch("/api/v1/app/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: data.get("email"),
        displayName: data.get("displayName"),
        password: data.get("password")
      })
    });
    if (!response.ok) {
      setError("Registration failed");
      return;
    }
    const tokens = await response.json();
    saveCustomerSession(tokens.accessToken, tokens.refreshToken);
    navigate("/app");
  }

  return (
    <main className="shell">
      <h1>Create customer account</h1>
      <form className="form" onSubmit={onSubmit}>
        <input name="displayName" placeholder="Display name" required />
        <input name="email" type="email" placeholder="Email" required />
        <input name="password" type="password" placeholder="Password" minLength={8} required />
        <button type="submit">Register</button>
      </form>
      {error && <p className="error">{error}</p>}
      <Link to="/app/login">Already have an account?</Link>
    </main>
  );
}

export function CustomerLoginPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const response = await fetch("/api/v1/app/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: data.get("email"),
        password: data.get("password")
      })
    });
    if (!response.ok) {
      setError("Login failed");
      return;
    }
    const tokens = await response.json();
    saveCustomerSession(tokens.accessToken, tokens.refreshToken);
    navigate("/app");
  }

  return (
    <main className="shell">
      <h1>Customer login</h1>
      <form className="form" onSubmit={onSubmit}>
        <input name="email" type="email" placeholder="Email" required />
        <input name="password" type="password" placeholder="Password" required />
        <button type="submit">Sign in</button>
      </form>
      {error && <p className="error">{error}</p>}
      <Link to="/app/register">Create an account</Link>
    </main>
  );
}

type Tenant = {
  id: string;
  name: string;
  slug: string;
  status: string;
  defaultWorkspaceId: string;
};

type Workspace = {
  id: string;
  name: string;
  slug: string;
};

export function CustomerDashboardPage() {
  const navigate = useNavigate();
  const [me, setMe] = useState<string>("");
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!getAccessToken("customer")) {
      navigate("/app/login");
      return;
    }
    void (async () => {
      const meRes = await apiFetch("customer", "/api/v1/app/me");
      if (!meRes.ok) {
        clearCustomerSession();
        navigate("/app/login");
        return;
      }
      const meJson = await meRes.json();
      setMe(meJson.displayName ?? meJson.email);
      const tenantRes = await apiFetch("customer", "/api/v1/app/tenants");
      const tenantJson = (await tenantRes.json()) as Tenant[];
      setTenants(tenantJson);
      if (!getTenantId() && tenantJson.length > 0) {
        setTenantWorkspace(tenantJson[0].id, tenantJson[0].defaultWorkspaceId);
      }
    })();
  }, [navigate]);

  useEffect(() => {
    const tenantId = getTenantId();
    if (!tenantId) {
      return;
    }
    void (async () => {
      const res = await apiFetch("customer", "/api/v1/app/workspaces");
      if (res.ok) {
        setWorkspaces((await res.json()) as Workspace[]);
      }
    })();
  }, [tenants]);

  async function createTenant(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const slug = String(data.get("slug"));
    const res = await apiFetch("customer", "/api/v1/app/tenants", {
      method: "POST",
      body: JSON.stringify({ name: data.get("name"), slug })
    });
    if (!res.ok) {
      setError("Unable to create tenant");
      return;
    }
    const tenant = (await res.json()) as Tenant;
    setTenantWorkspace(tenant.id, tenant.defaultWorkspaceId);
    setTenants((prev) => [...prev, tenant]);
  }

  return (
    <main className="shell">
      <p className="eyebrow">Customer portal</p>
      <h1>Welcome, {me || "…"}</h1>

      {/* Onboarding checklist */}
      <section className="panel">
        <h2>Get started</h2>
        <p className="lede">Complete these steps to get the most out of RSReach.</p>
        <ul className="plain-list" style={{ marginTop: "0.75rem", lineHeight: "1.9" }}>
          <li>
            <Link to="/app/integrations">→ Connect a channel (WhatsApp / Email)</Link>
          </li>
          <li>
            <Link to="/app/billing/plans">→ Activate a billing plan</Link>
          </li>
          <li>
            <Link to="/app/automations/new">→ Create your first automation</Link>
          </li>
          <li>
            <Link to="/app/contacts">→ Import or add contacts</Link>
          </li>
        </ul>
      </section>

      <section className="panel">
        <h2>Tenant</h2>
        <select
          value={getTenantId() ?? ""}
          onChange={(e) => {
            const tenant = tenants.find((t) => t.id === e.target.value);
            if (tenant) {
              setTenantWorkspace(tenant.id, tenant.defaultWorkspaceId);
              window.location.reload();
            }
          }}
        >
          <option value="" disabled>
            Select tenant
          </option>
          {tenants.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
        <form className="form" onSubmit={createTenant}>
          <input name="name" placeholder="New tenant name" required />
          <input name="slug" placeholder="slug" required />
          <button type="submit">Create tenant</button>
        </form>
      </section>

      <section className="panel">
        <h2>Workspace</h2>
        <select
          value={getWorkspaceId() ?? ""}
          onChange={(e) => {
            const tenantId = getTenantId();
            if (tenantId) {
              setTenantWorkspace(tenantId, e.target.value);
              window.location.reload();
            }
          }}
        >
          <option value="" disabled>
            Select workspace
          </option>
          {workspaces.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name}
            </option>
          ))}
        </select>
      </section>

      <section className="panel">
        <h2>Communications</h2>
        <p className="lede">Unified inbox, provider connections, and campaigns.</p>
        <div className="actions">
          <Link to="/app/inbox">Inbox</Link>
          <Link to="/app/integrations">Integrations</Link>
          <Link to="/app/campaigns">Campaigns</Link>
        </div>
      </section>
      <section className="panel">
        <h2>Automations</h2>
        <p className="lede">Trigger-based workflow automation.</p>
        <div className="actions">
          <Link to="/app/automations">Workflows</Link>
        </div>
      </section>
      <section className="panel">
        <h2>Contacts</h2>
        <p className="lede">Search, create, and manage workspace contacts.</p>
        <div className="actions">
          <Link to="/app/contacts">Open directory</Link>
          <Link to="/app/contact-groups">Contact groups</Link>
          <Link to="/app/contact-duplicates">Duplicate candidates</Link>
          <Link to="/app/merges">Merge history</Link>
        </div>
      </section>
      <section className="panel">
        <h2>Billing</h2>
        <p className="lede">Subscription, usage, and invoices.</p>
        <div className="actions">
          <Link to="/app/billing">Billing overview</Link>
          <Link to="/app/billing/plans">Plans</Link>
          <Link to="/app/billing/invoices">Invoices</Link>
        </div>
      </section>
      {error && <p className="error">{error}</p>}
    </main>
  );
}

export function StaffLoginPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [mfaToken, setMfaToken] = useState<string | null>(null);
  const [enrollmentSecret, setEnrollmentSecret] = useState<string | null>(null);

  async function onLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const response = await fetch("/api/v1/platform/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: data.get("email"),
        password: data.get("password")
      })
    });
    const body = await response.json();
    if (!response.ok) {
      setError("Staff login failed");
      return;
    }
    if (body.status === "MFA_REQUIRED") {
      setMfaToken(body.mfaToken);
      setEnrollmentSecret(body.enrollmentSecret ?? null);
      return;
    }
    saveStaffSession(body.accessToken, body.refreshToken);
    navigate("/platform");
  }

  async function onMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const response = await fetch("/api/v1/platform/auth/mfa/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        mfaToken,
        code: data.get("code")
      })
    });
    if (!response.ok) {
      setError("MFA verification failed");
      return;
    }
    const tokens = await response.json();
    saveStaffSession(tokens.accessToken, tokens.refreshToken);
    navigate("/platform");
  }

  return (
    <main className="shell">
      <h1>Platform staff login</h1>
      {!mfaToken ? (
        <form className="form" onSubmit={onLogin}>
          <input name="email" type="email" placeholder="Staff email" required />
          <input name="password" type="password" placeholder="Password" required />
          <button type="submit">Sign in</button>
        </form>
      ) : (
        <form className="form" onSubmit={onMfa}>
          {enrollmentSecret && import.meta.env.DEV && (
            <p className="lede">Enrollment secret (dev): {enrollmentSecret}</p>
          )}
          <input name="code" placeholder="6-digit MFA code" required />
          <button type="submit">Verify MFA</button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
    </main>
  );
}

export function PlatformDashboardPage() {
  const navigate = useNavigate();
  const [me, setMe] = useState("");
  const [tenants, setTenants] = useState<Array<Record<string, string>>>([]);

  useEffect(() => {
    if (!getAccessToken("staff")) {
      navigate("/platform/login");
      return;
    }
    void (async () => {
      const meRes = await apiFetch("staff", "/api/v1/platform/me");
      if (!meRes.ok) {
        clearStaffSession();
        navigate("/platform/login");
        return;
      }
      const meJson = await meRes.json();
      setMe(meJson.displayName ?? meJson.email);
      const tenantRes = await apiFetch("staff", "/api/v1/platform/tenants");
      if (tenantRes.ok) {
        setTenants(await tenantRes.json());
      }
    })();
  }, [navigate]);

  return (
    <main className="shell">
      <p className="eyebrow">Platform admin</p>
      <h1>{me || "Staff"}</h1>
      <button
        type="button"
        onClick={() => {
          clearStaffSession();
          navigate("/platform/login");
        }}
      >
        Log out
      </button>
      <section className="panel">
        <h2>Tenants</h2>
        <ul>
          {tenants.map((t) => (
            <li key={String(t.id)}>
              {String(t.name)} · {String(t.status)}
            </li>
          ))}
        </ul>
      </section>
      <section className="panel">
        <h2>Billing management</h2>
        <p className="lede">View tenant subscriptions and record manual payments.</p>
        <div className="actions">
          <Link to="/platform/billing">Platform billing</Link>
        </div>
      </section>
      <section className="panel">
        <h2>Staff access</h2>
        <p className="lede">Manage break-glass grants for tenant data access.</p>
        <div className="actions">
          <Link to="/platform/grants">Grants</Link>
        </div>
      </section>
      <section className="panel">
        <h2>Audit log</h2>
        <p className="lede">Review platform-wide action events.</p>
        <div className="actions">
          <Link to="/platform/audit">Audit events</Link>
        </div>
      </section>
    </main>
  );
}

/* -----------------------------------------------------------------------
   InviteAcceptPage  /app/invitations/accept?token=
   ----------------------------------------------------------------------- */
export function InviteAcceptPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [message, setMessage] = useState<string | null>(null);

  async function accept() {
    const token = searchParams.get("token");
    if (!token) {
      setMessage("Missing invitation token in URL.");
      setStatus("error");
      return;
    }
    setStatus("loading");
    const res = await apiFetch("customer", "/api/v1/app/invitations/accept", {
      method: "POST",
      body: JSON.stringify({ token }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      setMessage((err as { message?: string }).message ?? "Failed to accept invitation.");
      setStatus("error");
      return;
    }
    setStatus("ok");
    setTimeout(() => navigate("/app"), 1500);
  }

  return (
    <main className="shell">
      <p className="eyebrow">RSReach</p>
      <h1>Accept invitation</h1>
      {status === "idle" && (
        <div className="actions">
          <button type="button" onClick={() => void accept()}>
            Accept &amp; join tenant
          </button>
        </div>
      )}
      {status === "loading" && <p className="lede">Processing…</p>}
      {status === "ok" && (
        <p className="banner-ok">Invitation accepted! Redirecting to dashboard…</p>
      )}
      {status === "error" && <p className="error">{message}</p>}
      <Link to="/app/login">Back to login</Link>
    </main>
  );
}

/* -----------------------------------------------------------------------
   PasswordResetRequestPage  /app/password-reset
   ----------------------------------------------------------------------- */
export function PasswordResetRequestPage() {
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const res = await fetch("/api/v1/app/auth/password-reset/request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: data.get("email") }),
    });
    if (!res.ok) {
      setError("Request failed. Check the email address and try again.");
      return;
    }
    setSent(true);
  }

  return (
    <main className="shell">
      <p className="eyebrow">RSReach</p>
      <h1>Reset password</h1>
      {sent ? (
        <p className="banner-ok">
          If that email is registered, you will receive a reset link shortly.
        </p>
      ) : (
        <form className="form" onSubmit={onSubmit}>
          <input name="email" type="email" placeholder="Email address" required />
          <button type="submit">Send reset link</button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
      <Link to="/app/login">Back to login</Link>
    </main>
  );
}

/* -----------------------------------------------------------------------
   PasswordResetConfirmPage  /app/password-reset/confirm?token=
   ----------------------------------------------------------------------- */
export function PasswordResetConfirmPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const token = searchParams.get("token") ?? (data.get("token") as string);
    const res = await fetch("/api/v1/app/auth/password-reset/confirm", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token, newPassword: data.get("newPassword") }),
    });
    if (!res.ok) {
      setError("Reset failed. The link may have expired.");
      return;
    }
    setDone(true);
    setTimeout(() => navigate("/app/login"), 1500);
  }

  return (
    <main className="shell">
      <p className="eyebrow">RSReach</p>
      <h1>Set new password</h1>
      {done ? (
        <p className="banner-ok">Password updated! Redirecting to login…</p>
      ) : (
        <form className="form" onSubmit={onSubmit}>
          {!searchParams.get("token") && (
            <input name="token" placeholder="Reset token" required />
          )}
          <input
            name="newPassword"
            type="password"
            placeholder="New password (min 8 chars)"
            minLength={8}
            required
          />
          <button type="submit">Update password</button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
      <Link to="/app/login">Back to login</Link>
    </main>
  );
}

export function RequireCustomer({ children }: { children: JSX.Element }) {
  if (!getAccessToken("customer")) {
    return <Navigate to="/app/login" replace />;
  }
  return children;
}

export function RequireStaff({ children }: { children: JSX.Element }) {
  if (!getAccessToken("staff")) {
    return <Navigate to="/platform/login" replace />;
  }
  return children;
}
