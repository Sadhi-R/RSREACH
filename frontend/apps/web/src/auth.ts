const CUSTOMER_ACCESS = "rsreach.customer.access";
const CUSTOMER_REFRESH = "rsreach.customer.refresh";
const CUSTOMER_TENANT = "rsreach.customer.tenant";
const CUSTOMER_WORKSPACE = "rsreach.customer.workspace";
const STAFF_ACCESS = "rsreach.staff.access";
const STAFF_REFRESH = "rsreach.staff.refresh";

export type Realm = "customer" | "staff";

export function saveCustomerSession(accessToken: string, refreshToken: string) {
  localStorage.setItem(CUSTOMER_ACCESS, accessToken);
  localStorage.setItem(CUSTOMER_REFRESH, refreshToken);
}

export function clearCustomerSession() {
  localStorage.removeItem(CUSTOMER_ACCESS);
  localStorage.removeItem(CUSTOMER_REFRESH);
  localStorage.removeItem(CUSTOMER_TENANT);
  localStorage.removeItem(CUSTOMER_WORKSPACE);
}

export function saveStaffSession(accessToken: string, refreshToken: string) {
  localStorage.setItem(STAFF_ACCESS, accessToken);
  localStorage.setItem(STAFF_REFRESH, refreshToken);
}

export function clearStaffSession() {
  localStorage.removeItem(STAFF_ACCESS);
  localStorage.removeItem(STAFF_REFRESH);
}

export function getAccessToken(realm: Realm): string | null {
  return localStorage.getItem(realm === "customer" ? CUSTOMER_ACCESS : STAFF_ACCESS);
}

export function setTenantWorkspace(tenantId: string, workspaceId: string) {
  localStorage.setItem(CUSTOMER_TENANT, tenantId);
  localStorage.setItem(CUSTOMER_WORKSPACE, workspaceId);
}

export function getTenantId(): string | null {
  return localStorage.getItem(CUSTOMER_TENANT);
}

export function getWorkspaceId(): string | null {
  return localStorage.getItem(CUSTOMER_WORKSPACE);
}

export async function apiFetch(
  realm: Realm,
  path: string,
  init: RequestInit = {}
): Promise<Response> {
  const headers = new Headers(init.headers);
  const isFormData = typeof FormData !== "undefined" && init.body instanceof FormData;
  if (!isFormData && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const token = getAccessToken(realm);
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (realm === "customer") {
    const tenantId = getTenantId();
    const workspaceId = getWorkspaceId();
    if (tenantId) {
      headers.set("X-Tenant-Id", tenantId);
    }
    if (workspaceId) {
      headers.set("X-Workspace-Id", workspaceId);
    }
  }
  return fetch(path, { ...init, headers });
}
