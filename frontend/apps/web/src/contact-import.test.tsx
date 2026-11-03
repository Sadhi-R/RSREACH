import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ContactImportPage } from "./contact-import";

vi.mock("./auth", () => ({
  apiFetch: vi.fn(),
  getTenantId: () => "tenant-1",
  getWorkspaceId: () => "workspace-1"
}));

import { apiFetch } from "./auth";

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: async () => body
  } as Response;
}

async function submitPreview() {
  expect(await screen.findByRole("button", { name: "Preview" })).toBeInTheDocument();
  const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
  const form = fileInput.closest("form");
  if (!form) {
    throw new Error("preview form missing");
  }
  const file = new File(["display_name,email\nA,a@example.com\n"], "contacts.csv", {
    type: "text/csv"
  });
  fireEvent.change(fileInput, { target: { files: [file] } });
  await waitFor(() => {
    expect(screen.getByRole("button", { name: "Preview" })).not.toBeDisabled();
  });
  // fireEvent.submit bypasses HTML5 required validation in jsdom
  fireEvent.submit(form);
}

describe("ContactImportPage", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("hides import controls without contacts.import", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path) => {
      if (String(path).includes("/permissions")) {
        return jsonResponse({ permissions: ["contacts.read"] });
      }
      return jsonResponse([]);
    });

    render(
      <MemoryRouter>
        <ContactImportPage />
      </MemoryRouter>
    );

    expect(await screen.findByText(/contacts\.import permission/i)).toBeInTheDocument();
    expect(screen.queryByText("Preview")).not.toBeInTheDocument();
  });

  it("shows preview form when import permission is present", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path) => {
      if (String(path).includes("/permissions")) {
        return jsonResponse({ permissions: ["contacts.import"] });
      }
      return jsonResponse([]);
    });

    render(
      <MemoryRouter>
        <ContactImportPage />
      </MemoryRouter>
    );

    expect(await screen.findByRole("button", { name: "Preview" })).toBeInTheDocument();
  });

  it("renders preview error and warning rows from sampleRows", async () => {
    vi.mocked(apiFetch).mockImplementation(async (_realm, path, init) => {
      if (String(path).includes("/permissions")) {
        return jsonResponse({ permissions: ["contacts.import"] });
      }
      if (String(path).includes("/contact-field-defs")) {
        return jsonResponse([]);
      }
      if (String(path).includes("/import/preview") && init?.method === "POST") {
        return jsonResponse({
          id: "job-1",
          filename: "contacts.csv",
          status: "PREVIEW_READY",
          conflictPolicy: "SKIP",
          rowCount: 2,
          successCount: 0,
          failureCount: 0,
          skippedCount: 0,
          sampleRows: [
            {
              rowNumber: 1,
              result: "ERROR",
              errorCode: "MISSING_IDENTIFIER",
              errorMessage: "Email or phone is required"
            },
            {
              rowNumber: 2,
              result: "WARNING",
              errorCode: "SUPPRESSED",
              errorMessage: "Email matches an active suppression list entry"
            }
          ]
        });
      }
      return jsonResponse({});
    });

    render(
      <MemoryRouter>
        <ContactImportPage />
      </MemoryRouter>
    );

    await submitPreview();

    expect(await screen.findByText("ERROR")).toBeInTheDocument();
    expect(screen.getByText("Email or phone is required")).toBeInTheDocument();
    expect(screen.getByText("WARNING")).toBeInTheDocument();
    expect(screen.getByText(/active suppression/i)).toBeInTheDocument();
  });

  it("disables commit while a commit request is in flight", async () => {
    let resolveCommit: ((value: Response) => void) | undefined;
    const commitPromise = new Promise<Response>((resolve) => {
      resolveCommit = resolve;
    });

    vi.mocked(apiFetch).mockImplementation(async (_realm, path, init) => {
      if (String(path).includes("/permissions")) {
        return jsonResponse({ permissions: ["contacts.import"] });
      }
      if (String(path).includes("/contact-field-defs")) {
        return jsonResponse([]);
      }
      if (String(path).includes("/import/preview") && init?.method === "POST") {
        return jsonResponse({
          id: "job-lock",
          filename: "a.csv",
          status: "PREVIEW_READY",
          conflictPolicy: "SKIP",
          rowCount: 1,
          successCount: 0,
          failureCount: 0,
          skippedCount: 0,
          sampleRows: [{ rowNumber: 1, result: "OK", errorMessage: null }]
        });
      }
      if (String(path).includes("/commit") && init?.method === "POST") {
        return commitPromise;
      }
      return jsonResponse({});
    });

    render(
      <MemoryRouter>
        <ContactImportPage />
      </MemoryRouter>
    );

    await submitPreview();

    const commitBtn = await screen.findByRole("button", { name: "Commit import" });
    await userEvent.click(commitBtn);

    expect(await screen.findByRole("button", { name: "Committing…" })).toBeDisabled();

    resolveCommit?.(
      jsonResponse({
        id: "job-lock",
        filename: "a.csv",
        status: "RUNNING",
        conflictPolicy: "SKIP",
        rowCount: 1,
        successCount: 0,
        failureCount: 0,
        skippedCount: 0,
        sampleRows: []
      })
    );

    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Committing…" })).not.toBeInTheDocument();
    });
  });
});
