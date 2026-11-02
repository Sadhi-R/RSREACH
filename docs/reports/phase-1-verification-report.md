# RSReach Phase 1 Verification Report

**Date:** 2026-11-02 (final Docker verification)  
**Scope:** Phase 1 identity, authentication, multi-tenancy, RBAC, audit, portal shells, security remediation  
**Rules followed:** No Phase 2 work; Flyway V1–V9 not modified; skipped tests not marked passed.

---

## Executive recommendation

**A. Phase 1 is ready to proceed to Phase 2 planning.**

Evidence from this pass:

- Docker Desktop engine available and used by Testcontainers and Compose.
- `mvn -B verify` **BUILD SUCCESS** with **0 skipped** Testcontainers suites.
- Frontend typecheck + production build **PASS**.
- Compose stack healthy: postgres, api, worker, scheduler, web.
- Docker profile keeps `permit-unauthenticated-api=false` and `expose-dev-tokens=false`.
- Prior HIGH/MEDIUM authorization findings remain corrected and regression-tested.

---

## 0. Docker availability

| Check | Status | Command / evidence |
|---|---|---|
| Docker CLI | **PASS** | `docker version` → Client/Server 29.7.2 |
| Docker engine | **PASS** | `docker info` → Server=29.7.2, OS=Docker Desktop |
| Testcontainers connectivity | **PASS** (after operator config) | Requires Docker API **1.44+** (Engine 29 rejects older negotiated versions with HTTP 400). Working local config: `~/.testcontainers.properties` + `~/.docker-java.properties` with `docker.host=tcp://127.0.0.1:2375` and `api.version=1.44`, plus Maven `-Dapi.version=1.44`. |

---

## 1. Maven verification

### Command

```powershell
cd D:\Projects\RSReach\backend
$env:DOCKER_HOST = "tcp://127.0.0.1:2375"
$env:DOCKER_API_VERSION = "1.44"
$env:TESTCONTAINERS_RYUK_DISABLED = "true"
& "$env:TEMP\apache-maven-3.9.9\bin\mvn.cmd" -B verify "-Dapi.version=1.44"
```

### Result

| Check | Status |
|---|---|
| `mvn -B verify` | **PASS — BUILD SUCCESS** |
| Total skipped Testcontainers tests | **0** |

### Suite inventory (final run)

| Suite | Tests | Failures | Errors | Skipped | Status |
|---|---:|---:|---:|---:|---|
| `RsReachConstantsTest` | 1 | 0 | 0 | 0 | **PASS** |
| `Phase0ReliabilityTest` | 4 | 0 | 0 | 0 | **PASS** (executed) |
| `SecretEncryptionServiceTest` | 1 | 0 | 0 | 0 | **PASS** |
| `DevTokenExposureGateTest` | 4 | 0 | 0 | 0 | **PASS** |
| `JwtServiceTest` | 1 | 0 | 0 | 0 | **PASS** |
| `TotpServiceTest` | 1 | 0 | 0 | 0 | **PASS** |
| `InvitationRolePolicyTest` | 4 | 0 | 0 | 0 | **PASS** |
| `Phase0SecurityExposureTest` | 1 | 0 | 0 | 0 | **PASS** (executed, docker profile) |
| `Phase1AuthorizationSecurityTest` | 1 | 0 | 0 | 0 | **PASS** (executed) |
| `Phase1IdentityTenancyTest` | 1 | 0 | 0 | 0 | **PASS** (executed) |
| `RsReachApiApplicationTest` | 1 | 0 | 0 | 0 | **PASS** |
| `SystemControllerTest` | 1 | 0 | 0 | 0 | **PASS** (executed) |

---

## 2. Authorization / security coverage (executed)

Covered by `Phase1AuthorizationSecurityTest` + unit tests (all **PASS**, not skipped):

| Requirement | Status | Evidence |
|---|---|---|
| Cross-tenant permission isolation | **PASS** | Owner in Tenant A vs viewer in Tenant B; invite forbidden in B |
| Cross-workspace permission isolation | **PASS** | Workspace Admin vs Member; department create forbidden in member workspace |
| Missing tenant/workspace context | **PASS** | Scoped ops without headers → 403 |
| Invalid / disabled membership | **PASS** | Disabled membership → 403; foreign tenant headers → 403 |
| Customer vs staff realm separation | **PASS** | Customer token rejected on platform; staff token rejected on app |
| Platform roles rejected on customer invite | **PASS** | All `FORBIDDEN_PLATFORM_ROLES` → 403 |
| Unauthorized inviter | **PASS** | Viewer / forged path+header → 403 |
| Invitation accept revalidation | **PASS** | Expired/revoked/email mismatch/tampered token rejected; role re-checked from DB |
| Reset/invite token non-leakage (default) | **PASS** | No `devResetToken` / `devInviteToken` in API JSON |
| Docker profile token gate | **PASS** | `Phase0SecurityExposureTest` + Compose env `EXPOSE_DEV_TOKENS=false`; runtime smoke reset response keys=`status` only |

---

## 3. Frontend verification

### Commands

```powershell
cd D:\Projects\RSReach\frontend\apps\web
npm run typecheck
npm run build
```

| Check | Status |
|---|---|
| Typecheck | **PASS** |
| Production build | **PASS** (Vite) |

---

## 4. Docker Compose smoke

### Commands

```powershell
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env up --build -d
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env ps
```

| Service | Status | Evidence |
|---|---|---|
| postgres | **PASS** | healthy; port 55433 |
| api | **PASS** | healthy; `/actuator/health` → `{"status":"UP",...}` |
| worker | **PASS** | healthy after PrincipalLoader fallback fix; health UP |
| scheduler | **PASS** | healthy; health UP |
| web | **PASS** | HTTP 200; HTML title RSReach |

| Runtime security check | Status | Evidence |
|---|---|---|
| Unauthenticated `/api/v1/app/tenants` | **PASS** | HTTP 403 |
| `SPRING_PROFILES_ACTIVE` | **PASS** | `docker` |
| `RSREACH_SECURITY_PERMIT_UNAUTHENTICATED_API` | **PASS** | `false` |
| `RSREACH_SECURITY_EXPOSE_DEV_TOKENS` | **PASS** | `false` |
| Password-reset response in Docker | **PASS** | keys=`status` only (no `devResetToken`) |
| Flyway on API start | **PASS** | migrations applied; API started without migration failure |

### Startup issues found and corrected during this verification

| Issue | Severity | Correction |
|---|---|---|
| Testcontainers skipped: Docker Engine 29 returns HTTP 400 for API &lt; 1.44 | Blocker for integration tests | Operator config: `api.version=1.44` + TCP Docker host |
| Webhook duplicate ingest aborted PostgreSQL TX | Phase 0 reliability bug surfaced when tests ran | `ProviderWebhookRepository.ingest` uses `ON CONFLICT DO NOTHING RETURNING` |
| Staff/customer logout returned 403 without Bearer | Config gap | `SecurityConfig` permits POST logout endpoints (refresh-token body logout) |
| Worker/scheduler failed: missing `PrincipalLoader` | Compose blocker | `SecurityConfig` registers `NoOpPrincipalLoader` `@ConditionalOnMissingBean` |
| Compose lacked explicit `EXPOSE_DEV_TOKENS=false` on API | Hardening | Set in `docker-compose.dev.yml` (also hard-false in `application-docker.yml`) |

No Flyway V1–V9 edits.

---

## 5. Security remediation status (from prior pass; still green)

| Finding | Status |
|---|---|
| HIGH — flat-union customer permissions | **Corrected** + integration tested |
| HIGH — unrestricted invitation roles | **Corrected** + integration tested |
| MEDIUM — raw `devResetToken` / `devInviteToken` leakage | **Corrected** + default/Docker verified |

### Remaining non-blocking risks

1. Audit append-only is still application-convention (no DB trigger) — prior Medium.
2. Staff MFA first-enroll returns `enrollmentSecret` (intentional UX; must not be logged).
3. Operators must keep Docker API ≥ 1.44 for Testcontainers against Docker Desktop 4.88 / Engine 29.
4. Local `.env` verification secrets are for local only; rotate before any shared environment.

---

## Final recommendation

**Phase 1 release recommendation: APPROVED to close Phase 1 and begin Phase 2 planning.**

All required Docker-backed integration suites executed successfully (0 skipped), frontend builds, and Compose runtime health/security defaults are verified.
