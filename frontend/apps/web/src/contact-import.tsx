import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch, getTenantId, getWorkspaceId } from "./auth";

type RowPreview = {
  rowNumber: number;
  result: string;
  errorCode?: string | null;
  errorMessage?: string | null;
};

type JobView = {
  id: string;
  filename: string;
  status: string;
  conflictPolicy: string;
  rowCount: number;
  successCount: number;
  failureCount: number;
  skippedCount: number;
  sampleRows: RowPreview[];
};

type FieldDef = {
  fieldKey: string;
  label: string;
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

export function ContactImportPage() {
  const [canImport, setCanImport] = useState(false);
  const [canManageConsent, setCanManageConsent] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [displayNameCol, setDisplayNameCol] = useState("display_name");
  const [emailCol, setEmailCol] = useState("email");
  const [phoneCol, setPhoneCol] = useState("phone");
  const [consentEmailCol, setConsentEmailCol] = useState("");
  const [consentWhatsappCol, setConsentWhatsappCol] = useState("");
  const [customFieldMap, setCustomFieldMap] = useState<Record<string, string>>({});
  const [fieldDefs, setFieldDefs] = useState<FieldDef[]>([]);
  const [policy, setPolicy] = useState("SKIP");
  const [job, setJob] = useState<JobView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [committing, setCommitting] = useState(false);

  useEffect(() => {
    if (!getTenantId() || !getWorkspaceId()) {
      setError("Select a tenant and workspace first.");
      return;
    }
    void (async () => {
      const permRes = await apiFetch("customer", "/api/v1/app/permissions");
      if (permRes.ok) {
        const body = await permRes.json();
        const perms: string[] = body.permissions ?? [];
        setCanImport(perms.includes("contacts.import"));
        setCanManageConsent(perms.includes("contacts.consent.manage"));
      }
      const fieldsRes = await apiFetch("customer", "/api/v1/app/contact-field-defs");
      if (fieldsRes.ok) {
        const body = await fieldsRes.json();
        const raw: FieldDef[] = Array.isArray(body) ? body : (body.items ?? []);
        setFieldDefs(raw.filter((f) => f.active !== false));
      }
    })();
  }, []);

  useEffect(() => {
    if (!job || (job.status !== "RUNNING" && job.status !== "PENDING")) {
      return;
    }
    const timer = window.setInterval(() => {
      void refreshJob(job.id);
    }, 1500);
    return () => window.clearInterval(timer);
  }, [job?.id, job?.status]);

  async function refreshJob(jobId: string) {
    const res = await apiFetch("customer", `/api/v1/app/contacts/import/${jobId}`);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setJob((await res.json()) as JobView);
  }

  async function onPreview(e: FormEvent) {
    e.preventDefault();
    if (!file || !canImport) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const customFields: Record<string, string> = {};
      for (const [key, col] of Object.entries(customFieldMap)) {
        if (col.trim()) {
          customFields[key] = col.trim();
        }
      }
      const mapping: Record<string, unknown> = {
        displayName: displayNameCol,
        email: emailCol || null,
        phone: phoneCol || null,
        customFields
      };
      if (canManageConsent && consentEmailCol.trim()) {
        mapping.consentEmailMarketing = consentEmailCol.trim();
      }
      if (canManageConsent && consentWhatsappCol.trim()) {
        mapping.consentWhatsappMarketing = consentWhatsappCol.trim();
      }
      const form = new FormData();
      form.append("file", file);
      form.append("mapping", JSON.stringify(mapping));
      const res = await apiFetch("customer", "/api/v1/app/contacts/import/preview", {
        method: "POST",
        body: form
      });
      if (!res.ok) {
        setError(await readError(res));
        setBusy(false);
        return;
      }
      setJob((await res.json()) as JobView);
    } finally {
      setBusy(false);
    }
  }

  async function onCommit() {
    if (!job || committing) {
      return;
    }
    setCommitting(true);
    setError(null);
    try {
      const res = await apiFetch("customer", `/api/v1/app/contacts/import/${job.id}/commit`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": `import-${job.id}-${crypto.randomUUID()}`
        },
        body: JSON.stringify({ conflictPolicy: policy })
      });
      if (!res.ok) {
        setError(await readError(res));
        return;
      }
      setJob((await res.json()) as JobView);
    } finally {
      setCommitting(false);
    }
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Contacts</p>
      <div className="row-between">
        <h1>Import CSV</h1>
        <div className="actions inline">
          <Link to="/app/contacts">Directory</Link>
        </div>
      </div>

      {!canImport && <p className="error">You need contacts.import permission.</p>}
      {error && <p className="error">{error}</p>}

      {canImport && (
        <section className="panel">
          <form className="form" onSubmit={onPreview}>
            <label>
              CSV file
              <input
                type="file"
                accept=".csv,text/csv"
                onChange={(ev) => setFile(ev.target.files?.[0] ?? null)}
                required
              />
            </label>
            <label>
              display_name column
              <input value={displayNameCol} onChange={(e) => setDisplayNameCol(e.target.value)} required />
            </label>
            <label>
              email column
              <input value={emailCol} onChange={(e) => setEmailCol(e.target.value)} />
            </label>
            <label>
              phone column
              <input value={phoneCol} onChange={(e) => setPhoneCol(e.target.value)} />
            </label>
            {canManageConsent && (
              <>
                <label>
                  consent_email column (optional)
                  <input
                    value={consentEmailCol}
                    onChange={(e) => setConsentEmailCol(e.target.value)}
                    placeholder="consent_email"
                  />
                </label>
                <label>
                  consent_whatsapp column (optional)
                  <input
                    value={consentWhatsappCol}
                    onChange={(e) => setConsentWhatsappCol(e.target.value)}
                    placeholder="consent_whatsapp"
                  />
                </label>
              </>
            )}
            {fieldDefs.map((def) => (
              <label key={def.fieldKey}>
                {def.label} column (optional)
                <input
                  value={customFieldMap[def.fieldKey] ?? ""}
                  onChange={(e) =>
                    setCustomFieldMap((prev) => ({ ...prev, [def.fieldKey]: e.target.value }))
                  }
                  placeholder={def.fieldKey}
                />
              </label>
            ))}
            <button type="submit" disabled={busy || !file}>
              {busy ? "Uploading…" : "Preview"}
            </button>
          </form>
        </section>
      )}

      {job && (
        <section className="panel">
          <h2>
            {job.filename} — {job.status}
          </h2>
          <p className="lede">
            Rows {job.rowCount}. Success {job.successCount}, skipped {job.skippedCount}, failed{" "}
            {job.failureCount}.
          </p>
          {job.status === "PREVIEW_READY" && (
            <div className="form inline-form">
              <select value={policy} onChange={(e) => setPolicy(e.target.value)}>
                <option value="SKIP">SKIP conflicts</option>
                <option value="UPDATE">UPDATE matches</option>
                <option value="CREATE_ANYWAY">CREATE_ANYWAY</option>
              </select>
              <button type="button" onClick={() => void onCommit()} disabled={committing}>
                {committing ? "Committing…" : "Commit import"}
              </button>
            </div>
          )}
          {job.sampleRows?.length > 0 && (
            <table className="data-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Result</th>
                  <th>Message</th>
                </tr>
              </thead>
              <tbody>
                {job.sampleRows.map((row) => (
                  <tr key={row.rowNumber}>
                    <td>{row.rowNumber}</td>
                    <td>{row.result}</td>
                    <td>{row.errorMessage ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </main>
  );
}
