ADMINDI — Guía semilla QA Etapa 0 (solo entornos no productivos)
================================================================

IMPORTANTE (criterio senior)
-----------------------------
- **READY_LOCAL_QA** y **READY_PROD** son estados distintos. Verde en local/QA **no** cierra Etapa 0 en estándar producción.
- Los UUID “fijos” en código (`QaEtapa0Constants`) son **referencia documental** cuando `strict-user-ids=true`. En **local/qa** el default es `strict-user-ids=false`: la BD puede tener **otros ids**; la semilla adopta el id existente y relaciónalo con el checklist de abajo para smoke.

Estados
-------
- **READY_LOCAL_QA**: Postgres + Redis + backend perfil `local` o `qa`, semilla si aplica, smoke `etapa0-smoke.ps1` con variables correctas para **esa** BD.
- **READY_PROD**: cumple **integralmente** el “Checklist prod-like obligatorio” de `ENTREGA_ETAPA_0_FINAL.txt` (y orquestación no depende de credenciales QA del repo).

Activación de la semilla
------------------------
Por defecto `application.yml`: `admindi.qa-seed.enabled=false`.

1) Perfil **local** o **qa** (`application-local.yml` / `application-qa.yml`): semilla **on** salvo `ADMINDI_QA_SEED_ENABLED=false`.
2) Cualquier perfil: `ADMINDI_QA_SEED_ENABLED=true` fuerza semilla on (usar solo en entornos controlados).

**Producción:** `application-prod.yml` fija `admindi.qa-seed.enabled=false`. En despliegue real fijar también `ADMINDI_QA_SEED_ENABLED=false` en variables del runtime (doble barrera operativa).

strict-user-ids (ids QA)
-------------------------
- **`admindi.qa-seed.strict-user-ids=true`** (p. ej. `ADMINDI_QA_SEED_STRICT_USER_IDS=true`): el email QA debe coincidir con el **UUID fijo** de documentación; si no, **falla el arranque**.
- **`strict-user-ids=false`** (default en **local** y **qa**): si el email ya existe con **otro id**, se **adopta** el id de BD (warning en log). El **rol** distinto sigue siendo error.

**SMOKE_CONTEXT_ID (tenant multi, select/switch):** no usar UUID fijos del doc como si fueran universales.

1) Tras `POST /auth/login` como tenant multi, leer el mapa `organizations` en la respuesta (o `GET /auth/contexts` con el Bearer recibido) y copiar el **id** del owner elegido (Alpha/Bravo).
2) Opcional: línea de log `[QA_SEED] Etapa 0 seed aplicado…` para ids de **propiedades** sembradas (no sustituye `organizations` para context).

Contraseña y MFA de semilla (solo QA)
-------------------------------------
- Contraseña común plaintext documentada en `QaEtapa0Constants.QA_PASSWORD_PLAINTEXT` (no usar en prod).
- TOTP Base32 compartido semilla: `JBSWY3DPEHPK3PXP` (owners QA de semilla, staff, superadmin QA). Ejemplo: `oathtool --totp -b JBSWY3DPEHPK3PXP`.
- Smoke owner QA archive: `SMOKE_ARCHIVE_MFA_CODE` con ese TOTP si el login devuelve challenge MFA (`AuthService` exige MFA para rol OWNER).

Usuarios / escenarios (emails estables)
---------------------------------------
Ver `QaEtapa0Constants.java` y `QaEtapa0SeedService.java`.

- Tenant multi: `qa.etapa0.tenant.multi@test.local` (requiere `SMOKE_CONTEXT_ID` real por contexto, ver arriba).
- Owners QA: alpha / bravo / archive `@test.local` (MFA de semilla si aplica).
- Staff MFA + grants; superadmin MFA QA.
- Property admin: en ambos Owners (**Alpha** y **Bravo**) el grant es `tpl-full-access`. Históricamente Alpha usó `tpl-property-admin-operational` (V27/V28), pero la migración V40 consolidó los niveles a 3 (Acceso Total / Contador / Solo Lectura) y reubicó los grants del template operacional en `tpl-full-access`. Smoke `SMOKE_FULL_ETAPA0`: Alpha se elige por nombre en `GET /auth/contexts` (default `QA Owner Alpha`; `SMOKE_PROPADMIN_ALPHA_CONTEXT_NAME` o `SMOKE_OWNER_ALPHA_ID`).
- Archivo: owner `qa.etapa0.owner.archive@test.local`, tenant `qa.etapa0.tenant.archive@test.local`; `tenantProfileId` por `GET /api/tenants` como ese owner o autodetección en `etapa0-smoke.ps1`.

Archivo / smoke
---------------
- `scripts/etapa0-smoke.ps1` (cabecera): variables, MFA archive, descubrimiento de profile.
- **Prod-like (sin `qa.etapa0.*`):** `scripts/etapa0-prod-smoke.ps1` — variables `SMOKE_PROD_*`; evidencia por corrida bajo `qa-evidence/2026-04-15/etapa-0/prod/runs/<run-id>/` (inmutable; ver `prod/runs/README.txt`). Ver `PROD_SECRET_RUNBOOK.txt` y acta Etapa 0.
- **RUTA determinista semilla:** mismo owner QA archive + mismo tenant archive (no mezclar con un owner Gmail si el expediente no está bajo ese owner en BD).

Rate limit
----------
`admindi.rate-limit.*` en `application.yml`; prod en `application-prod.yml` (login 5 / 15 min).

Checklist prod-like (referencia; detalle en ENTREGA_ETAPA_0_FINAL.txt)
----------------------------------------------------------------------
Antes de afirmar cierre de Etapa 0 en prod: `spring.profiles.active=prod`, `ENCRYPTION_KEY` presente y validada, `ADMINDI_QA_SEED_ENABLED=false`, arranque sin semilla, auth/refresh/reuse/logout y contextos donde aplique, **sin** depender de credenciales QA del repositorio, evidencia archivada.
