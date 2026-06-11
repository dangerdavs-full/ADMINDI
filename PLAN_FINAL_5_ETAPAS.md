# ADMINDI — Plan Final en 5 Etapas

> Plan consolidado, accionable y centrado en desbloquearte. Reemplaza a `PLAN.md`, `PLAN me gusta.md`, `PLAN mejorado 1.md`, `PLAN mejorado mejorado 1.md` y `PLAN consolidado.md`. Ya no se vuelve a rediseñar el modelo: se cierra.

## Desbloqueo inmediato: tu modelo ya resuelve "un usuario con muchos dueños y 2+ inmuebles rentando con el mismo dueño"

Lo que llevas todo el día buscando **ya existe en tu código**:

- `UserEntity` → una sola cuenta por email, global para toda la plataforma.
- `OwnerMembershipEntity (userId, ownerId)` → permite que UN usuario pertenezca a VARIOS dueños. Es la fuente de contexto multi-owner para `TENANT`, `PROPERTY_ADMIN`, `ACCOUNTANT`, `REAL_ESTATE_AGENT` y `MAINTENANCE_PROVIDER`.
- `TenantProfileEntity (userId, ownerId, propertyId)` → el "expediente". Un mismo `userId` puede tener:
  - varios expedientes con el mismo `ownerId` (mismo dueño, 2+ propiedades rentando).
  - varios expedientes con `ownerId` distintos (varios dueños al mismo tiempo).
- Regla ya codificada en `TenantService.createTenant`: un `Lease ACTIVE` por `propertyId`.

**Invariantes que NO se vuelven a tocar:**

1. Una sola cuenta `UserEntity` por email, no importa cuántos owners toque.
2. `OwnerMembership` es la única fuente de "a qué dueños pertenece este usuario".
3. Un `TenantProfile` = un expediente = `(userId, ownerId, propertyId)`.
4. `UserEntity.ownerId` queda solo como puntero de compatibilidad: se nulifica si hay más de uno (ya lo hace `syncTenantUserOwnerPointer`). El contexto real vive en el JWT (`TenantContext`).
5. Un solo `Lease` con `status = ACTIVE` por `propertyId`.
6. Crear expediente = ocupa inmueble. Archivar expediente = libera inmueble + abre vacancia.

Si alguna pantalla o endpoint contradice esto, el que se ajusta es la pantalla/endpoint, **no** el modelo.

---

## Reglas rectoras del producto (no se negocian)

- El sistema opera 100% con la app. `n8n` y canales externos solo **amplifican**, nunca contienen lógica de negocio ni aprueban nada.
- Toda notificación externa tiene equivalente **interno** en inbox.
- Toda notificación externa requiere que el usuario la **active** explícitamente en sus preferencias.
- `EMAIL` lo envía el backend. `WHATSAPP` usa adapter vía n8n. `N8N` no es canal visible para usuario.
- Toda aprobación sensible (cotizaciones, pagos, borrado) ocurre dentro de la app con MFA + reauth cuando aplique.
- `Property` es el activo arrendable. No existe módulo funcional de `Unit/Unidades` en el producto.
- El expediente es el centro de trabajo operativo, no el "contrato".
- Dueño, admin con permiso y contador ven **la misma historia** para el mismo owner y periodo.

---

# Etapa 1 — Identidad, contexto multi-dueño y SUPER_ADMIN (cerrar)

**Objetivo:** dejar intocable la base de auth + contexto + permisos para el resto del plan. Ya tienes MFA y JWT RS256.

## 1.1 Auth y contexto (confirmar y endurecer)
- JWT RS256 con access corto y refresh rotatorio con detección de reuse por `jti`. *(ya existe `RefreshTokenSessionEntity` y `RefreshTokenRevocationService`)*.
- MFA obligatorio para `SUPER_ADMIN`, `OWNER`, `PROPERTY_ADMIN`, `ACCOUNTANT`. Opcional para `TENANT`, `REAL_ESTATE_AGENT`, `MAINTENANCE_PROVIDER`.
- Reauth + MFA para: purga de owner, borrado de inmueble aprobado por owner, cambios de permisos sensibles, exportaciones críticas.
- Flujo estándar:
  1. login primer factor.
  2. si aplica MFA, challenge + verify.
  3. token `BASE` (sin `ownerId`).
  4. `GET /auth/contexts` → devuelve la lista de owners a los que pertenece el usuario vía `OwnerMembership`.
  5. `POST /auth/select-context` con `ownerId`.
  6. token `FULL` con `ownerId` activo.

## 1.2 Contextos por rol
- `SUPER_ADMIN`: sin contexto owner. Endpoints globales.
- `OWNER`: entra directo a su `ownerId` (self-membership o `User.ownerId` legado coincide).
- `PROPERTY_ADMIN`, `ACCOUNTANT`, `TENANT`, `REAL_ESTATE_AGENT`, `MAINTENANCE_PROVIDER`: selector si `OwnerMembership.count > 1`, auto-selección si == 1, bloqueado si == 0.

## 1.3 Permisos de PROPERTY_ADMIN (plantillas)
Permisos mínimos (entidad `PermissionGrantEntity` ya existe):
- `QUOTE_APPROVE`, `QUOTE_REJECT`
- `EXPENSE_PAY`, `EXPENSE_SETTLEMENT_CONFIRM`
- `VACANCY_ROUTE`
- `TEAM_MANAGE`
- `PROPERTY_ARCHIVE_TENANT`
- `REPORT_EXPORT`

Por default, `PROPERTY_ADMIN` no autoriza nada sensible. Si tiene el permiso, actúa en nombre operativo del owner.

## 1.4 SUPER_ADMIN (cierre final)
Ya lo tienes casi listo. Lo que falta confirmar:
- CRUD de `OWNER` con recovery administrativa.
- Purga total de `OWNER` (borra portafolio, expedientes, memberships, pagos, etc.) con evento `OWNER_PURGED` a n8n.
- Catálogo de `MAINTENANCE_PROVIDER` de plataforma y `REAL_ESTATE_AGENT` de plataforma.
- Al crear un `OWNER`, seed automático en `PlatformProviderAssignmentEntity` con todos los proveedores/agentes de plataforma activos.
- Configuración global: Mercado Pago, Banxico/CEP, n8n, storage, parámetros.
- Soporte para configuración cifrada por owner de cuentas bancarias receptoras y perfil propio de Mercado Pago.
- Eventos **prohibidos** hacia n8n: password reset, MFA reset, full recovery, cualquier secreto/token.

## 1.5 Entregables Etapa 1
- `GET /auth/contexts`, `POST /auth/select-context` probados con tenant multi-owner.
- `PropertyDeleteRequest` de `PROPERTY_ADMIN` + aprobación `OWNER` con reauth+MFA.
- Seed de proveedores/agentes de plataforma al crear owner.
- Hardening de cifrado en reposo (`EncryptionService` activo con `ENCRYPTION_KEY`, falla dura si falta en no-dev).

## 1.6 Pruebas Etapa 1
- Tenant con 2 owners: login → base → `/auth/contexts` devuelve los 2 → select-context → full token con solo ese owner.
- Tenant con un solo owner: select-context automático.
- Borrado de inmueble por `PROPERTY_ADMIN` sin permiso → crea request. Owner aprueba con MFA → borra.
- Recovery de MFA del owner por `SUPER_ADMIN` auditado y sin pasar por n8n.

---

# Etapa 2 — Expediente inmueble-arrendatario (cerrar la operación multi-expediente)

**Objetivo:** cerrar el flujo alta → edición → archivo → vacancia sobre `Property`, asumiendo multi-expediente por tenant y por owner. El modelo ya está, falta cerrar reglas y UI.

## 2.1 Dominio
- `Property` identificado por dirección completa obligatoria:
  `calle, númeroExterior, númeroInteriorOpcional, colonia, ciudad, estado, cp, referenciaOpcional`.
- `Property.status`: `OCCUPIED | AVAILABLE | MAINTENANCE | DELETED`, gobernado por expediente activo + mantenimiento.
- `Lease.status = ACTIVE` único por `propertyId` (regla ya en `LeaseRepository.existsByOwnerIdAndProperty_IdAndStatus`).
- No existe módulo funcional de unidades. La UI oculta "Unidades" y "Contratos" como secciones principales.

## 2.2 Alta integral (ya existe, cerrar edge cases)
`POST /api/tenants` con `TenantDTO` + `contractPdf` opcional. En una transacción:
1. Si `email` existe con rol `TENANT` → reutilizar cuenta. Si existe con otro rol → error de negocio.
2. Si el `propertyId` ya tiene `Lease ACTIVE` → error "ya ocupado".
3. Si no existe la cuenta → crear `UserEntity TENANT` con password temporal, `mustChangePassword=true`.
4. `ensureOwnerMembership(userId, ownerId)` → crea `OwnerMembership` si no existía.
5. Crear `TenantProfileEntity (userId, ownerId, propertyId, rentAmount, paymentDay, lateFee*)`.
6. `syncTenantUserOwnerPointer(user)` → si solo tiene 1 owner lo pone en `User.ownerId`, si tiene 2+ lo nulifica (contexto vive en JWT).
7. `leaseService.createLeaseForProperty(...)` → `Lease ACTIVE` con contrato PDF opcional.
8. `propertyMovementService.record(..., TENANT_EXPEDIENTE_OPENED, ...)`.
9. `vacancyService.closeOpenVacanciesForPropertyOnNewExpediente(...)`.
10. `dispatcher.dispatch("TENANT_CREATED", ...)` → se encola notificación interna + canales activos.

## 2.3 Edición (endurecer `TenantService.updateTenant`)
- `PUT /api/tenants/{id}` NO puede cambiar `propertyId`. Ya tienes `assertPropertyIdUnchangedOnEdit`.
- Cambios permitidos: nombre, teléfono, rentAmount, paymentDay, late fee config.
- Mover tenant de inmueble no existe como edición: es archivo + alta nueva.

## 2.4 Archivo operativo (`TenantExpedienteArchiveService`)
`POST /api/tenants/{tenantProfileId}/archive` con payload:
```
{ "vacancyRoutingMode": "PRIVATE | PLATFORM", "reason": "opcional" }
```
Efectos transaccionales:
- `tenantProfile.archivedAt = now`.
- `lease.status = TERMINATED`.
- `property.status = AVAILABLE`.
- Crea `VacancyEntity` con `routingMode`.
- Crea `ActionTask` para owner/admin: "Vacancia abierta en `<direccion>`".
- `PropertyMovementEventType.TENANT_EXPEDIENTE_ARCHIVED` + `VACANCY_OPENED`.
- `syncTenantUserOwnerPointer(tenantUserId)` → si ese era su último expediente activo con ese owner y no tiene `OwnerMembership` explícito ajeno al expediente, se revoca el acceso a ese owner.
- Si pierde todos los owners activos → cuenta queda inactiva (no se borra, solo `active=false` o bloqueo de login hasta nuevo expediente).
- Cancela la `Invoice` **abierta** del periodo actual (deja de contar como ingreso esperado). Las ya pagadas o cobradas quedan en histórico.
- Despacha `VACANCY_CREATED` a n8n si el owner lo tiene configurado.

Si eligió `PRIVATE` y no hay agente privado activo → bloquea la baja y ofrece registrar agente privado o cambiar a `PLATFORM`.

## 2.5 Contratos dentro del expediente
- `POST /api/tenants/{tenantProfileId}/contract-document` sube/reemplaza PDF.
- `GET /api/tenants/{tenantProfileId}/contracts` lista histórico.
- `LeaseFileEntity` guarda los históricos.
- UI: el detalle del expediente tiene pestaña "Contrato" con descarga y reemplazo. No hay pestaña principal "Contratos".

## 2.6 Entregables Etapa 2
- `POST/PUT /api/tenants` cerrados con invariantes probados.
- `POST /api/tenants/{id}/archive` completo con cancelación de factura abierta.
- UI de detalle de expediente con: datos personales, inmueble, renta, contrato, historial, convenios, estado contable básico.
- Panel tenant: selector de owner → lista de sus expedientes en ese owner → detalle.

## 2.7 Pruebas Etapa 2
- Tenant con 2 expedientes activos en el mismo owner (dos propiedades): login, select-context, ve ambos expedientes.
- Tenant con expedientes en 2 owners distintos: ve cada grupo al cambiar contexto.
- Alta con email que ya es `ADMIN` → 400.
- Alta sobre inmueble ya ocupado → 400.
- Archivo: libera inmueble, cierra lease, abre vacancia, cancela factura abierta, crea ActionTask.
- Archivo del último expediente con ese owner: pierde membership si no tenía otra razón para estar.

---

# Etapa 3 — Cobranza automatizada (Mercado Pago + SPEI/CEP + Convenios)

**Objetivo:** que la app cobre sola. Una vez que el tenant paga, todo se refleja automáticamente. SPEI con validación CEP real. Convenios enganchados con reportes.

## 3.1 Ingresos esperados automáticos
Un cron mensual (Spring `@Scheduled` o tabla de periodos con `ReportingPeriodService`) corre el día 1 de cada mes y, por cada `TenantProfile` **no archivado**:
- Genera `InvoiceEntity` del periodo (`monthYear`, `amount = rentAmount`, `dueDate = periodStart + paymentDay`, `status = OPEN`).
- Dispara `INVOICE_CREATED` interno.

Regla: si el expediente se **archiva** después, esa invoice **abierta** pasa a `CANCELLED` y deja de contar como ingreso esperado. Lo ya cobrado queda histórico.

## 3.2 Mercado Pago
- Cada `OWNER` cobra con su propio perfil de Mercado Pago; no existe una configuración única para todos.
- `TENANT` paga desde su portal con `/integrations/mercadopago/checkout` usando la configuración del owner activo.
- Webhook MP firmado y validado por owner → crea `PaymentEntity (method=MP, status=VALIDATED)` → aplica al Ledger → notificación `PAYMENT_VALIDATED`.
- Todo pago con tarjeta validado por webhook queda marcado automáticamente como correcto con origen `MP_WEBHOOK`.

## 3.3 SPEI manual + CEP real (reemplazar mock)
Flujo:
1. Tenant sube comprobante → `TransferProofSubmission (status=PENDING_VALIDATION)`.
2. Sistema usa IA/OCR/visión para extraer monto, fecha, referencia, cuenta destino y clave de rastreo desde imagen/PDF.
3. Si la imagen no es comprobante o no contiene datos suficientes, el sistema pide al tenant que mande los datos correctamente escritos para poder validar el SPEI.
4. Si el comprobante entra por `WHATSAPP`, n8n manda a backend `whatsappMessageId`, adjuntos, texto y teléfono normalizado; backend resuelve identidad por `contactPhone` en `E.164` y solo automatiza si hay match único y autorizado.
5. `BanxicoCepAdapter.validate(...)` → `CepValidationAttempt`. Estados:
   - datos completos y coinciden → `VALIDATED` → crea `Payment` → aplica a Ledger.
   - faltan datos → pide al tenant los faltantes exactos y reintenta hasta **3 veces**.
   - no coincide → `REJECTED` con motivo.
   - imagen incorrecta / no comprobante → `PENDING_DATA`.
   - identidad por teléfono ambigua o no autorizada → `MANUAL_REVIEW`.
6. Si después del tercer intento no se puede validar, se crea alerta al owner/admin con `PAYMENT_REVIEW` indicando arrendatario e inmueble para confirmar si el dinero sí llegó a su cuenta.
7. Solo con esa confirmación explícita se registra en contabilidad con origen `MANUAL_OVERRIDE` y debe reflejarse en el resumen mensual del inmueble.
8. El owner/admin con permiso ve estado, reintentos, motivos y quién confirmó.
9. Si el CEP valida, el sistema descarga y preserva XML, PDF/acuse y payload oficial disponible.
10. Los artefactos quedan ligados al pago, a la factura y al expediente mensual del inmueble.

### 3.3.1 Expediente mensual del inmueble
Cada inmueble debe mostrar por `monthYear`:
- monto esperado;
- monto pagado;
- método;
- origen de verificación;
- evidencia descargable;
- actor o sistema que confirmó;
- estado final de conciliación.

En Etapa 3 se debe **reemplazar el mock** por el adapter real de CEP/Banxico. Hasta que eso pase, Etapa 3 no se considera cerrada.

## 3.4 Pago parcial: razón obligatoria
Cuando un pago confirmado deja saldo abierto, el tenant debe responder en el portal:
- `shortfallReason ∈ { PARTIAL_SAME_MONTH, PARTIAL_NEXT_MONTH, REQUESTING_AGREEMENT, BANK_ISSUE, OTHER }`.
- `shortfallDescription?`, `promisedCompletionDate?`.

Si no responde → `ActionTask` para tenant.

Reglas:
- `PARTIAL_SAME_MONTH`: saldo abierto; si no paga antes de `dueDate + gracePeriodDays`, aplica `lateFee` si está configurada en `TenantProfile`.
- `PARTIAL_NEXT_MONTH`: se redirige a solicitud de convenio.
- Con convenio aprobado activo: el diferido no genera mora hasta la fecha de sus parcialidades.

## 3.5 Convenios (`PaymentAgreementEntity`)
- Tenant solicita convenio sobre invoice o saldo pendiente. Evidencia opcional (PDF/imagen).
- Owner aprueba o rechaza en la app.
- Estados: `REQUESTED | APPROVED | REJECTED | ACTIVE | COMPLETED | BREACHED | CANCELLED`.
- `AgreementInstallmentEntity` con fechas y montos. Estados: `PENDING | PAID | LATE`.
- Cron diario de incumplimiento: si vence una parcialidad sin pagar → `installment.LATE` → `agreement.BREACHED` → `PropertyMovement AGREEMENT_BREACHED` + `ActionTask` + notificación.

## 3.6 Ledger único (`LedgerService`)
Fuente de verdad para ingresos. Owner, admin con permiso y contador leen el mismo ledger por owner y periodo.

## 3.7 Reportería contable (Etapa 3 inicia, cierra en Etapa 5)
`OwnerMonthlyAccountingSummaryDTO` con:
- `expectedIncome`, `collectedIncome`, `outstandingIncome`, `overpaidCredits`
- `lateFeeAccrued`
- `activeAgreementsCount`, `breachedAgreementsCount`
- `delinquentTenantsCount`

## 3.8 Entregables Etapa 3
- Cron mensual de emisión de invoices.
- Webhook Mercado Pago aplicando a ledger.
- CEP real integrado (reemplaza mock).
- Artefactos CEP preservados en expediente mensual del inmueble.
- Shortfall reason obligatorio en pagos parciales.
- Convenios completos con evidencia + cron de incumplimiento.
- Cancelación de invoice abierta al archivar expediente.

## 3.9 Pruebas Etapa 3
- Día 1 del mes: se generan invoices para todos los expedientes activos.
- Pago MP exacto, parcial, excedente.
- SPEI con CEP validado, incompleto (pide datos), rechazado.
- Convenio solicitado, aprobado, parcialidad cumplida, parcialidad incumplida → `BREACHED`.
- Pago parcial sin razón → ActionTask al tenant.
- Archivar expediente mid-mes → invoice abierta del mes → `CANCELLED`.

---

# Etapa 4 — Operación: Mantenimiento, Vacancia/Comercial y Settlement bilateral

**Objetivo:** que los flujos operativos del inmueble (mantenimiento y vacancia) se modelen completos, con pagos a proveedores/agentes con confirmación bilateral.

## 4.1 Routing preferente del owner
`OwnerPreferencesEntity` (o tabla similar):
- `maintenanceRoutingMode: PRIVATE | PLATFORM`
- `vacancyRoutingMode: PRIVATE | PLATFORM` (este se confirma en cada archive, pero hay default).
- No hay fallback silencioso: si eligió PRIVATE y no hay proveedor/agente privado activo, el sistema bloquea y pide decidir.

## 4.2 Mantenimiento
Entidades ya existentes: `MaintenanceTicketEntity`, `MaintenanceQuoteEntity`, `ExpenseEntity (sourceType=MAINTENANCE)`.

Flujo:
1. `TENANT` abre ticket desde portal (o se crea manualmente por owner/admin).
2. Sistema asigna/notifica al proveedor según `maintenanceRoutingMode`.
3. Proveedor acepta, visita, sube evidencia y cotización.
4. `OWNER` o `PROPERTY_ADMIN` con `QUOTE_APPROVE` aprueba o rechaza.
5. Aprobado → `ExpenseEntity` con `approvedAmount`, estado `APPROVED_PENDING_PAYMENT`.
6. Pago registrado → settlement bilateral (ver 4.4).

Estados del ticket: `OPEN | ASSIGNED | QUOTED | APPROVED | IN_PROGRESS | COMPLETED | REJECTED | CANCELLED`.

## 4.3 Vacancia y actividad comercial
- `VacancyEntity` se crea automáticamente al archivar expediente (ver Etapa 2).
- `RealEstateActivityEntity` registra visitas, fotos, notas del agente.
- `ExpenseEntity (sourceType=COMMERCIAL)` para comisión del agente.
- Cierre de vacancia = creación de nuevo expediente sobre el mismo `propertyId`.

Estados: `VACANCY_OPEN | LISTING_ACTIVE | VISIT_RECORDED | COMMISSION_QUOTED | COMMISSION_APPROVED | COMMISSION_REJECTED | COMMISSION_PAID | VACANCY_CLOSED`.

El agente puede subir fotos etiquetadas (`BASELINE`, `BEFORE`, `AFTER`, `VISIT`) desde su portal con contexto operativo (`uploadedBy`, `uploaderRole`, `label`, `note`).

## 4.4 Settlement bilateral de egresos (obligatorio para mantenimiento y comercial)
`ExpenseEntity` debe incluir la capa de settlement:
- `approvedAmount`, `paidAmount`, `outstandingAmount`
- `paymentSettlementStatus: UNPAID | PARTIALLY_PAID | PAID_IN_FULL | DISPUTED`
- `paymentMethod: SPEI | CASH | OTHER`
- `ownerConfirmationStatus: PENDING | CONFIRMED`
- `providerConfirmationStatus: PENDING | CONFIRMED | DISPUTED`

Endpoints:
- `POST /api/expenses/{id}/payments` → owner/admin registra un pago (parcial o completo).
- `POST /api/expenses/{id}/confirmations/owner` → owner confirma qué pagó.
- `POST /api/expenses/{id}/confirmations/provider` → proveedor/agente confirma qué recibió (completo/parcial/diferencia).
- `GET /api/expenses/{id}/settlement` → estado completo.

Reglas de resolución:
- Ambos CONFIRMED coinciden `paid == approved` → `PAID_IN_FULL`.
- Ambos CONFIRMED coinciden `paid < approved` → `PARTIALLY_PAID` con `outstanding`.
- Desacuerdo → `DISPUTED` + `ActionTask` para owner/admin/accountant. n8n puede notificar; no resuelve.

## 4.5 Equipo y proveedores (UI unificada)
Sección "Equipo y proveedores" con 3 bloques: administradores, mantenimiento, agentes. En mantenimiento y agentes cada fila muestra `origen: PLATFORM | PRIVATE`, estado, contacto, acciones vincular/desvincular.

## 4.6 Entregables Etapa 4
- `OwnerPreferences` con routing persistente.
- Mantenimiento end-to-end con quote + expense + settlement bilateral.
- Vacancia automática al archivar + actividad comercial + comisión.
- Bloqueo cuando `PRIVATE` sin proveedor/agente privado activo.
- `ActionTask` automáticas en cada punto de decisión.

## 4.7 Pruebas Etapa 4
- Routing `PRIVATE` sin privado: bloquea y ofrece alternativas.
- Ticket mantenimiento → cotización → aprobación → pago parcial → proveedor confirma parcial → `PARTIALLY_PAID`.
- Ticket → pago registrado por owner como completo → proveedor dice que recibió menos → `DISPUTED` + ActionTask.
- Vacancia: fotos, visita, comisión aprobada, pago, confirmación bilateral, cierre con nuevo expediente.

---

# Etapa 5 — Notificaciones automáticas, reportes, n8n opcional y salida a operación

**Objetivo:** cerrar el sistema de notificaciones por usuario (con activación propia), reportes mensuales/anuales automáticos, wiring de n8n opcional, hardening y QA final.

## 5.1 Centro de preferencias de notificación (por usuario, evento y canal)
Ya tienes `NotificationPreferenceEntity`. Falta:
- Tabla de decisiones por `(userId, eventType, channel)` con `enabled: boolean`.
- Canales: `IN_APP` (siempre on, no configurable), `EMAIL` (enviado por backend), `WHATSAPP` (vía adapter n8n).
- Eventos relevantes por rol:

| Evento | OWNER | PROPERTY_ADMIN | ACCOUNTANT | TENANT | AGENT | PROVIDER |
|---|---|---|---|---|---|---|
| `PAYMENT_VALIDATED` (transferencia/MP) | ✅ | ✅ | ✅ | ✅ | — | — |
| `MONTHLY_SUMMARY_READY` | ✅ | ✅ | ✅ | — | — | — |
| `INVOICE_CREATED` | — | — | — | ✅ | — | — |
| `INVOICE_DUE_SOON` / `INVOICE_OVERDUE` | ✅ | ✅ | — | ✅ | — | — |
| `AGREEMENT_REQUESTED` | ✅ | ✅ | — | — | — | — |
| `AGREEMENT_APPROVED/REJECTED` | — | — | — | ✅ | — | — |
| `AGREEMENT_BREACHED` | ✅ | ✅ | ✅ | ✅ | — | — |
| `MAINTENANCE_TICKET_CREATED` | ✅ | ✅ | — | ✅ | — | ✅ |
| `MAINTENANCE_QUOTE_SUBMITTED` | ✅ | ✅ | — | — | — | — |
| `EXPENSE_SETTLEMENT_DISPUTED` | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| `VACANCY_OPENED/CLOSED` | ✅ | ✅ | — | — | ✅ | — |
| `PROPERTY_DELETE_REQUESTED` | ✅ | — | — | — | — | — |

`OWNER_CREATED` es bootstrap interno/auditoría y no aparece en preferencias configurables.

Endpoints:
- `GET /api/notifications/preferences` → devuelve la matriz del usuario actual.
- `PUT /api/notifications/preferences` → actualiza switches.
- `GET /api/notifications/inbox` → lista paginada.
- `POST /api/notifications/{id}/read`.

**Regla:** `IN_APP` siempre se crea. `EMAIL` y `WHATSAPP` solo si el usuario tiene el switch en ON para ese evento y canal. Default razonable al crear cuenta: `EMAIL` en ON para eventos críticos, `WHATSAPP` en OFF.

## 5.2 Resumen mensual automático
Cron mensual (por ejemplo día 1 a las 6am) por owner activo:
1. `OwnerAccountingSummaryService.generateForMonth(ownerId, period)` genera `OwnerMonthlyAccountingSummaryDTO` y lo persiste.
2. `PropertyReportService.generateForMonth(ownerId, propertyId, period)` genera `PropertyMonthlySummaryDTO` por inmueble.
3. Se despacha `MONTHLY_SUMMARY_READY` a owner + property admins con `REPORT_EXPORT` + contadores del owner, respetando sus preferencias.
4. Si el canal externo está activo, el backend envía `EMAIL` y el adapter de `WHATSAPP` vía n8n entrega el mensaje con link firmado al resumen.

Vista: `/api/reports/monthly/{ownerId}/{period}` con export JSON/Excel/PDF.

## 5.3 Reporte anual (`PropertyAnnualSummaryDTO`)
Agregación de 12 meses por inmueble y por owner. Disponible desde `/api/reports/annual/...`.

## 5.4 Vistas de dueño, admin y contador
Widgets del dashboard owner/admin:
- Ingresos esperados/cobrados/pendientes del mes.
- Egresos aprobados/pagados/pendientes.
- Propiedades con incidencias (mora, ticket abierto, vacancia, convenio roto).
- Arrendatarios en mora, convenios activos/incumplidos, vacancias abiertas, tickets abiertos.

Contador: libro mayor, pagos con aplicado/no aplicado, convenios y diferidos, settlement bilateral, disputas, export mensual/anual.

Regla de consistencia: dueño, admin con `REPORT_EXPORT` y contador ven **la misma historia** para el mismo `ownerId` y periodo.

## 5.5 Adaptadores de salida (`EMAIL` + `WHATSAPP`)
`EmailNotificationAdapter` y `N8nWhatsAppAdapter` deben quedar alineados. Reglas:
- El backend envía `EMAIL` y decide la preferencia/autorización antes de cualquier envío.
- n8n recibe solo solicitudes de salida para `WHATSAPP` por `ownerId`, `providerId`, `tenantId`, `ticketId`. Nunca por email.
- Todos los eventos son **no bloqueantes**. Si n8n falla, la operación local sigue.
- Se audita cada envío como `SENT | SKIPPED | FAILED`.
- Prohibido enviar: password reset, MFA reset, full recovery, secretos, tokens.

Eventos que salen a `WHATSAPP`: `OWNER_CONTACT_UPDATED`, `OWNER_PURGED`, `PAYMENT_VALIDATED`, `MAINTENANCE_TICKET_CREATED`, `MAINTENANCE_TICKET_ASSIGNED`, `VACANCY_CREATED`, `VACATE_REQUEST_CREATED`, `MONTHLY_SUMMARY_READY`, `PROPERTY_DELETE_REQUESTED`, `AGREEMENT_BREACHED`, `EXPENSE_SETTLEMENT_DISPUTED`.

`OWNER_CREATED` queda interno para onboarding/auditoría y no forma parte del catálogo configurable ni del adapter de WhatsApp.

## 5.6 Hardening y QA final
- `EncryptionService` obligatorio en no-dev. Falla dura si falta `ENCRYPTION_KEY`.
- Rate limiting por endpoint crítico.
- Audit log en toda escritura relevante (`AuditEventEntity` ya existe).
- RLS por `owner_id` en queries sensibles.
- CSP, CORS, headers correctos.
- Content-Type estricto.
- Matriz de QA por bloque con evidencia real:
  - seguridad y selector de contexto multi-owner.
  - alta/edición/archivo de expediente multi-owner/multi-property.
  - cobranza MP + SPEI/CEP real + convenios + shortfall.
  - mantenimiento + settlement bilateral.
  - vacancia + comercial + settlement.
  - notificaciones por usuario/evento/canal.
  - reportes mensuales/anuales consistentes entre owner/admin/accountant.
  - n8n apagado: operación local sigue. n8n fallando: sin bloqueo.

## 5.7 Entregables Etapa 5
- Endpoints de preferencias de notificación y wiring completo a todos los `dispatch(...)` existentes.
- Cron mensual de `OwnerMonthlyAccountingSummary` y `PropertyMonthlySummary` que dispara `MONTHLY_SUMMARY_READY`.
- Reporte anual.
- Dashboards coherentes para los 3 roles financieros.
- Matriz QA con evidencia.
- Flags: `n8n.enabled`, `email.enabled`, `whatsapp.enabled`, `ia.enabled` (solo copiloto, nunca motor).

## 5.8 Pruebas Etapa 5
- Tenant activa email para `PAYMENT_VALIDATED`: sube SPEI, se valida, recibe email + inbox.
- Tenant desactiva email: solo recibe inbox.
- Contador con email ON para `MONTHLY_SUMMARY_READY`: día 1 del mes recibe link a resumen.
- Owner con dos propiedades y dos tenants: dashboard refleja ambos; admin con permiso ve lo mismo; contador ve lo mismo.
- n8n apagado: owner crea expediente, sube SPEI, valida CEP, todo se refleja sin fallos.
- n8n caído: reintento automático y `FAILED` auditado; la operación local continúa.
- SPEI por `WHATSAPP` con datos incompletos: backend pide faltantes, reintenta 3 veces y al tercer fallo alerta al owner para confirmación manual.

---

# Checklist ejecutivo (orden sugerido, día por día)

### Esta semana — cerrar Etapa 1 + 2
1. Confirmar `GET /auth/contexts` + `POST /auth/select-context` con pruebas reales multi-owner.
2. Cerrar SUPER_ADMIN (purga, seed de providers/agents de plataforma, recovery).
3. Cerrar `POST /api/tenants/{id}/archive` con cancelación de factura abierta y revocación de membership cuando aplique.
4. Endurecer `EncryptionService` en no-dev.
5. UI del expediente: tabs Datos / Inmueble / Contrato / Convenios / Cobranza / Mantenimiento.

### Semana siguiente — Etapa 3
6. Cron mensual de invoices.
7. Reemplazar mock CEP por adapter real.
8. Shortfall reason en pagos parciales.
9. Cron diario de incumplimiento de convenios.
10. Evidencia de convenio (upload).

### Semana 3 — Etapa 4
11. `OwnerPreferences` con routing persistente.
12. Settlement bilateral en `ExpenseEntity` + endpoints.
13. Vacancia automática al archivar + cierre con nuevo expediente.
14. Bloqueo `PRIVATE` sin privado.

### Semana 4 — Etapa 5
15. `NotificationPreferenceEntity` completo end-to-end: endpoints, UI, wiring a dispatcher.
16. Cron mensual de `OwnerMonthlyAccountingSummary` + `MONTHLY_SUMMARY_READY`.
17. Reporte anual + exportaciones.
18. QA final con evidencia.
19. Flags de entorno y salida a operación.

---

# APIs finales (consolidado)

Manteniendo y ajustando lo ya existente:
- `/auth/*` + `/auth/contexts` + `/auth/select-context`
- `/owners/*`, `/permissions/*`, `/properties/*` + `/properties/delete-requests/*`
- `/tenants/*` + `/tenants/{id}/archive` + `/tenants/{id}/contracts` + `/tenants/{id}/contract-document`
- `/leases/*`
- `/payments/*` + `/payments/proofs/*` + `/payments/cep-validation/*` + `/payments/{invoiceId}/shortfall-reason`
- `/agreements/*` + `/agreements/{id}/evidence`
- `/maintenance/tickets|quotes|payments`
- `/vacancies/*` + `/vacancies/{id}/route`
- `/expenses/{id}/payments` + `/expenses/{id}/confirmations/owner|provider` + `/expenses/{id}/settlement`
- `/notifications/*` + `/notifications/preferences`
- `/tasks/*` (ActionTask)
- `/owner/team/*` + `/owner/preferences/*` (routing + notifications defaults)
- `/reports/monthly/*` + `/reports/annual/*`
- `/files/*`
- `/integrations/mercadopago/*` + `/integrations/n8n/events`

---

# Suposiciones y defaults (fijos)

- Una sola cuenta `UserEntity` por email.
- Muchos expedientes por tenant, múltiples dueños posibles vía `OwnerMembership`.
- Un solo `Lease ACTIVE` por inmueble.
- "Eliminar tenant" = archivar expediente. No borra historial.
- Revocación de acceso del tenant es **por owner**. Si pierde todos, pierde acceso total (cuenta inactiva).
- Ingreso esperado se cancela al archivar si la factura del periodo sigue abierta.
- Toda aprobación sensible ocurre dentro de la app.
- `n8n` solo notifica y acompaña; nunca reemplaza la lógica principal.
- IA (si se usa) es copiloto opcional para resumen narrativo y clasificación. No cambia estados financieros.
- MFA obligatorio para `SUPER_ADMIN`, `OWNER`, `PROPERTY_ADMIN`, `ACCOUNTANT`. Opcional para el resto.

---

# Criterios de cierre por etapa (done-done)

- **Etapa 1 done-done:** tenant multi-owner puede loguear, listar contextos, cambiar contexto, y solo ve datos del owner activo. SUPER_ADMIN crea/purga owner y provee providers/agents de plataforma con seed automático. MFA operando.
- **Etapa 2 done-done:** un mismo tenant puede tener 2 expedientes activos en 2 propiedades del mismo owner, y un 3º con otro owner. Archivo cancela invoice abierta, abre vacancia, revoca membership cuando corresponde.
- **Etapa 3 done-done:** sistema genera invoices automáticas, procesa MP por owner, valida SPEI con CEP **real**, preserva artefactos CEP en expediente mensual del inmueble, registra shortfall reason, reconcilia efectivo contra estado de cuenta y ejecuta convenios con evidencia y cierre automático de incumplidos.
- **Etapa 4 done-done:** ticket de mantenimiento termina con settlement bilateral auditado. Archivo abre vacancia con routing efectivo, agente opera, comisión se paga con settlement bilateral.
- **Etapa 5 done-done:** cada usuario configura sus notificaciones por evento/canal. Cron mensual dispara resumen y lo manda solo a quien lo activó. Dueño/admin/contador ven la misma historia para el mismo owner y periodo. n8n es opcional y su caída no bloquea nada.
