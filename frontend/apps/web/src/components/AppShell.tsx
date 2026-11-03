import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { clearCustomerSession } from "../auth";

const NAV_LINKS = [
  { to: "/app", label: "Dashboard", end: true },
  { to: "/app/contacts", label: "Contacts" },
  { to: "/app/inbox", label: "Inbox" },
  { to: "/app/campaigns", label: "Campaigns" },
  { to: "/app/billing", label: "Billing" },
  { to: "/app/automations", label: "Automations" },
  { to: "/app/notifications", label: "Notifications" },
];

export function AppShell() {
  const navigate = useNavigate();

  function handleLogout() {
    clearCustomerSession();
    navigate("/app/login");
  }

  return (
    <div className="app-layout">
      <nav className="app-nav" aria-label="Main navigation">
        <span className="app-nav-brand">RSReach</span>
        <ul className="app-nav-links" role="list">
          {NAV_LINKS.map(({ to, label, end }) => (
            <li key={to}>
              <NavLink
                to={to}
                end={end}
                className={({ isActive }) =>
                  "app-nav-link" + (isActive ? " app-nav-link-active" : "")
                }
              >
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
        <button
          type="button"
          className="app-nav-logout"
          onClick={handleLogout}
        >
          Log out
        </button>
      </nav>
      <div className="app-content">
        <Outlet />
      </div>
    </div>
  );
}
