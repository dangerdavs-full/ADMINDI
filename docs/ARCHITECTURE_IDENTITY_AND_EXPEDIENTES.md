# Arquitectura de Identidad, Expedientes y Contabilidad

> **Estatus**: documento rector (contrato). Todo cambio de código sobre identidad, creación de cuentas, borrado/archivado de cuentas, expedientes del inmueble o contabilidad DEBE respetar este documento. Si una regla aquí se queda corta, se actualiza este documento ANTES de tocar código.

> **Audiencia**: developers, PM, operaciones. Escrito en español porque el dominio de negocio es en español y las discusiones con el cliente son en español.

---

## 1. Motivación

Durante Fase 2 aparecieron bugs recurrentes que venían todos del mismo problema raíz: **el modelo de identidad del sistema nunca fue diseñado para un humano que tiene más de una relación con la plataforma**. Cada vez que se parchaba un síntoma (por ejemplo, "el email queda bloqueado tras borrar al tenant") se creaba otro en un camino paralelo (staff, proveedor, archivado con snapshot, archivado sin snapshot, cascada del dueño...).

Este documento cierra esa clase de bugs escribiendo explícitamente:

1. Qué es una **cuenta** en el sistema (y por qué no es lo mismo que "un humano").
2. Quién puede crear a quién.
3. Cómo se borra una cuenta y qué se preserva según el rastro contable.
4. Cómo se compone el **expediente del inmueble** (contabilidad + archivos + cronología) y cuándo se escribe en él.

Cada regla de este documento tiene cobertura directa en código en los bloques del plan `identity-expedientes-refactor`.

---

## 2. Principios rectores

1. **Una cuenta = una relación**. Si un humano renta con dos dueños, son dos cuentas con dos `username` distintos. No hay "multi-contexto" compartido.
2. **`username` identifica el login**, globalmente único. **`email` NO identifica nada**: es un campo de contacto libre, puede repetirse entre cuentas.
3. **El rastro contable es sagrado**. Si una cuenta tocó dinero (pagos, invoices, expenses, movimientos de inmueble, convenios), su historial nunca se pierde. El archivado preserva la contabilidad; el hard delete sólo se permite cuando no hay rastro.
4. **Jerarquía de creación estricta**. `SUPERADMIN` sólo puede crear `OWNER`. Un `OWNER` (o un `PROPERTY_ADMIN` con permiso explícito del `OWNER`) crea al resto dentro de su propio ámbito.
5. **El expediente del inmueble es vista agregada**, no una tabla duplicada. Se construye uniendo `property_movements`, `property_files`, `lease_files`, archivos de cotizaciones, comprobantes SPEI, invoices y snapshots archivados.
6. **Cero rastros huérfanos**. Si se hace hard delete, no queda ni una fila en `property_movements`, ni un archivo en storage, ni una invoice `PENDING` auto-generada, ni un lease sin pagos.

---

## 3. Modelo de identidad

### 3.1 Roles del sistema

| Rol | Creado por | Ámbito (`users.owner_id`) | Login | Puede crear a |
|---|---|---|---|---|
| `SUPER_ADMIN` | Seed / otro `SUPER_ADMIN` | `null` | sí | `OWNER` |
| `OWNER` | `SUPER_ADMIN` | `null` (es su propio contexto) | sí | tenant, contador, property_admin, agente privado, proveedor privado |
| `PROPERTY_ADMIN` | `OWNER` | `= owner.id` | sí | (si el `OWNER` le otorga permiso) tenant, contador, agente privado, proveedor privado |
| `ACCOUNTANT` | `OWNER` / `PROPERTY_ADMIN` autorizado | `= owner.id` | sí | — |
| `TENANT` | `OWNER` / `PROPERTY_ADMIN` autorizado | `= owner.id` | sí | — |
| `REAL_ESTATE_AGENT` (privado) | `OWNER` | `= owner.id` | sí | — |
| `REAL_ESTATE_AGENT` (plataforma) | `SUPER_ADMIN` | `null` | sí | — |
| `MAINTENANCE_PROVIDER` (privado) | `OWNER` | `= owner.id` | sí | — |
| `MAINTENANCE_PROVIDER` (plataforma) | `SUPER_ADMIN` | `null` | sí | — |

Regla de creación:

```
creador.role    → role que puede crear
SUPER_ADMIN     → OWNER, agente/proveedor de plataforma
OWNER           → TENANT, ACCOUNTANT, PROPERTY_ADMIN, REAL_ESTATE_AGENT privado, MAINTENANCE_PROVIDER privado
PROPERTY_ADMIN  → (sólo con permiso del OWNER) TENANT, ACCOUNTANT, agentes/proveedores privados
```

### 3.2 Identidad y contacto

| Campo | Unicidad | Rol |
|---|---|---|
| `users.id` | UUID global | identificador interno inmutable |
| `users.username` | UNIQUE global, NOT NULL | identificador de login |
| `users.email` | **NO UNIQUE**, puede ser `NULL` | canal de contacto para notificaciones/password reset |
| `users.contact_email` | no unique, cifrado | canal de contacto alterno declarado por el user |
| `users.phone`, `users.contact_phone` | no unique, cifrados | canales de contacto |

Implicaciones:

- Dos cuentas con el mismo `email` son perfectamente válidas (distinto `username`, distinto humano o mismo humano en contextos distintos).
- El humano recibe al crearse su cuenta un email de bienvenida con su **`username`** y su **password temporal** (`must_change_password = true`).
- Si el humano olvida su username, puede pedir "mándame mis usuarios a mi correo" y recibe la lista de cuentas activas asociadas a ese `email`.

### 3.3 Casos clásicos resueltos

| Caso | Antes (bug) | Con este modelo |
|---|---|---|
| Juan es tenant del Dueño A y del Dueño B | bloqueado, `email UNIQUE` | Dos cuentas `juan-A` y `juan-B`, email libre |
| Juan fue tenant del Dueño A, lo archivó, ahora Dueño B lo quiere como tenant | bloqueado | Nueva cuenta `juan-B`, la cuenta archivada de A no interfiere |
| Juan fue tenant de A, ahora se registra como `OWNER` | bloqueado | Nueva cuenta `juan-owner`, email libre |
| Juan es agente privado de A y proveedor privado de B | bloqueado | Dos cuentas independientes |
| Juan renta dos inmuebles del mismo Dueño A | ya funciona | **Una** cuenta `juan-A` con dos `tenant_profiles` |

---

## 4. Ciclo de vida de una cuenta

Cinco estados posibles para una cuenta (`users`):

| Estado | `active` | `deleted_at` | `username_tombstoned_at` | Semántica |
|---|---|---|---|---|
| `ACTIVE` | true | null | null | cuenta en uso |
| `INACTIVE` | false | null | null | desactivada temporalmente por el creador, username intacto |
| `ARCHIVED` | false | timestamp | null | archivada con rastro contable; puede reactivarse (no es común, requiere decisión explícita) |
| `TOMBSTONED` | false | timestamp | timestamp | archivada + `username` liberado para reutilizar; la fila vive sólo por integridad referencial del rastro contable |
| `DELETED` | n/a | n/a | n/a | fila inexistente (hard delete) |

El estado **`TOMBSTONED`** es la clave: preserva toda la contabilidad (foreign keys de `payments`, `expenses`, `property_movements` siguen válidas) y al mismo tiempo libera el `username` para que esa cuenta **pueda ser re-creada** por otro creador sin colisión.

### 4.1 Tombstone del username

Formato:

```
<username>         →  tombstone-<id8>-<yyyyMMddHHmmss>
juan-A             →  tombstone-1a2b3c4d-20260417183042
```

- `<id8>` es los primeros 8 caracteres del UUID de la cuenta.
- El timestamp da unicidad determinista aunque dos tombstones caigan en el mismo segundo sobre IDs distintos.
- Una vez tombstoneado, el username original queda disponible globalmente.

### 4.2 Regla única de retención

```
¿la cuenta tiene rastro contable?
    SÍ  → archivar (opcionalmente tombstonear el username si el creador quiere liberarlo)
    NO  → hard delete completo
```

Definición de **rastro contable** (consultado vía COUNT por `userId` o sus `tenant_profile_id`s):

1. `payments` donde `tenant_profile_id` pertenece al user.
2. `invoices` con `status ∈ {PAID, PARTIALLY_PAID, VOID}` donde `tenant_profile_id` pertenece al user. Invoices `PENDING` recién auto-generadas **no cuentan** como rastro.
3. `expenses` donde `provider_user_id = userId`.
4. `property_movements` donde `actor_user_id = userId` **y** tiene `attachment_file_id` o `resource_id` significativo. Un `TENANT_EXPEDIENTE_OPENED` sin más movimientos no cuenta como rastro (se limpia en hard delete).
5. `transfer_proof_submissions` donde el user figura.
6. `payment_agreements` donde el user figura.
7. `maintenance_quotes` / `maintenance_budgets` / `commercial_activities` donde el user es actor.
8. `tenant_archive_snapshots` donde `tenant_user_id = userId`.

Si alguna de esas consultas regresa > 0, es **rastro contable**. Si todas son 0, no hay rastro.

### 4.3 Efectos del hard delete

Borra en este orden (dentro de una sola transacción):

1. Convenios del user y sus installments; archivos adjuntos en storage.
2. Comprobantes de transferencia y attempts CEP; archivos en storage.
3. Pagos (que no existen si la regla de retención se respeta).
4. Invoices `PENDING` auto-generadas.
5. Leases sin pagos asociados + sus `lease_files` + documento PDF en storage.
6. `tenant_profiles` del user.
7. `platform_provider_assignments` donde el user es provider.
8. `owner_memberships` del user.
9. `permission_grants` emitidos al user.
10. `property_movements` residuales sin adjuntos asociados a ese user (incluye `TENANT_EXPEDIENTE_OPENED`).
11. Archivos del user subidos con `FileOwnership.registerClaim` (Fase 2) y aún no consumidos: storage + claims.
12. `file_upload_claims` del user.
13. `refresh_token_sessions`.
14. `notification_preferences` y `notifications` del user.
15. `action_tasks` del user.
16. `user_activation_tokens` del user.
17. `users.delete(id)`.

### 4.4 Efectos del archivado (con rastro)

1. Si aplica (tenant con historial pagado): `TenantArchiveSnapshotService.buildAndPersist`.
2. `voidAllOpenInvoicesForTenant`.
3. `leases` activos del tenant → `status = TERMINATED`, `endDate = today`.
4. `tenant_profile.archivedAt = now`.
5. `user.active = false`, `user.deleted_at = now`.
6. `user.password = bcrypt(random)`, `user.mfa_enabled = false`, `user.mfa_secret = null`.
7. `user.permissions.clear()`.
8. (Opcional, decidido por el creador) tombstone del `username` y `username_tombstoned_at = now`.
9. `refresh_token_sessions.deleteByUserId`.
10. `user_activation_tokens.deleteByUserId`.
11. `platform_provider_assignments.active = false` (no se borran).
12. Escribe `property_movement` `TENANT_EXPEDIENTE_ARCHIVED` (o `STAFF_ACCOUNT_ARCHIVED` para no-tenants).

### 4.5 Caso especial: `OWNER` borrado por `SUPERADMIN`

Sigue rigiendo `OwnerCascadeDeletionService.hardDeleteOwner` (ver código actual). Un owner arrastra todo su universo de datos: properties, units, leases, invoices, payments, tickets, vacancies, property_movements, property_files, expenses, tenant_profiles del owner y snapshots. Los usuarios "colgados" de ese owner (tenants, staff, agentes, proveedores privados) se cascadan:

- Si esos users tenían **otro** contexto vivo (otro dueño, otra asignación de plataforma) → se mantiene la cuenta, sólo se limpia el pointer `users.owner_id = null`.
- Si NO tenían otro contexto → se invocan las reglas 4.2–4.4 para cada uno.

### 4.6 Caso especial: `SUPER_ADMIN`

- Invariante absoluta: un `SUPER_ADMIN` **nunca** se borra por las rutas de lifecycle. Sólo otro `SUPER_ADMIN` autenticado con MFA puede desactivarlo por un endpoint dedicado (fuera de este plan).
- **Sin datos de contacto, sin notificaciones**. `SUPER_ADMIN` es un rol puramente administrativo (crear dueños, reset password/MFA, eliminar cuentas). No se le escribe `contactEmail`, `contactPhone` ni `whatsapp`, no recibe IN_APP, email ni WhatsApp, y está fuera de toda preferencia de notificación.
  - Enforcement en creación: `AuthService.createSuperAdmin` nulifica `contactEmail/contactPhone` y el seed QA (`QaEtapa0SeedService.ensureUser`) hace lo mismo, incluso sobre filas preexistentes.
  - Enforcement en despacho: `DomainEventDispatcher.filterOutSuperAdmins` descarta silenciosamente a cualquier `SUPER_ADMIN` que se cuele en `recipientUserIds`, antes de crear IN_APP, email o WhatsApp. Si aparece, queda registrado en log `debug` y no en audit (no es un evento de negocio).
  - El campo `email` del `SUPER_ADMIN` sólo existe como identificador histórico de login (backfill V48 → `username`). No se usa como canal.

---

## 5. Expediente del inmueble

### 5.1 Qué es

El expediente del inmueble es la **vista cronológica + documental** de todo lo que le ha pasado a un inmueble del dueño: ingresos recibidos, egresos ejecutados, tickets de mantenimiento, presupuestos aprobados, tenants que lo habitaron, archivos subidos (contratos, comprobantes, fotos, PDFs).

Se expone por `GET /api/properties/{propertyId}/expediente` y devuelve un DTO agregado (no hay tabla nueva). Se lee de:

| Origen | Uso |
|---|---|
| `property_movements` | cronología (eje temporal del expediente) |
| `property_files` | fotos, planos, otros archivos del inmueble |
| `lease_files` | documentos firmados por lease |
| `leases` + `lease.documentUrl` | PDF del contrato vigente/histórico |
| `invoices` | cuentas por cobrar (histórico por tenant_profile) |
| `payments` + `transfer_proof_submissions` | pagos recibidos y sus comprobantes SPEI |
| `maintenance_tickets` + `photo_file_ids` | fotos del tenant al abrir ticket |
| `maintenance_quotes.evidenceFileId` (`budget_file_id`) | PDF del presupuesto del proveedor |
| `expenses` + `budget_file_id` + `payment_proof_file_id` | egresos aprobados y pagados del inmueble |
| `tenant_archive_snapshots` | historial de tenants archivados |

### 5.2 Invariantes del expediente

1. **Todo pago de renta** (SPEI, efectivo, tarjeta) produce:
   - Una fila en `payments`.
   - Un `PropertyMovement` `PAYMENT_RECEIVED` con `attachment_file_id` = proof/evidencia si existe.
2. **Todo ticket de mantenimiento pagado** produce:
   - Una `ExpenseEntity` con `status = APPROVED` creada al **aprobar el presupuesto**, con `budget_file_id` = PDF del proveedor.
   - La misma `ExpenseEntity` con `status = PAID` y `payment_proof_file_id` = SPEI proof del dueño, al cerrar el pago. **No se sobrescribe el `budget_file_id`**.
   - `PropertyMovement` `MAINTENANCE_TICKET_OPENED`, `MAINTENANCE_QUOTE_UPLOADED`, `MAINTENANCE_QUOTE_APPROVED`, `MAINTENANCE_PAYMENT_SETTLED` según avance, cada uno con su `attachment_file_id` correspondiente.
3. **Tenant sin pagos archivado** → `purgeWithoutTrace` también borra los `property_movements` residuales (`TENANT_EXPEDIENTE_OPENED`) para que el expediente no muestre una línea fantasma de alguien que nunca transfirió.
4. **Tenant con pagos archivado** → permanece en el expediente agregado a través de `tenant_archive_snapshots` + los pagos/movimientos ya persistidos.

### 5.3 Contrato del endpoint

```
GET /api/properties/{propertyId}/expediente
Authorization: OWNER|PROPERTY_ADMIN del owner del inmueble

{
  "property": { ... resumen ... },
  "timeline": [ PropertyMovement... ],              // ordenado desc por occurred_at
  "files": {
    "propertyFiles": [...],                         // property_files
    "leaseDocs": [ { leaseId, files, contractUrl } ],
    "maintenance": [ { ticketId, photos, budgetFileId, proofFileId } ],
    "payments": [ { paymentId, proofFileId, invoiceId } ]
  },
  "accounting": {
    "invoices": [...],
    "payments": [...],
    "expenses": [...]
  },
  "tenants": {
    "active": [ TenantProfileSummary... ],
    "archived": [ TenantArchiveSnapshotSummary... ]
  }
}
```

El endpoint es **sólo lectura**. No crea ni duplica filas; construye el DTO en memoria por query. El caching es opcional y no forma parte de este plan.

---

## 6. Catálogo de servicios afectados por cada regla

| Regla | Servicios / archivos |
|---|---|
| 3.2 Login por username | `UserEntity`, `UserRepository`, `ApplicationConfig.UserDetailsService`, `AuthService`, `AuthRequest`, `JwtService`, `JwtAuthenticationFilter`, `TenantContext`, `ReauthService`, frontend `Login.tsx` |
| 3.1 Jerarquía de creación | `OwnerService.createOwner`, `TenantService.createTenant`, `StaffService.createStaff`, `MaintenanceProviderService.createPrivateProviderForOwner`, `OwnerLinkedProviderController`, `SuperAdminUserController` |
| 3.2 Email libre | quitar `unique=true` de `UserEntity.email`, quitar `UNIQUE` en DDL, cambiar dedup en todos los create-* a `existsByUsername` |
| 4.2 Retención única | `AccountLifecycleService` (nuevo), `OwnerCascadeDeletionService.hardDeleteOwner` se preserva, `TenantExpedienteArchiveService` y `deleteOrTombstoneStaffOrProvider` se deprecan |
| 4.3 Hard delete completo | `AccountLifecycleService.hardDeleteNoTrace`, `StorageService.delete`, `FileUploadClaimRepository` |
| 4.4 Archivado | `AccountLifecycleService.archiveWithSnapshot`, `TenantArchiveSnapshotService` (reusado) |
| 5.1 Expediente agregado | nuevo `PropertyExpedienteController` + `PropertyExpedienteService` (sólo lectura) |
| 5.2 Invariante pago → movement | `LedgerService.autoConfirmPayment`, `LedgerService.markAsPaidManual`, `PropertyMovementService.recordPaymentMovement`, `MercadoPagoService` |
| 5.2 Invariante mantenimiento → expense | `MaintenanceWorkflowService.ownerApproveQuote`, `MaintenanceWorkflowService.ownerPayAndClose`, `PropertyMovementService.record` |

---

## 7. Reglas de migración (de lo que hay hoy a este modelo)

### 7.1 Migración Flyway

| Migración | Cambio |
|---|---|
| `V48__users_username_and_email_non_unique.sql` | añade `users.username VARCHAR(64) NOT NULL UNIQUE`, `users.username_tombstoned_at TIMESTAMP NULL`, backfill `username = email` por cada fila, suelta el constraint UNIQUE de `email` |
| `V49__accounting_fixes.sql` | añade `expenses.budget_file_id`, `expenses.payment_proof_file_id`; backfill `budget_file_id = evidence_file_id` cuando la expense viene de un `MAINTENANCE_QUOTE`; `invoices.lease_id` queda NOT NULL a partir de la fecha de migración (no retroactivo) |

### 7.2 Data existente

- Cada `UserEntity` existente obtiene `username = email` (el `email` sigue siendo el mismo string; sólo pasa a no ser único). Esto preserva los logins actuales de QA, de los admins y de la producción.
- `owner_memberships` se **congela**: se deja de escribir desde los caminos nuevos, pero las filas existentes siguen funcionando para resolución de contexto legada durante el periodo de transición.
- `tenant_profiles` no cambia de schema.
- `password`, `mfa_secret`, `permissions` no cambian.

### 7.3 Compatibilidad en login

Durante un periodo de transición (marcado con feature flag `auth.accept_email_as_username=true`):

- `POST /auth/login` acepta `{ username, password }` o `{ email, password }`.
- Si se envía `email`, el backend hace `findByUsername(email)` primero (funciona porque al migrar cada `username = email`).
- Tras confirmar en producción que todos los clientes mandan `username`, se desactiva el flag.

---

## 8. Qué queda fuera de este documento

- UI completa de creación de cuentas por parte del OWNER (se cubre en frontend post-bloque-2).
- Tests automatizados exhaustivos de `AccountLifecycleService` (se cubren en bloque 3).
- Estrategia de partitioning o archivado de `property_movements` para inmuebles con años de historia.
- Integración con Banxico real (mock sigue rigiendo).
- Pagos con tarjeta y efectivo desde el frontend del tenant (fuera de Fase 2).

---

## 9. Glosario

- **Cuenta** = fila en `users`. Un humano puede tener N cuentas distintas (una por relación).
- **Humano** = persona física. No es una entidad del sistema.
- **Dueño / OWNER** = cuenta con `role=OWNER`, dueña de sus propios contextos, inmuebles y universo de usuarios privados.
- **Expediente del inmueble** = vista agregada por inmueble (no es una tabla).
- **Expediente del tenant** = `tenant_profile` + sus snapshots archivados + los pagos/invoices/movimientos que lo involucran.
- **Rastro contable** = ver sección 4.2.
- **Tombstone** = técnica de renombrar el `username` de una cuenta archivada para liberar el índice UNIQUE.

---

## 10. Historial del documento

- **2026-04-17** — v1. Creado como contrato del refactor `identity-expedientes-refactor`. Decisiones tomadas con el usuario en chat: login por username, email libre, multi-cuenta no, multi-inmueble sí, retención única por rastro contable. Autor: refactor planificado (bloques 0–5).
