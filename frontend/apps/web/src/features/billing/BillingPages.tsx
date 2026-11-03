import { FormEvent, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiFetch, getTenantId } from "../../auth";

type Subscription = {
  id: string;
  status: string;
  billingPeriod: string;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  trialEndsAt?: string;
  cancelAtPeriodEnd: boolean;
};

type UsageMeter = {
  meterCode: string;
  usedQuantity: number;
  limitValue?: number;
  warningFlag: boolean;
};

type Invoice = {
  id: string;
  invoiceNumber: string;
  status: string;
  totalPaise: number;
  subtotalPaise: number;
  taxPaise: number;
  currency: string;
  issuedAt?: string;
  dueAt?: string;
  periodStart?: string;
  periodEnd?: string;
};

type Payment = {
  id: string;
  method: string;
  status: string;
  amountPaise: number;
  currency: string;
  provider?: string;
  providerOrderId?: string;
  providerPaymentId?: string;
  externalReference?: string;
  paidAt?: string;
  createdAt: string;
};

type CheckoutResult = {
  subscriptionId: string;
  invoiceId: string;
  paymentId: string;
  razorpayOrderId: string;
  keyId: string;
  amountPaise: number;
  currency: string;
};

type PlanVersion = {
  id: string;
  versionNumber: number;
  billingPeriod: string;
  pricePaise: number;
  currency: string;
  trialDays: number;
  entitlements: Array<{ meterCode: string; limitValue?: number }>;
};

type Plan = {
  id: string;
  code: string;
  name: string;
  description: string;
  active: boolean;
  versions: PlanVersion[];
};

async function readError(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body.message ?? `Request failed (${res.status})`;
  } catch {
    return `Request failed (${res.status})`;
  }
}

function paise2rupees(paise: number): string {
  return "₹" + (paise / 100).toFixed(2);
}

export function BillingOverviewPage() {
  const [sub, setSub] = useState<Subscription | null>(null);
  const [usage, setUsage] = useState<UsageMeter[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [activating, setActivating] = useState(false);
  const [checkoutResult, setCheckoutResult] = useState<CheckoutResult | null>(null);
  const [checkingOut, setCheckingOut] = useState(false);

  async function load() {
    if (!getTenantId()) {
      setError("Select a tenant first.");
      return;
    }
    const [subRes, usageRes] = await Promise.all([
      apiFetch("customer", "/api/v1/app/billing/subscription"),
      apiFetch("customer", "/api/v1/app/billing/usage")
    ]);
    if (subRes.ok) {
      setSub((await subRes.json()) as Subscription);
    } else if (subRes.status === 404) {
      setSub(null);
    } else {
      setError(await readError(subRes));
    }
    if (usageRes.ok) {
      setUsage((await usageRes.json()) as UsageMeter[]);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function activateFree(e: FormEvent) {
    e.preventDefault();
    setActivating(true);
    const res = await apiFetch("customer", "/api/v1/app/billing/activate-free", { method: "POST" });
    setActivating(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    await load();
  }

  async function startCheckout(e: FormEvent) {
    e.preventDefault();
    setCheckingOut(true);
    setError(null);
    setCheckoutResult(null);
    const res = await apiFetch("customer", "/api/v1/app/billing/checkout", { method: "POST" });
    setCheckingOut(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setCheckoutResult((await res.json()) as CheckoutResult);
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Billing</p>
      <div className="row-between">
        <h1>Subscription &amp; Usage</h1>
        <div className="actions">
          <Link to="/app/billing/plans">Plans</Link>
          <Link to="/app/billing/invoices">Invoices</Link>
          <Link to="/app/billing/payments">Payments</Link>
          <Link to="/app">Back</Link>
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      <section className="panel">
        <h2>Subscription</h2>
        {sub ? (
          <>
            <dl>
              <dt>Status</dt>
              <dd>{sub.status}</dd>
              <dt>Billing period</dt>
              <dd>{sub.billingPeriod}</dd>
              <dt>Current period</dt>
              <dd>{sub.currentPeriodStart?.slice(0, 10)} → {sub.currentPeriodEnd?.slice(0, 10)}</dd>
              {sub.trialEndsAt && (
                <>
                  <dt>Trial ends</dt>
                  <dd>{sub.trialEndsAt.slice(0, 10)}</dd>
                </>
              )}
              {sub.cancelAtPeriodEnd && <dd className="error">Cancels at period end</dd>}
            </dl>
            <form onSubmit={(e) => void startCheckout(e)} style={{ marginTop: "0.75rem" }}>
              <button type="submit" disabled={checkingOut}>
                {checkingOut ? "Creating order…" : "Checkout (pay now)"}
              </button>
            </form>
            {checkoutResult && (
              <div className="panel" style={{ marginTop: "1rem", background: "var(--color-surface-raised, #f9f9f9)" }}>
                <h3>Checkout order created</h3>
                <dl>
                  <dt>Razorpay Order ID</dt>
                  <dd><code>{checkoutResult.razorpayOrderId}</code></dd>
                  <dt>Key ID</dt>
                  <dd><code>{checkoutResult.keyId}</code></dd>
                  <dt>Amount</dt>
                  <dd>{paise2rupees(checkoutResult.amountPaise)} ({checkoutResult.currency})</dd>
                  <dt>Invoice ID</dt>
                  <dd>
                    <Link to={`/app/billing/invoices/${checkoutResult.invoiceId}`}>
                      {checkoutResult.invoiceId}
                    </Link>
                  </dd>
                </dl>
              </div>
            )}
          </>
        ) : (
          <div>
            <p className="lede">No active subscription.</p>
            <form onSubmit={(e) => void activateFree(e)}>
              <button type="submit" disabled={activating}>Activate Free Plan</button>
            </form>
          </div>
        )}
      </section>
      {usage.length > 0 && (
        <section className="panel">
          <h2>Usage this period</h2>
          <table className="data-table">
            <thead>
              <tr>
                <th>Meter</th>
                <th>Used</th>
                <th>Limit</th>
              </tr>
            </thead>
            <tbody>
              {usage.map((u) => (
                <tr key={u.meterCode} style={{ color: u.warningFlag ? "var(--color-warning, orange)" : undefined }}>
                  <td>{u.meterCode}</td>
                  <td>{u.usedQuantity}</td>
                  <td>{u.limitValue != null ? u.limitValue : "Unlimited"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </main>
  );
}

export function BillingPlansPage() {
  const [plans, setPlans] = useState<Plan[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      const res = await apiFetch("customer", "/api/v1/app/billing/plans");
      if (!res.ok) {
        setError(await readError(res));
        return;
      }
      setPlans((await res.json()) as Plan[]);
    })();
  }, []);

  async function choosePlan(planVersionId: string, action: "trial" | "change-plan") {
    const endpoint = action === "trial" ? "/api/v1/app/billing/trial" : "/api/v1/app/billing/change-plan";
    const res = await apiFetch("customer", endpoint, {
      method: "POST",
      body: JSON.stringify({ planVersionId })
    });
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setMsg(`Plan updated successfully.`);
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Billing</p>
      <div className="row-between">
        <h1>Plans</h1>
        <Link to="/app/billing">Back to billing</Link>
      </div>
      {error && <p className="error">{error}</p>}
      {msg && <p className="lede">{msg}</p>}
      {plans.map((plan) => (
        <section key={plan.id} className="panel">
          <h2>{plan.name} {plan.code === "FREE" && <span className="badge">Free</span>}</h2>
          <p className="lede">{plan.description}</p>
          {plan.versions.map((v) => (
            <div key={v.id} style={{ marginBottom: "1rem" }}>
              <strong>{v.billingPeriod}</strong> · {paise2rupees(v.pricePaise)}/period
              {v.trialDays > 0 && <span> · {v.trialDays} day trial</span>}
              <ul>
                {v.entitlements.map((e) => (
                  <li key={e.meterCode}>{e.meterCode}: {e.limitValue != null ? e.limitValue : "Unlimited"}</li>
                ))}
              </ul>
              <div className="actions">
                {v.trialDays > 0 && (
                  <button type="button" onClick={() => void choosePlan(v.id, "trial")}>
                    Start trial
                  </button>
                )}
                <button type="button" onClick={() => void choosePlan(v.id, "change-plan")}>
                  Select plan
                </button>
              </div>
            </div>
          ))}
        </section>
      ))}
    </main>
  );
}

export function BillingInvoicesPage() {
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      const res = await apiFetch("customer", "/api/v1/app/billing/invoices");
      if (!res.ok) {
        setError(await readError(res));
        return;
      }
      setInvoices((await res.json()) as Invoice[]);
    })();
  }, []);

  return (
    <main className="shell wide">
      <p className="eyebrow">Billing</p>
      <div className="row-between">
        <h1>Invoices</h1>
        <Link to="/app/billing">Back to billing</Link>
      </div>
      {error && <p className="error">{error}</p>}
      <section className="panel">
        {invoices.length === 0 ? (
          <p className="lede">No invoices yet.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Invoice #</th>
                <th>Status</th>
                <th>Amount</th>
                <th>Issued</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {invoices.map((inv) => (
                <tr key={inv.id}>
                  <td>{inv.invoiceNumber}</td>
                  <td>{inv.status}</td>
                  <td>{paise2rupees(inv.totalPaise)}</td>
                  <td>{inv.issuedAt?.slice(0, 10) ?? "—"}</td>
                  <td>
                    <Link to={`/app/billing/invoices/${inv.id}`}>View</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}

export function InvoiceDetailPage() {
  const { invoiceId } = useParams<{ invoiceId: string }>();
  const [invoice, setInvoice] = useState<Invoice | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!invoiceId) return;
    void (async () => {
      setLoading(true);
      const res = await apiFetch("customer", `/api/v1/app/billing/invoices/${invoiceId}`);
      setLoading(false);
      if (res.ok) {
        setInvoice((await res.json()) as Invoice);
      } else if (res.status === 404) {
        setError("Invoice not found.");
      } else {
        setError(await readError(res));
      }
    })();
  }, [invoiceId]);

  if (loading) {
    return (
      <main className="shell wide">
        <p className="eyebrow">Billing</p>
        <p className="lede">Loading…</p>
      </main>
    );
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Billing</p>
      <div className="row-between">
        <h1>Invoice {invoice?.invoiceNumber ?? invoiceId}</h1>
        <Link to="/app/billing/invoices">Back to invoices</Link>
      </div>
      {error && <p className="error">{error}</p>}
      {invoice && (
        <section className="panel">
          <dl>
            <dt>Invoice number</dt>
            <dd>{invoice.invoiceNumber}</dd>
            <dt>Status</dt>
            <dd>{invoice.status}</dd>
            <dt>Currency</dt>
            <dd>{invoice.currency}</dd>
            <dt>Subtotal</dt>
            <dd>{paise2rupees(invoice.subtotalPaise)}</dd>
            <dt>Tax</dt>
            <dd>{paise2rupees(invoice.taxPaise)}</dd>
            <dt>Total</dt>
            <dd><strong>{paise2rupees(invoice.totalPaise)}</strong></dd>
            {invoice.periodStart && (
              <>
                <dt>Period</dt>
                <dd>{invoice.periodStart.slice(0, 10)} → {invoice.periodEnd?.slice(0, 10) ?? "—"}</dd>
              </>
            )}
            {invoice.issuedAt && (
              <>
                <dt>Issued</dt>
                <dd>{invoice.issuedAt.slice(0, 10)}</dd>
              </>
            )}
            {invoice.dueAt && (
              <>
                <dt>Due</dt>
                <dd>{invoice.dueAt.slice(0, 10)}</dd>
              </>
            )}
          </dl>
        </section>
      )}
    </main>
  );
}

export function PaymentsPage() {
  const [payments, setPayments] = useState<Payment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    void (async () => {
      setLoading(true);
      const res = await apiFetch("customer", "/api/v1/app/billing/payments");
      setLoading(false);
      if (res.ok) {
        setPayments((await res.json()) as Payment[]);
      } else {
        setError(await readError(res));
      }
    })();
  }, []);

  if (loading) {
    return (
      <main className="shell wide">
        <p className="eyebrow">Billing</p>
        <p className="lede">Loading…</p>
      </main>
    );
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Billing</p>
      <div className="row-between">
        <h1>Payments</h1>
        <Link to="/app/billing">Back to billing</Link>
      </div>
      {error && <p className="error">{error}</p>}
      <section className="panel">
        {payments.length === 0 ? (
          <p className="lede">No payments yet.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Method</th>
                <th>Status</th>
                <th>Amount</th>
                <th>Provider Payment ID</th>
                <th>Paid</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((p) => (
                <tr key={p.id}>
                  <td>{p.method}</td>
                  <td>{p.status}</td>
                  <td>{paise2rupees(p.amountPaise)}</td>
                  <td>
                    {p.providerPaymentId ? (
                      <code style={{ fontSize: "0.8em" }}>{p.providerPaymentId}</code>
                    ) : (
                      p.externalReference ?? "—"
                    )}
                  </td>
                  <td>{p.paidAt?.slice(0, 10) ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}

export function PlatformBillingPage() {
  const [tenantId, setTenantId] = useState("");
  const [billingData, setBillingData] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [payAmount, setPayAmount] = useState("");
  const [payMsg, setPayMsg] = useState<string | null>(null);

  async function lookup(e: FormEvent) {
    e.preventDefault();
    setBillingData(null);
    setError(null);
    const res = await apiFetch("staff", `/api/v1/platform/billing/tenants/${tenantId}`);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setBillingData((await res.json()) as Record<string, unknown>);
  }

  async function recordPayment(e: FormEvent) {
    e.preventDefault();
    const res = await apiFetch("staff", `/api/v1/platform/billing/tenants/${tenantId}/manual-payment`, {
      method: "POST",
      body: JSON.stringify({ amountPaise: parseInt(payAmount) * 100, currency: "INR", method: "MANUAL" })
    });
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setPayMsg("Payment recorded.");
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Platform admin</p>
      <div className="row-between">
        <h1>Tenant Billing</h1>
        <Link to="/platform">Back</Link>
      </div>
      {error && <p className="error">{error}</p>}
      <section className="panel">
        <h2>Lookup tenant</h2>
        <form className="form" onSubmit={(e) => void lookup(e)}>
          <input
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            placeholder="Tenant UUID"
            required
          />
          <button type="submit">Lookup</button>
        </form>
      </section>
      {billingData && (
        <section className="panel">
          <h2>Subscription</h2>
          <pre style={{ fontSize: "0.8rem" }}>{JSON.stringify(billingData.subscription, null, 2)}</pre>
          <h2>Usage</h2>
          <pre style={{ fontSize: "0.8rem" }}>{JSON.stringify(billingData.usage, null, 2)}</pre>
          <h2>Manual payment (₹)</h2>
          {payMsg && <p className="lede">{payMsg}</p>}
          <form className="form" onSubmit={(e) => void recordPayment(e)}>
            <input
              value={payAmount}
              onChange={(e) => setPayAmount(e.target.value)}
              placeholder="Amount in rupees"
              type="number"
              min="1"
              required
            />
            <button type="submit">Record payment</button>
          </form>
        </section>
      )}
    </main>
  );
}
