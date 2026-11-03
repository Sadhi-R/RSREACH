import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiFetch, getTenantId, getWorkspaceId } from "../../auth";

type Conversation = {
  id: string;
  channel: string;
  contactId: string;
  subject?: string | null;
  status: string;
  lastMessageAt?: string | null;
};

type Message = {
  id: string;
  direction: string;
  bodyText?: string | null;
  status: string;
  createdAt?: string;
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

export function InboxDirectoryPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Conversation[]>([]);
  const [channel, setChannel] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [canRead, setCanRead] = useState(false);
  const [loading, setLoading] = useState(true);

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
        setCanRead((body.permissions ?? []).includes("inbox.read"));
      }
      const params = new URLSearchParams({ page: "0", size: "50" });
      if (channel) params.set("channel", channel);
      const res = await apiFetch("customer", `/api/v1/app/conversations?${params}`);
      if (!res.ok) {
        setError(await readError(res));
        setLoading(false);
        return;
      }
      const body = await res.json();
      setItems(body.items ?? []);
      setLoading(false);
    })();
  }, [channel]);

  if (!canRead && !loading) {
    return (
      <main className="shell">
        <p className="error">You need inbox.read permission.</p>
        <Link to="/app">Back</Link>
      </main>
    );
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Communications</p>
      <div className="row-between">
        <h1>Inbox</h1>
        <div className="actions inline">
          <Link to="/app">Dashboard</Link>
          <Link to="/app/integrations">Integrations</Link>
          <Link to="/app/campaigns">Campaigns</Link>
        </div>
      </div>
      <section className="panel">
        <select value={channel} onChange={(e) => setChannel(e.target.value)}>
          <option value="">All channels</option>
          <option value="WHATSAPP">WhatsApp</option>
          <option value="EMAIL">Email</option>
          <option value="INTERNAL_TEST">Internal test</option>
        </select>
        {loading && <p className="lede">Loading…</p>}
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
        {!loading && items.length === 0 && <p className="lede">No conversations yet.</p>}
        {items.length > 0 && (
          <table className="data-table">
            <thead>
              <tr>
                <th>Channel</th>
                <th>Subject</th>
                <th>Status</th>
                <th>Last message</th>
              </tr>
            </thead>
            <tbody>
              {items.map((c) => (
                <tr key={c.id} onClick={() => navigate(`/app/inbox/${c.id}`)}>
                  <td>{c.channel}</td>
                  <td>{c.subject ?? "—"}</td>
                  <td>{c.status}</td>
                  <td>{c.lastMessageAt ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}

export function InboxDetailPage() {
  const { conversationId } = useParams();
  const [messages, setMessages] = useState<Message[]>([]);
  const [notes, setNotes] = useState<{ id: string; bodyText: string }[]>([]);
  const [body, setBody] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [canReply, setCanReply] = useState(false);
  const [conversation, setConversation] = useState<Conversation | null>(null);

  async function load() {
    if (!conversationId) return;
    const [permRes, convRes, msgRes, notesRes] = await Promise.all([
      apiFetch("customer", "/api/v1/app/permissions"),
      apiFetch("customer", `/api/v1/app/conversations/${conversationId}`),
      apiFetch("customer", `/api/v1/app/conversations/${conversationId}/messages?page=0&size=100`),
      apiFetch("customer", `/api/v1/app/conversations/${conversationId}/notes`)
    ]);
    if (permRes.ok) {
      const bodyJson = await permRes.json();
      setCanReply((bodyJson.permissions ?? []).includes("inbox.reply"));
    }
    if (!convRes.ok) {
      setError(await readError(convRes));
      return;
    }
    setConversation((await convRes.json()) as Conversation);
    if (msgRes.ok) {
      const bodyJson = await msgRes.json();
      setMessages(bodyJson.items ?? []);
    }
    if (notesRes.ok) {
      setNotes((await notesRes.json()) as { id: string; bodyText: string }[]);
    }
  }

  useEffect(() => {
    void load();
  }, [conversationId]);

  async function send(e: FormEvent) {
    e.preventDefault();
    if (!conversationId || !body.trim() || busy) return;
    setBusy(true);
    setError(null);
    const res = await apiFetch("customer", `/api/v1/app/conversations/${conversationId}/messages`, {
      method: "POST",
      body: JSON.stringify({
        bodyText: body.trim(),
        purpose: "SERVICE",
        destinationAddress: conversation?.channel === "INTERNAL_TEST" ? undefined : undefined
      })
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setBody("");
    await load();
  }

  async function addNote(e: FormEvent) {
    e.preventDefault();
    if (!conversationId || !note.trim()) return;
    setBusy(true);
    const res = await apiFetch("customer", `/api/v1/app/conversations/${conversationId}/notes`, {
      method: "POST",
      body: JSON.stringify({ bodyText: note.trim() })
    });
    setBusy(false);
    if (!res.ok) {
      setError(await readError(res));
      return;
    }
    setNote("");
    await load();
  }

  return (
    <main className="shell wide">
      <p className="eyebrow">Inbox</p>
      <div className="row-between">
        <h1>{conversation?.subject ?? "Conversation"}</h1>
        <Link to="/app/inbox">Back</Link>
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
        <p className="lede">
          {conversation?.channel} · {conversation?.status}
        </p>
        <ul>
          {messages.map((m) => (
            <li key={m.id}>
              <strong>{m.direction}</strong> [{m.status}] {m.bodyText}
            </li>
          ))}
        </ul>
        {canReply && (
          <form className="form" onSubmit={(e) => void send(e)}>
            <textarea value={body} onChange={(e) => setBody(e.target.value)} required rows={3} />
            <button type="submit" disabled={busy}>
              {busy ? "Sending…" : "Send"}
            </button>
          </form>
        )}
      </section>
      <section className="panel">
        <h2>Internal notes</h2>
        <ul>
          {notes.map((n) => (
            <li key={n.id}>{n.bodyText}</li>
          ))}
        </ul>
        {canReply && (
          <form className="form inline-form" onSubmit={(e) => void addNote(e)}>
            <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note" required />
            <button type="submit" disabled={busy}>
              Add note
            </button>
          </form>
        )}
      </section>
    </main>
  );
}
