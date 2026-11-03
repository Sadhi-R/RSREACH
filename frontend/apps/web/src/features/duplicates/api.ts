import { apiFetch } from "../../auth";
import type {
  ApiErrorBody,
  CandidateDetail,
  CandidatePage,
  FieldChoice,
  MergePage,
  MergePreview,
  MergeView,
  PrimaryChoice,
  ScanAccepted
} from "./types";

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly traceId: string | null;

  constructor(status: number, body: ApiErrorBody | null, fallback: string) {
    super(body?.message ?? fallback);
    this.name = "ApiClientError";
    this.status = status;
    this.code = body?.code ?? null;
    this.traceId = body?.traceId ?? null;
  }
}

export async function parseApiError(res: Response): Promise<ApiClientError> {
  let body: ApiErrorBody | null = null;
  try {
    body = (await res.json()) as ApiErrorBody;
  } catch {
    body = null;
  }
  return new ApiClientError(res.status, body, `Request failed (${res.status})`);
}

export async function loadPermissions(): Promise<string[]> {
  const res = await apiFetch("customer", "/api/v1/app/permissions");
  if (!res.ok) {
    return [];
  }
  const body = (await res.json()) as { permissions?: string[] };
  return body.permissions ?? [];
}

export async function listCandidates(params: {
  status?: string;
  contactId?: string;
  page: number;
  size?: number;
}): Promise<CandidatePage> {
  const query = new URLSearchParams({
    page: String(params.page),
    size: String(params.size ?? 25)
  });
  if (params.status) {
    query.set("status", params.status);
  }
  if (params.contactId) {
    query.set("contactId", params.contactId);
  }
  const res = await apiFetch("customer", `/api/v1/app/contact-duplicates?${query}`);
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as CandidatePage;
}

export async function getCandidate(candidateId: string): Promise<CandidateDetail> {
  const res = await apiFetch("customer", `/api/v1/app/contact-duplicates/${candidateId}`);
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as CandidateDetail;
}

export async function startDuplicateScan(): Promise<ScanAccepted> {
  const res = await apiFetch("customer", "/api/v1/app/contact-duplicates/scans", {
    method: "POST",
    body: "{}"
  });
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as ScanAccepted;
}

export async function dismissCandidate(candidateId: string, reason: string): Promise<CandidateDetail> {
  const res = await apiFetch("customer", `/api/v1/app/contact-duplicates/${candidateId}/dismiss`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as CandidateDetail;
}

export async function reopenCandidate(candidateId: string): Promise<CandidateDetail> {
  const res = await apiFetch("customer", `/api/v1/app/contact-duplicates/${candidateId}/reopen`, {
    method: "POST",
    body: "{}"
  });
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as CandidateDetail;
}

export async function previewMerge(body: {
  candidateId: string;
  survivorContactId: string;
  loserContactId: string;
  fieldChoices: FieldChoice[];
  primaryChoices: PrimaryChoice[];
}): Promise<MergePreview> {
  const res = await apiFetch("customer", "/api/v1/app/contacts/merges/preview", {
    method: "POST",
    body: JSON.stringify(body)
  });
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as MergePreview;
}

export async function executeMerge(
  body: {
    candidateId: string;
    survivorContactId: string;
    loserContactId: string;
    fieldChoices: FieldChoice[];
    primaryChoices: PrimaryChoice[];
    previewHash: string;
    confirm: boolean;
  },
  idempotencyKey: string
): Promise<MergeView> {
  const res = await apiFetch("customer", "/api/v1/app/contacts/merges", {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(body)
  });
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as MergeView;
}

export async function listMerges(params: {
  contactId?: string;
  page: number;
  size?: number;
}): Promise<MergePage> {
  const query = new URLSearchParams({
    page: String(params.page),
    size: String(params.size ?? 25)
  });
  if (params.contactId) {
    query.set("contactId", params.contactId);
  }
  const res = await apiFetch("customer", `/api/v1/app/contacts/merges?${query}`);
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as MergePage;
}

export async function getMerge(mergeId: string): Promise<MergeView> {
  const res = await apiFetch("customer", `/api/v1/app/contacts/merges/${mergeId}`);
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as MergeView;
}
