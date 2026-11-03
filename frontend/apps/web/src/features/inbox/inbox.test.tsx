import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { InboxDirectoryPage } from "./InboxPages";

vi.mock("../../auth", () => ({
  apiFetch: vi.fn(),
  getTenantId: () => "tenant-1",
  getWorkspaceId: () => "workspace-1"
}));

import { apiFetch } from "../../auth";

describe("InboxDirectoryPage", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
  });

  it("hides inbox without inbox.read", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path) => {
      if (String(path).includes("/permissions")) {
        return { ok: true, json: async () => ({ permissions: ["contacts.read"] }) } as Response;
      }
      return { ok: true, json: async () => ({ items: [] }) } as Response;
    });
    render(
      <MemoryRouter>
        <InboxDirectoryPage />
      </MemoryRouter>
    );
    expect(await screen.findByText(/inbox\.read permission/i)).toBeInTheDocument();
  });

  it("lists conversations when permitted", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path) => {
      if (String(path).includes("/permissions")) {
        return { ok: true, json: async () => ({ permissions: ["inbox.read"] }) } as Response;
      }
      return {
        ok: true,
        json: async () => ({
          items: [{ id: "c1", channel: "WHATSAPP", contactId: "x", status: "OPEN", subject: "Hello" }]
        })
      } as Response;
    });
    render(
      <MemoryRouter>
        <InboxDirectoryPage />
      </MemoryRouter>
    );
    expect(await screen.findByText("Hello")).toBeInTheDocument();
  });
});
