import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ContactDetailPage, ContactsDirectoryPage } from "./contacts";

vi.mock("./auth", () => ({
  apiFetch: vi.fn(),
  getTenantId: () => "tenant-1",
  getWorkspaceId: () => "workspace-home"
}));

import { apiFetch } from "./auth";

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return { ok, status, json: async () => body } as Response;
}

describe("Contact sharing UX", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("shows (shared) badge when contact home differs from current workspace", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path) => {
      if (String(path).includes("/permissions")) {
        return jsonResponse({ permissions: ["contacts.read", "contacts.manage"] });
      }
      if (String(path).startsWith("/api/v1/app/contacts?")) {
        return jsonResponse({
          items: [
            {
              id: "c1",
              displayName: "Shared Ada",
              status: "ACTIVE",
              homeWorkspaceId: "workspace-other",
              primaryEmail: "ada@example.com",
              channels: []
            }
          ],
          page: 0,
          size: 25,
          hasNext: false
        });
      }
      return jsonResponse({});
    });

    render(
      <MemoryRouter>
        <ContactsDirectoryPage />
      </MemoryRouter>
    );

    expect(await screen.findByText(/Shared Ada \(shared\)/i)).toBeInTheDocument();
  });

  it("hides shares panel and consent controls for shared-in contacts", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path) => {
      if (String(path).includes("/permissions")) {
        return jsonResponse({
          permissions: ["contacts.read", "contacts.manage", "contacts.consent.manage"]
        });
      }
      if (String(path).includes("/contacts/c-shared") && !String(path).includes("/shares")) {
        return jsonResponse({
          id: "c-shared",
          displayName: "Shared In",
          status: "ACTIVE",
          homeWorkspaceId: "workspace-other",
          access: "READ",
          primaryEmail: "s@example.com",
          channels: []
        });
      }
      if (String(path).includes("/suppressions")) {
        return jsonResponse({ items: [] });
      }
      return jsonResponse({ items: [] });
    });

    render(
      <MemoryRouter initialEntries={["/app/contacts/c-shared"]}>
        <Routes>
          <Route path="/app/contacts/:contactId" element={<ContactDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByText(/Shared into this workspace/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Shares" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Grant" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.getByText(/not available for shared-in contacts/i)).toBeInTheDocument();
  });

  it("shows shares panel on home contact with manage permission", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path) => {
      if (String(path).includes("/permissions")) {
        return jsonResponse({ permissions: ["contacts.read", "contacts.manage"] });
      }
      if (String(path).match(/\/contacts\/c-home$/) || String(path).endsWith("/contacts/c-home")) {
        return jsonResponse({
          id: "c-home",
          displayName: "Home Contact",
          status: "ACTIVE",
          homeWorkspaceId: "workspace-home",
          access: "HOME",
          primaryEmail: "h@example.com",
          channels: []
        });
      }
      if (String(path).includes("/shares")) {
        return jsonResponse({
          items: [
            {
              id: "share-1",
              targetWorkspaceId: "workspace-b",
              permission: "READ",
              revokedAt: null
            }
          ]
        });
      }
      if (String(path).includes("/consents")) {
        return jsonResponse({ items: [] });
      }
      if (String(path).includes("/suppressions")) {
        return jsonResponse({ items: [] });
      }
      return jsonResponse({ items: [] });
    });

    render(
      <MemoryRouter initialEntries={["/app/contacts/c-home"]}>
        <Routes>
          <Route path="/app/contacts/:contactId" element={<ContactDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByRole("heading", { name: "Shares" })).toBeInTheDocument();
    expect(await screen.findByText("workspace-b")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Revoke" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
  });

  it("surfaces share create errors", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path, init) => {
      if (String(path).includes("/permissions")) {
        return jsonResponse({ permissions: ["contacts.manage"] });
      }
      if (String(path).endsWith("/contacts/c-home")) {
        return jsonResponse({
          id: "c-home",
          displayName: "Home Contact",
          status: "ACTIVE",
          homeWorkspaceId: "workspace-home",
          access: "HOME",
          channels: []
        });
      }
      if (String(path).includes("/shares") && init?.method === "POST") {
        return jsonResponse({ message: "Target workspace not found" }, false, 404);
      }
      if (String(path).includes("/shares")) {
        return jsonResponse({ items: [] });
      }
      if (String(path).includes("/consents") || String(path).includes("/suppressions")) {
        return jsonResponse({ items: [] });
      }
      return jsonResponse({});
    });

    const { userEvent } = await import("@testing-library/user-event");
    render(
      <MemoryRouter initialEntries={["/app/contacts/c-home"]}>
        <Routes>
          <Route path="/app/contacts/:contactId" element={<ContactDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByRole("heading", { name: "Shares" })).toBeInTheDocument();
    const input = screen.getByPlaceholderText(/Target workspace UUID/i);
    await userEvent.type(input, "00000000-0000-0000-0000-000000000099");
    await userEvent.click(screen.getByRole("button", { name: "Share" }));
    await waitFor(() => {
      expect(screen.getByText(/Target workspace not found/i)).toBeInTheDocument();
    });
  });
});
