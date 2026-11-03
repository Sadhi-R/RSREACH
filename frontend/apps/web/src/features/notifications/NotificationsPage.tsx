import { useEffect, useState } from "react";
import { apiFetch, getTenantId } from "../../auth";

type Notification = {
  id: string;
  title: string;
  body?: string;
  linkPath?: string;
  readAt?: string;
  createdAt: string;
};

export function NotificationsPage() {
  const [items, setItems] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    const res = await apiFetch("customer", "/api/v1/app/notifications");
    if (!res.ok) {
      setError("Failed to load notifications");
      setLoading(false);
      return;
    }
    setItems(await res.json());
    setLoading(false);
  }

  useEffect(() => {
    if (!getTenantId()) return;
    void load();
  }, []);

  async function markRead(id: string) {
    const res = await apiFetch("customer", `/api/v1/app/notifications/${id}/read`, {
      method: "PATCH",
    });
    if (res.ok) {
      setItems((prev) =>
        prev.map((n) =>
          n.id === id ? { ...n, readAt: new Date().toISOString() } : n
        )
      );
    }
  }

  async function markAllRead() {
    await apiFetch("customer", "/api/v1/app/notifications/read-all", {
      method: "POST",
    });
    const now = new Date().toISOString();
    setItems((prev) => prev.map((n) => (n.readAt ? n : { ...n, readAt: now })));
  }

  const unreadCount = items.filter((n) => !n.readAt).length;

  return (
    <main className="shell">
      <p className="eyebrow">RSReach</p>
      <div className="row-between">
        <h1>Notifications</h1>
        {unreadCount > 0 && (
          <button type="button" onClick={markAllRead}>
            Mark all read ({unreadCount})
          </button>
        )}
      </div>
      {loading && <p className="lede">Loading…</p>}
      {error && <p className="error">{error}</p>}
      {!loading && !error && items.length === 0 && (
        <p className="lede">No notifications yet.</p>
      )}
      {!loading && items.length > 0 && (
        <section className="panel" style={{ marginTop: "1.5rem" }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Title</th>
                <th>Body</th>
                <th>Received</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {items.map((n) => (
                <tr
                  key={n.id}
                  className={n.readAt ? "no-row-nav" : ""}
                  style={{ opacity: n.readAt ? 0.65 : 1 }}
                >
                  <td>
                    {n.linkPath ? (
                      <a
                        href={n.linkPath}
                        style={{ color: "var(--accent)", textDecoration: "none" }}
                      >
                        {n.title}
                      </a>
                    ) : (
                      n.title
                    )}
                  </td>
                  <td style={{ color: "var(--muted)", fontSize: "0.9rem" }}>
                    {n.body ?? "—"}
                  </td>
                  <td style={{ color: "var(--muted)", fontSize: "0.85rem", whiteSpace: "nowrap" }}>
                    {new Date(n.createdAt).toLocaleString()}
                  </td>
                  <td>
                    {!n.readAt && (
                      <button
                        type="button"
                        style={{ fontSize: "0.8rem", padding: "0.25rem 0.5rem" }}
                        onClick={() => void markRead(n.id)}
                      >
                        Mark read
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </main>
  );
}
