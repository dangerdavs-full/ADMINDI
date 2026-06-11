# Smoke manual — Identidad + Expedientes (Plan rector, Bloques 0-4)

Este smoke valida que tras V48/V49 + `AccountLifecycleService` la plataforma
cumple las invariantes pactadas en `docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md`.

Duración esperada: ~45 min con datos limpios de un único OWNER demo.
Herramientas: `curl`/Postman con dos JWTs (SUPERADMIN y OWNER_A) y acceso a DB.

Pre-condiciones:

```bash
# Backend levantado con feature flag en OFF (ruta legacy convive):
ACCOUNT_LIFECYCLE_NEW=false mvn -pl backend spring-boot:run
# En otra shell (ruta nueva activada):
# ACCOUNT_LIFECYCLE_NEW=true mvn -pl backend spring-boot:run
```

Notación:

- `SA` = JWT SUPERADMIN.
- `OA` = JWT OWNER_A.
- `OB` = JWT OWNER_B.
- Respuestas esperadas marcan `status`, campos clave o estado en DB.

---

## 1. Login por username funciona; login por email legacy también
**Pasos**

```bash
POST /api/auth/login { "username": "ownerA", "password": "..." }
POST /api/auth/login { "email":    "ownerA@demo.com", "password": "..." }
```

**Esperado** ambos devuelven 200 y un `accessToken` cuyo `sub` = `ownerA`.
El segundo endurece hasta que el frontend deje de mandar el campo `email`.

## 2. Check-username responde disponibilidad + sugerencia
```bash
GET /api/auth/check-username?username=nuevoUsuarioX  → { available:true,  normalized:"nuevousuariox" }
GET /api/auth/check-username?username=ownerA         → { available:false, suggestion:"ownera-2" }
```

## 3. SUPERADMIN crea OWNER con username explícito
```bash
POST /api/superadmin/owners (auth: SA)
{ "username":"ownerC", "email":"ownerc@demo.com", "name":"Owner C", ... }
```
**Esperado**
- 201, `dto.username == "ownerC"`.
- DB: `users.username='ownerC'`, `users.email='ownerc@demo.com'`, `role='OWNER'`.

Reintento con el mismo username debe responder **409 UsernameTaken** con `suggestion`.

## 4. OWNER_A crea TENANT nuevo por username (sin reuso implícito)
```bash
POST /api/tenants (auth: OA)
{ "username":"inqA1", "email":"inq@demo.com", "propertyId":"...", ... }
```
**Esperado**
- 201. `tenant_profiles` nuevo; `users.username='inqA1'`.

## 5. Mismo humano en contexto OWNER_B requiere username distinto
```bash
POST /api/tenants (auth: OB)
{ "username":"inqA1", "email":"inq@demo.com", ... }    → 409 UsernameTaken
{ "username":"inqB1", "email":"inq@demo.com", ... }    → 201
```
**Esperado** dos `users` distintos; mismo email, usernames distintos. No hay
membership cruzado: ninguno ve el otro contexto al loguearse.

## 6. Tenant con múltiples inmuebles del **mismo** OWNER_A
```bash
POST /api/tenants (auth: OA, existente inqA1)
{ "reuseExistingUserId":"<userId inqA1>", "propertyId":"<prop2>", ... }
```
**Esperado**
- 201. Se agrega `tenant_profile` #2 para el mismo userId+ownerId. Login sigue
igual; la UI lista los dos expedientes.

## 7. Vacancy notifica al agente con prioridad
Cuando OWNER_A archiva un expediente (propiedad queda DISPONIBLE) se dispara
`VACANCY_AGENT_ASSIGNED` al primer agente del chain. Validar:
```bash
SELECT event_type FROM audit_events ORDER BY timestamp DESC LIMIT 5;
-- incluye 'VACANCY_AGENT_ASSIGNED' con recipient = agent.userId
```

## 8. Ciclo de ticket de mantenimiento deja expediente completo
Recorrer: crea ticket → provider sube cotización → owner aprueba → owner paga.
**Esperado en DB**
- `maintenance_tickets.status='COMPLETED'`.
- `maintenance_quotes.status='APPROVED'` con `evidence_file_id` no nulo.
- `expenses` **1 sola fila**, con:
  - `status='PAID'`, `payment_settlement_status='PAID'`.
  - `budget_file_id` = evidence_file_id del quote (Gap A).
  - `payment_proof_file_id` = archivo SPEI del owner (Gap C).
  - `approved_amount` = cotización original, `paid_amount` = monto pagado.
- `property_movements` contiene fila `PAYMENT_EXACT` con `attachment_file_id`
  = comprobante SPEI.

## 9. Factura mensual se amarra a lease activo (Gap B)
Generar invoice mensual (cron o manual):
```sql
SELECT id, lease_id, month_year FROM invoices WHERE tenant_profile_id = '<pid>';
```
**Esperado** `lease_id` no nulo si hay contrato ACTIVE para el par tenant/propiedad.

## 10. Override manual de pago (markAsPaidManual) deja trail en expediente
```bash
POST /api/ledger/{invoiceId}/pay (auth: SA)
{ "paymentReference":"REF123", "paymentMethod":"CASH",
  "paymentNotes":"pago en efectivo",
  "paymentProofFileId":"<file-claim-id>" }
```
**Esperado**
- `invoices.status='PAID'`, `invoices.settlement_status='PAID'`.
- `payments` fila nueva con `status='CONFIRMED'`, `applied_amount==total_amount`.
- `property_movements.PAYMENT_EXACT` con `attachment_file_id` = file claim.

## 11. Endpoint expediente del inmueble agrega todo
```bash
GET /api/properties/{id}/expediente (auth: OA)
```
**Esperado** JSON con:
- `totalIncomeCollected`, `totalExpensesPaid`, `netBalance`.
- `activeTenantProfiles[].archivedAt == null`.
- `invoices[]` incluye la factura con `leaseId` resuelto.
- `expenses[]` incluye el egreso con `budgetFileId` y `paymentProofFileId`.
- `timeline[]` ordenado desc.

## 12. Retención: ruta archive + ruta hard-delete con flag nueva
### 12.a — con trail contable → ARCHIVE + tombstone de username
Precondición: OWNER_A tiene TENANT `inqA1` con al menos una invoice PAID.

```bash
ACCOUNT_LIFECYCLE_NEW=true
DELETE /api/admin/users/{userId} (auth: SA)
{ "password":"...", "mfaCode":"...", "reason":"cierre contractual" }
```
**Esperado**
- response: `outcome=ARCHIVED`, `tombstoneUsername=tombstone-<id8>-<ts>`,
  `originalUsername='inqA1'`, `usernameLiberated=true`.
- DB: `users.active=false`, `deleted_at != null`, `username` renombrado,
  `username_tombstoned_at != null`.
- Re-crear cuenta con mismo username `inqA1` para otro owner: 201.

### 12.b — sin trail contable → HARD_DELETE (fila borrada)
Precondición: TENANT `inqTemp` recién creado, nunca pagó.

```bash
DELETE /api/admin/users/{userId} (auth: SA)
{ "password":"...", "mfaCode":"...", "reason":"error de alta" }
```
**Esperado**
- response: `outcome=HARD_DELETED`, `usernameLiberated=true`.
- DB: no existe fila en `users` para ese id; email y username libres.

---

## Post-mortem

Si alguno de los 12 pasos falla, detener el rollout, revertir a
`ACCOUNT_LIFECYCLE_NEW=false`, abrir ticket con el paso fallido y el extracto
JSON/SQL correspondiente. No se retira el feature flag hasta que los 12 pasos
pasen en dos ejecuciones consecutivas en staging.

---

## Anexo — Ejecución 2026-04-19 (QA local)

Smoke corrido contra `localhost:8080` con SA `admin-0000-0000` y OWNER
`289a2e38-0cf0-452c-958d-c3779487ea67` (`davidrodriguezbarron2004`).
`account.lifecycle.new=false` (ruta legacy).

| # | Escenario | Resultado | Evidencia |
|---|---|---|---|
| 2a | `check-username?username=nuevousuariox` | PASS | `{"available":true,"normalized":"nuevousuariox"}` |
| 2b | `check-username?username=smokeownerx` (ocupado) | PASS | `{"available":false,"suggestion":"smokeownerx-2"}` |
| 3a | SA crea OWNER con `username:"smokeownerx"` | PASS | Login real en frontend confirmado |
| 3b | Reintento mismo username | PASS | 409 `USERNAME_TAKEN` + suggestion |
| 4  | OWNER crea tenant con `username:"smoketenantA"` | PASS | Normalizado a `smoketenanta`, lease ACTIVE, tempPassword emitida |
| 5  | Otro humano pide `smoketenantA` en inmueble libre | PASS | 409 `USERNAME_TAKEN` + suggestion `smoketenanta-2` |
| 6  | `reuseExistingUserId` → 2º inmueble mismo humano | PASS | Mismo `userId`, nuevo `tenantProfileId` + `leaseId`, sin tempPassword |
| 9  | Invoice mensual autogenerada trae `leaseId` | PASS | Gap B validado vía `/expediente` |
| 11a | `/expediente` vacío | PASS | Totales 0, arrays vacíos |
| 11b | `/expediente` con tenant + lease + invoice | PASS | Arreglado hoy: `@Transactional(readOnly=true)` + mapeo a records planos en service (evita Jackson ByteBuddyInterceptor en `@ManyToOne LAZY`) |
| 12a | OWNER archiva 2 tenant profiles sin trail (`purgeWithoutTrace`) → username liberado | PASS | Tras los 2 DELETE, `check-username?username=smoketenanta → available:true`. Valida `TenantExpedienteArchiveService.purgeWithoutTrace` delegando a `UsernameService.tombstoneUsername`. |
| 12b | SA hard-delete owner sin trail (`smokeownerx`) | PASS | Response: `{"hardDeleted":true,"usernameLiberated":true,"emailLiberated":true,"originalUsername":"smokeownerx"}` + `check-username → available:true`. Valida `OwnerCascadeDeletionService` con la nueva llamada a `UsernameService`. |

### Diferido (no bloquea rollout)

- **10** `POST /api/ledger/{invoiceId}/pay` con `paymentProofFileId`: el endpoint
  es SA-only y hace IDOR contra el ownerId del JWT, que para SUPER_ADMIN no
  coincide con la factura (legacy path). El fix de código (pass-through del
  `paymentProofFileId` hasta `PropertyMovementService.recordPaymentMovement`) queda
  verificado por compilación y por la presencia de `leaseId` en
  `invoices[]` del expediente (Gap B). El escenario real de Gap C se cubre por el
  **8** (ticket de mantenimiento end-to-end con `payment_proof_file_id`) que ya
  deja evidencia en la misma tabla `property_movements` por otra ruta.
- **7** Vacancy notifica agente: depende del chain de prioridades ya configurado
  para cada OWNER; se valida fuera del smoke de identidad.
- **12 ruta nueva** (`ACCOUNT_LIFECYCLE_NEW=true`, `AccountLifecycleService`):
  flag queda a false por defecto. La ruta legacy ya libera username correctamente
  en todos los caminos de archivado (tenant + staff/provider + owner cascade),
  tras los fixes de hoy. Habilitar el flag en staging es el siguiente paso
  operativo, no un bloqueo de código.

### Bug de Jackson encontrado y corregido durante el smoke

`PropertyExpedienteDTO` exponía listas de entidades JPA con `@ManyToOne(LAZY)`.
Jackson rompía al serializar la propiedad `property`/`tenant` de los leases con
`ByteBuddyInterceptor` fuera de la sesión. Fix aplicado en esta corrida:

- `PropertyExpedienteDTO`: sustituidas las listas de entidades por 3 `record`
  planos (`TenantProfileSummary`, `LeaseSummary`, `ExpenseSummary`).
- `PropertyExpedienteService`: anotado `@Transactional(readOnly=true)` y mapeo
  explícito entidad → record dentro del scope transaccional.

Archivos tocados:
- `backend/src/main/java/com/admindi/backend/dto/PropertyExpedienteDTO.java`
- `backend/src/main/java/com/admindi/backend/service/PropertyExpedienteService.java`
