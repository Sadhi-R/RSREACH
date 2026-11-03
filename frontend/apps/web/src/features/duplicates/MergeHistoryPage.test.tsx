import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MergeHistoryPage } from "./MergeHistoryPage";

const listMerges = vi.fn();
const loadPermissions = vi.fn();

vi.mock("../../auth", () => ({
  getTenantId: () => "tenant-1",
  getWorkspaceId: () => "workspace-1"
}));

vi.mock("./api", async () => {
  const actual = await vi.importActual<typeof import("./api")>("./api");
  return {
    ...actual,
    listMerges: (...args: unknown[]) => listMerges(...args),
    loadPermissions: (...args: unknown[]) => loadPermissions(...args)
  };
});

describe("MergeHistoryPage", () => {
  beforeEach(() => {
    listMerges.mockReset();
    loadPermissions.mockReset();
    loadPermissions.mockResolvedValue(["contacts.read"]);
  });

  it("renders merge history rows", async () => {
    listMerges.mockResolvedValue({
      items: [
        {
          id: "m1",
          workspaceId: "ws",
          survivorContactId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          loserContactId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          status: "COMPLETED",
          idempotencyKey: "k",
          requestHash: "r",
          previewHash: "p",
          actorId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
          completedAt: "2026-01-04T12:00:00Z",
          items: []
        }
      ],
      page: 0,
      size: 25,
      hasNext: false
    });
    render(
      <MemoryRouter>
        <MergeHistoryPage />
      </MemoryRouter>
    );
    expect(await screen.findByText("COMPLETED")).toBeInTheDocument();
  });

  it("shows unauthorized state without contacts.read", async () => {
    loadPermissions.mockResolvedValue([]);
    render(
      <MemoryRouter>
        <MergeHistoryPage />
      </MemoryRouter>
    );
    expect(await screen.findByText(/do not have permission/i)).toBeInTheDocument();
  });
});
