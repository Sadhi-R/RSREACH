import { FormEvent, useEffect, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
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
      <button
        type="button"
        onClick={() => {
          clearCustomerSession();
          navigate("/app/login");
        }}
      >
        Log out
      </button>

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
          {enrollmentSecret && (
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
