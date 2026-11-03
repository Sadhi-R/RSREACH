import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DuplicatesDirectoryPage } from "./DuplicatesDirectoryPage";

const listCandidates = vi.fn();
const startDuplicateScan = vi.fn();
const loadPermissions = vi.fn();

vi.mock("../../auth", () => ({
  getTenantId: () => "tenant-1",
  getWorkspaceId: () => "workspace-1"
}));

vi.mock("./api", async () => {
  const actual = await vi.importActual<typeof import("./api")>("./api");
  return {
    ...actual,
    listCandidates: (...args: unknown[]) => listCandidates(...args),
    startDuplicateScan: (...args: unknown[]) => startDuplicateScan(...args),
    loadPermissions: (...args: unknown[]) => loadPermissions(...args)
  };
});

describe("DuplicatesDirectoryPage", () => {
  beforeEach(() => {
    listCandidates.mockReset();
    startDuplicateScan.mockReset();
    loadPermissions.mockReset();
    loadPermissions.mockResolvedValue(["contacts.read", "contacts.manage"]);
  });

  it("shows empty state when there are no candidates", async () => {
    listCandidates.mockResolvedValue({ items: [], page: 0, size: 25, hasNext: false });
    render(
      <MemoryRouter>
        <DuplicatesDirectoryPage />
      </MemoryRouter>
    );
    expect(await screen.findByText(/no duplicate candidates/i)).toBeInTheDocument();
  });

  it("shows error state with retry", async () => {
    const { ApiClientError } = await import("./api");
    listCandidates.mockRejectedValue(new ApiClientError(500, { message: "boom" }, "boom"));
    render(
      <MemoryRouter>
        <DuplicatesDirectoryPage />
      </MemoryRouter>
    );
    expect(await screen.findByText("boom")).toBeInTheDocument();
    listCandidates.mockResolvedValue({ items: [], page: 0, size: 25, hasNext: false });
    await userEvent.click(screen.getByRole("button", { name: /retry/i }));
    await waitFor(() => expect(listCandidates).toHaveBeenCalledTimes(2));
  });

  it("initiates a workspace scan when permitted", async () => {
    listCandidates.mockResolvedValue({ items: [], page: 0, size: 25, hasNext: false });
    startDuplicateScan.mockResolvedValue({ scanId: "scan-1", status: "QUEUED" });
    render(
      <MemoryRouter>
        <DuplicatesDirectoryPage />
      </MemoryRouter>
    );
    await screen.findByText(/no duplicate candidates/i);
    await userEvent.click(screen.getByRole("button", { name: /scan workspace/i }));
    await waitFor(() => expect(startDuplicateScan).toHaveBeenCalled());
    expect(await screen.findByText(/scan accepted/i)).toBeInTheDocument();
  });

  it("hides scan action without contacts.manage", async () => {
    loadPermissions.mockResolvedValue(["contacts.read"]);
    listCandidates.mockResolvedValue({ items: [], page: 0, size: 25, hasNext: false });
    render(
      <MemoryRouter>
        <DuplicatesDirectoryPage />
      </MemoryRouter>
    );
    await screen.findByText(/no duplicate candidates/i);
    expect(screen.queryByRole("button", { name: /scan workspace/i })).not.toBeInTheDocument();
  });

  it("renders candidate list rows", async () => {
    listCandidates.mockResolvedValue({
      items: [
        {
          id: "cand-1",
          contactIdA: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          contactIdB: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          matchTypes: ["EMAIL_EXACT"],
          confidence: "HIGH",
          score: 100,
          mergeAllowed: true,
          status: "DISMISSED",
          detectedAt: "2026-01-01T00:00:00Z",
          lastDetectedAt: "2026-01-02T00:00:00Z",
          stale: false
        },
        {
          id: "cand-2",
          contactIdA: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          contactIdB: "cccccccc-cccc-cccc-cccc-cccccccccccc",
          matchTypes: ["PHONE_EXACT"],
          confidence: "HIGH",
          score: 90,
          mergeAllowed: false,
          status: "SUPERSEDED",
          detectedAt: "2026-01-01T00:00:00Z",
          lastDetectedAt: "2026-01-03T00:00:00Z",
          stale: true
        }
      ],
      page: 0,
      size: 25,
      hasNext: false
    });
    render(
      <MemoryRouter>
        <DuplicatesDirectoryPage />
      </MemoryRouter>
    );
    expect(await screen.findByText("DISMISSED")).toBeInTheDocument();
    expect(screen.getByText(/STALE/)).toBeInTheDocument();
  });
});
