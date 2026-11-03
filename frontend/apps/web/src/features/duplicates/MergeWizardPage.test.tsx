import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MergeWizardPage } from "./MergeWizardPage";

const getCandidate = vi.fn();
const previewMerge = vi.fn();
const executeMerge = vi.fn();
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
    previewMerge: (...args: unknown[]) => previewMerge(...args),
    executeMerge: (...args: unknown[]) => executeMerge(...args),
    loadPermissions: (...args: unknown[]) => loadPermissions(...args)
  };
});

const candidate = {
  id: "cand-1",
  workspaceId: "ws-1",
  contactIdA: "a1",
  contactIdB: "b1",
  contactA: {
    id: "a1",
    displayName: "Survivor Ada",
    status: "ACTIVE",
    channels: [],
    customFields: {}
  },
  contactB: {
    id: "b1",
    displayName: "Loser Ada",
    status: "ARCHIVED",
    channels: [],
    customFields: {}
  },
  matchTypes: ["EMAIL_EXACT"],
  confidence: "HIGH",
  score: 100,
  mergeAllowed: true,
  status: "OPEN",
  matches: [],
  detectedAt: "2026-01-01T00:00:00Z",
  lastDetectedAt: "2026-01-02T00:00:00Z",
  stale: false
};

const preview = {
  candidateId: "cand-1",
  survivorContactId: "a1",
  loserContactId: "b1",
  fields: [
    {
      fieldKey: "display_name",
      survivorValue: "Survivor Ada",
      loserValue: "Loser Ada",
      resultValue: "Survivor Ada",
      source: "SURVIVOR"
    }
  ],
  channels: [
    {
      channelId: "ch1",
      contactId: "a1",
      channelType: "EMAIL",
      valueNormalized: "ada@example.com",
      valueRaw: "ada@example.com",
      status: "ACTIVE",
      action: "RETAINED",
      primaryAfterMerge: true
    },
    {
      channelId: "ch2",
      contactId: "b1",
      channelType: "EMAIL",
      valueNormalized: "ada@example.com",
      valueRaw: "ada@example.com",
      status: "ARCHIVED",
      action: "DEDUPED_ARCHIVED",
      primaryAfterMerge: false
    },
    {
      channelId: "ch3",
      contactId: "b1",
      channelType: "PHONE",
      valueNormalized: "+9199",
      valueRaw: "+9199",
      status: "INVALID",
      action: "LEFT_INVALID",
      primaryAfterMerge: false
    }
  ],
  introducedAddresses: [
    {
      channelType: "EMAIL",
      storageType: "EMAIL",
      channelId: "ch-new",
      valueNormalized: "new@example.com",
      valueRaw: "new@example.com"
    }
  ],
  consent: [
    {
      channelType: "EMAIL",
      purpose: "MARKETING",
      survivorBefore: "OPTED_IN",
      loserBefore: "OPTED_IN",
      after: "UNKNOWN",
      changed: true,
      requiresFreshConsent: true
    }
  ],
  groups: [{ groupId: "g1", groupType: "STATIC", action: "UNION_MEMBER" }],
  sharesToRevoke: [
    {
      shareId: "share-1",
      sourceWorkspaceId: "ws-1",
      targetWorkspaceId: "ws-2",
      permission: "READ"
    }
  ],
  blockingConflicts: [],
  mergeAllowed: true,
  previewHash: "preview-hash-1",
  stale: false
};

function renderWizard() {
  return render(
    <MemoryRouter initialEntries={["/app/contact-duplicates/cand-1/merge"]}>
      <Routes>
        <Route path="/app/contact-duplicates/:candidateId/merge" element={<MergeWizardPage />} />
      </Routes>
    </MemoryRouter>
  );
}

async function advanceToConfirm() {
  expect(await screen.findByRole("heading", { name: /choose survivor/i })).toBeInTheDocument();
  previewMerge.mockResolvedValue(preview);
  await userEvent.click(screen.getByRole("button", { name: /survivor ada/i }));
  expect(await screen.findByRole("heading", { name: /resolve fields/i })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /^continue$/i }));
  expect(await screen.findByRole("heading", { name: /review channels/i })).toBeInTheDocument();
  expect(screen.getByText(/deduplicated/i)).toBeInTheDocument();
  expect(screen.getByText(/left invalid/i)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /^continue$/i }));
  expect(await screen.findByRole("heading", { name: /consent impact/i })).toBeInTheDocument();
  expect(screen.getByText(/fresh consent required/i)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /^continue$/i }));
  expect(await screen.findByRole("heading", { name: /groups and shares/i })).toBeInTheDocument();
  expect(screen.getByText(/revoked and are not transferred/i)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /^continue$/i }));
  expect(
    await screen.findByRole("heading", { level: 2, name: /final confirmation/i })
  ).toBeInTheDocument();
}

describe("MergeWizardPage", () => {
  beforeEach(() => {
    getCandidate.mockReset();
    previewMerge.mockReset();
    executeMerge.mockReset();
    loadPermissions.mockReset();
    loadPermissions.mockResolvedValue(["contacts.manage", "contacts.consent.manage"]);
    getCandidate.mockResolvedValue(candidate);
  });

  it("walks survivor selection through channel and consent review", async () => {
    renderWizard();
    await advanceToConfirm();
  });

  it("prevents duplicate submissions while merge is in flight and shows success only after backend confirms", async () => {
    renderWizard();
    await advanceToConfirm();
    let resolveExecute: (value: unknown) => void = () => undefined;
    executeMerge.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveExecute = resolve;
        })
    );
    await userEvent.click(screen.getByRole("checkbox"));
    const confirmBtn = screen.getByRole("button", { name: /confirm merge/i });
    await userEvent.click(confirmBtn);
    expect(confirmBtn).toBeDisabled();
    expect(executeMerge).toHaveBeenCalledTimes(1);
    const firstKey = (executeMerge.mock.calls[0] as unknown[])[1];
    resolveExecute({
      id: "merge-1",
      workspaceId: "ws-1",
      survivorContactId: "a1",
      loserContactId: "b1",
      status: "COMPLETED",
      idempotencyKey: firstKey,
      requestHash: "r",
      previewHash: "preview-hash-1",
      actorId: "actor",
      completedAt: "2026-01-04T00:00:00Z",
      items: []
    });
    expect(await screen.findByText(/merge completed/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /open survivor/i })).toBeInTheDocument();
  });

  it("reuses idempotency key on retry of the same logical request", async () => {
    renderWizard();
    await advanceToConfirm();
    const { ApiClientError } = await import("./api");
    executeMerge.mockRejectedValueOnce(new ApiClientError(500, { message: "temporary" }, "temporary"));
    await userEvent.click(screen.getByRole("checkbox"));
    await userEvent.click(screen.getByRole("button", { name: /confirm merge/i }));
    await screen.findByText("temporary");
    const firstKey = (executeMerge.mock.calls[0] as unknown[])[1];
    executeMerge.mockResolvedValueOnce({
      id: "merge-1",
      workspaceId: "ws-1",
      survivorContactId: "a1",
      loserContactId: "b1",
      status: "COMPLETED",
      idempotencyKey: firstKey,
      requestHash: "r",
      previewHash: "preview-hash-1",
      actorId: "actor",
      completedAt: "2026-01-04T00:00:00Z",
      items: []
    });
    await userEvent.click(screen.getByRole("button", { name: /retry same request/i }));
    await waitFor(() => expect(executeMerge).toHaveBeenCalledTimes(2));
    expect((executeMerge.mock.calls[1] as unknown[])[1]).toBe(firstKey);
  });

  it("asks the user to refresh preview after 409 stale conflict", async () => {
    renderWizard();
    await advanceToConfirm();
    const { ApiClientError } = await import("./api");
    executeMerge.mockRejectedValue(new ApiClientError(409, { message: "STALE_PREVIEW" }, "STALE_PREVIEW"));
    await userEvent.click(screen.getByRole("checkbox"));
    await userEvent.click(screen.getByRole("button", { name: /confirm merge/i }));
    expect(await screen.findByText(/refresh the preview/i)).toBeInTheDocument();
    expect(await screen.findByText(/resolve fields/i)).toBeInTheDocument();
  });

  it("blocks unauthorized merge wizard", async () => {
    loadPermissions.mockResolvedValue(["contacts.read"]);
    renderWizard();
    expect(
      await screen.findByText(/contacts.manage and contacts.consent.manage/i)
    ).toBeInTheDocument();
  });
});
