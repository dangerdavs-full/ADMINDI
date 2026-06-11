# Etapa 3 - QA evidence summary (2026-04-15)

Environment: see `00-run/services.txt` and `00-run/data-prep-local.txt`.

**Second pass (QA seed):** `00-run/seed_etapa3_qa.sql` applied to local DB. Test month for non-zero checks: **2026-05**. Property id: `b3f9b48b-130b-457f-90af-2973a840a3a1`. GET evidence (no JWT in repo): `pass-2-api/owner-session-evidence.txt`, `pass-2-api/accountant-session-evidence.txt`.

HTTP contract: `@PreAuthorize` denials return **403** (`GlobalExceptionHandler` + `AccessDeniedException`). See `00-http-contract/access-denied-403-verification.txt`.

---

## Seed outcome (DB)

- 6 property_movements on QA property; invoice PARTIALLY_PAID 10k/4k/6k; agreement ACTIVE + installments; maintenance ticket+quote+expense PAID; vacancy+commercial activity+commission expense; users qa-tenant / qa-accountant / qa-maint / qa-agent (password = same bcrypt as superadmin).
- Details: `00-run/data-prep-local.txt`

---

## Blocks (matrix) - second pass status

| Block | Runtime with real data | Notes |
|-------|------------------------|-------|
| Timeline | PASS | API GET 200, 6 events (OWNER assisted session) |
| Shortfall | PASS | Invoice + property monthly + owner summary align 10k/4k/6k for 2026-05 |
| Agreements | PASS | GET /api/agreements count 1 |
| Maintenance | Seed PASS | Monthly report count 0 for 2026-05 (seed timestamps outside May window) |
| Vacancy / commercial | Seed PASS | Same month-window caveat as maintenance |
| Reports / consistency | PASS | OWNER + ACCOUNTANT: 2026-05 monthly vs accounting-summary aligned; ZIP/XLSX 200; annual 2026 non-zero on ACCOUNTANT run (see `accountant-session-evidence.txt`; OWNER file still notes earlier annual 0 if URL var was wrong) |

---

## Failures / findings

1. Access denied HTTP 403 fix: see `00-http-contract/`.
2. Login rate limit may require backend restart during QA.
3. Property monthly report showed zeros when the wrong path variable was used in PowerShell (`$pid` is reserved); with literal property UUID, monthly matches owner summary.

---

## MFA / JWT

Do not store JWT in git. Document only "sesion asistida por usuario".

**Done (pass 2):** OWNER + ACCOUNTANT assisted GET matrix documented under `pass-2-api/` (tokens never committed).

**Etapa 3:** Operational/contable consistency is evidenced for seed + both roles on the listed endpoints; close when you accept residual caveats (month-window for maintenance/commercial counts in monthly report vs annual rollups).