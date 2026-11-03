import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CandidateDetailPage } from "./CandidateDetailPage";

const getCandidate = vi.fn();
const dismissCandidate = vi.fn();
const reopenCandidate = vi.fn();
const loadPermissions = vi.fn();

vi.mock("../../auth", () => ({
  getTenantId: () => "tenant-1",
  getWorkspaceId: () => "workspace-1"
}));

vi.mock("./api", async () => {
  const actual = await vi.importActual<typeof import("./api")>("./api");
  return {
    ...actual,
    getCandidate: (...args: unknown[]) => getCandidate(...args),
    dismissCandidate: (...args: unknown[]) => dismissCandidate(...args),
    reopenCandidate: (...args: unknown[]) => reopenCandidate(...args),
    loadPermissions: (...args: unknown[]) => loadPermissions(...args)
  };
});

const openCandidate = {
  id: "cand-1",
  workspaceId: "ws-1",
  contactIdA: "a1",
  contactIdB: "b1",
  contactA: {
    id: "a1",
    displayName: "Ada",
    status: "ACTIVE",
    primaryEmail: "ada@example.com",
    primaryPhone: null,
    channels: [{ channelType: "EMAIL", value: "ada@example.com", primary: true, status: "ACTIVE" }],
    customFields: {}
  },
  contactB: {
    id: "b1",
    displayName: "Ada L",
    status: "ARCHIVED",
    primaryEmail: "ada@example.com",
    primaryPhone: null,
    channels: [
      { channelType: "EMAIL", value: "ada@example.com", primary: true, status: "ARCHIVED" }
    ],
    customFields: {}
  },
  matchTypes: ["EMAIL_EXACT"],
  confidence: "HIGH",
  score: 100,
  mergeAllowed: true,
  status: "OPEN",
  matches: [
    {
      matchType: "EMAIL_EXACT",
      channelType: "EMAIL",
      channelIdA: "c1",
      channelIdB: "c2",
      valueNormalized: "ada@example.com",
      valueRawA: "ada@example.com",
      valueRawB: "ada@example.com",
      stillValid: true
    }
  ],
  detectedAt: "2026-01-01T00:00:00Z",
  lastDetectedAt: "2026-01-02T00:00:00Z",
  dismissedAt: null,
  dismissReason: null,
  stale: false
};

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={["/app/contact-duplicates/cand-1"]}>
      <Routes>
        <Route path="/app/contact-duplicates/:candidateId" element={<CandidateDetailPage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe("CandidateDetailPage", () => {
  beforeEach(() => {
    getCandidate.mockReset();
    dismissCandidate.mockReset();
    reopenCandidate.mockReset();
    loadPermissions.mockReset();
    loadPermissions.mockResolvedValue([
      "contacts.read",
      "contacts.manage",
      "contacts.consent.manage"
    ]);
  });

  it("renders candidate detail and evidence", async () => {
    getCandidate.mockResolvedValue(openCandidate);
    renderDetail();
    expect(await screen.findByText("Ada")).toBeInTheDocument();
    expect(screen.getByText("Ada L")).toBeInTheDocument();
    expect(screen.getByText("EMAIL_EXACT")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /start merge/i })).toBeInTheDocument();
  });

  it("shows stale status and blocks merge link", async () => {
    getCandidate.mockResolvedValue({ ...openCandidate, stale: true, mergeAllowed: false });
    renderDetail();
    expect(await screen.findByText(/this candidate is stale/i)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /start merge/i })).not.toBeInTheDocument();
  });

  it("hides dismiss without manage permission", async () => {
    loadPermissions.mockResolvedValue(["contacts.read"]);
    getCandidate.mockResolvedValue(openCandidate);
    renderDetail();
    expect(await screen.findByRole("heading", { name: /review match/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /dismiss candidate/i })).not.toBeInTheDocument();
  });

  it("dismisses and reopens when permitted", async () => {
    getCandidate.mockResolvedValue(openCandidate);
    dismissCandidate.mockResolvedValue({
      ...openCandidate,
      status: "DISMISSED",
      dismissReason: "not a duplicate"
    });
    reopenCandidate.mockResolvedValue(openCandidate);
    renderDetail();
    expect(await screen.findByRole("heading", { name: /review match/i })).toBeInTheDocument();
    await userEvent.type(screen.getByPlaceholderText(/reason/i), "not a duplicate");
    await userEvent.click(screen.getByRole("button", { name: /dismiss candidate/i }));
    await waitFor(() => expect(dismissCandidate).toHaveBeenCalled());
    expect(await screen.findByRole("button", { name: /reopen candidate/i })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /reopen candidate/i }));
    await waitFor(() => expect(reopenCandidate).toHaveBeenCalled());
  });

  it("shows safe not-found for inaccessible candidate", async () => {
    const { ApiClientError } = await import("./api");
    getCandidate.mockRejectedValue(new ApiClientError(404, { message: "hidden" }, "hidden"));
    renderDetail();
    expect(await screen.findByText(/not found in the current workspace/i)).toBeInTheDocument();
  });
});
