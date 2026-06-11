# ADMINDI — Plan Maestro Final (5 Etapas)

> **Documento de referencia único.** Consolida todos los planes previos (`PLAN.md`, `PLAN_me_gusta.md`, `PLAN_consolidado_.md`, `PLAN_mejorado_1.md`, `PLAN_mejorado_mejorado_1.md`, `ADMINDI_CONTEXTO_MAESTRO_OPUS.md`, `PLAN_FINAL_5_ETAPAS.md`) en una sola fuente de verdad. Si algo no está aquí, no se construye. Si algo está aquí y en otro documento se contradice, gana este.

---

## Visión

Plataforma inmobiliaria multi-owner, multi-contexto, **100% autónoma**. La app opera completa por sí sola; `IN_APP` siempre está activo, `EMAIL` lo entrega el backend y `WHATSAPP` usa un adapter opcional de salida. Cada usuario controla sus canales externos de notificación. Las aprobaciones sensibles siempre ocurren dentro de la app.

## Principios rectores

1. **Seguridad primero.** Cada endpoint tiene validación de token, permisos y RLS. Nada de atajos.
2. **Automatización máxima.** Si el sistema puede hacerlo solo, lo hace solo.
3. **n8n opcional, nunca crítico.** La app funciona con o sin n8n.
4. **Notificación interna siempre.** Todo evento importante genera `Notification`; los canales externos son preferencia por usuario y por evento.
5. **Contrato único: evento de dominio.** La lógica vive en la app; n8n solo es un adapter de salida.
6. **El inmueble es el activo.** `Property` es el centro; no existe módulo funcional de `Unit/Unidades` en el producto.
7. **Una cuenta tenant por email.** Muchos expedientes por tenant, muchos owners por tenant.
8. **Aprobación dentro de la app.** n8n puede notificar, nunca aprobar.

## Stack

Java 21 + Spring Boot 3 + Spring Security 6 · React 18 + TypeScript + Vite · PostgreSQL 16 con Row Level Security · Redis 7 · Cloudflare R2 · WhatsApp Cloud API · n8n (opcional) · Mercado Pago Checkout Pro · Banxico/CEP · GitHub Actions CI/CD

## Roles y MFA

| Rol | MFA | Multi-contexto | Resumen |
|-----|-----|----------------|---------|
| SUPER_ADMIN | Obligatorio | No (global, bypass RLS) | Plataforma, dueños, proveedores globales |
| OWNER | Obligatorio | No (su owner) | Portafolio, equipo, aprobaciones |
| PROPERTY_ADMIN | Obligatorio | Sí | Opera bajo permisos finos del dueño |
| ACCOUNTANT | Obligatorio | Sí | Lectura financiera y exportación |
| TENANT | Recomendado | Sí | Muchos owners, muchos inmuebles por owner |
| REAL_ESTATE_AGENT | Recomendado | Sí | Vacancias y actividad comercial |
| MAINTENANCE_PROVIDER | Recomendado | Sí | Tickets y mantenimiento |

## Modelo clave: tenant multi-owner / multi-inmueble

```
UserEntity (TENANT, 1 por email)
  └── OwnerMembership (1 por cada owner con el que renta)
        └── TenantProfile (1 por cada inmueble con ese owner)
              └── Lease ACTIVE (1 por profile, 1 por inmueble)
```

- Un inmueble solo puede tener **un** lease ACTIVE.
- Un tenant puede tener **muchos** profiles con el mismo owner (distintos inmuebles).
- Un tenant puede tener **muchos** memberships (distintos owners).
- El tenant elige owner como contexto; dentro del owner ve todos sus expedientes de ese dueño.
- Si el email ya existe como TENANT → se reutiliza sin reset.
- Si el email ya existe con otro rol → falla con error de negocio.

## Flujo de autenticación único (aplica a todos los roles)

```
1. POST /auth/login           → email + password + MFA
2. Sistema consulta contextos activos
   - 0 contextos            → error "Sin acceso"
   - 1 contexto             → token FULL directo
   - 2+ contextos           → token BASE + lista de contextos
3. POST /auth/select-context  → ownerId elegido → token FULL
4. POST /auth/switch-context  → cambio sin re-login
5. POST /auth/refresh         → rotación con detección de reuse
```

JWT RS256, access 15 min en memoria, refresh 7 días en cookie HttpOnly con rotación. Token BASE solo puede acceder a `/auth/contexts` y `/auth/select-context`.

---

# ETAPA 1 — Seguridad, identidad, SUPER_ADMIN y notificaciones

**Estado: 80% cerrada.** MFA, JWT, login, refresh, revocación y SUPER_ADMIN base funcionan. Falta cerrar contextos, notificaciones y siembra.

## Lo que ya existe

- JWT RS256 con access + refresh rotatorio y revocación por jti en Redis.
- MFA TOTP obligatorio para roles críticos.
- Reautenticación + MFA para acciones destructivas.
- RLS en PostgreSQL con `owner_id` en toda tabla con datos de dueño.
- Rate limiting por endpoint con Bucket4j + Redis.
- Validación Jakarta Bean Validation en todos los DTOs.
- HMAC en webhooks (WhatsApp, Mercado Pago, n8n) con idempotencia Redis.
- Audit log de toda escritura.
- SUPER_ADMIN con dashboard global, CRUD de OWNER y recovery administrativo.
- Separación `loginEmail` vs `contactEmail/contactPhone`.
- `loginEmail` sirve solo para autenticación.
- `contactEmail` es el destinatario operativo del canal `EMAIL` de la plataforma.
- `contactPhone` es el identificador operativo para `WHATSAPP` inbound/outbound.

## Lo que falta para cerrar

### 1.1 Selector de contexto automático
- 1 contexto → token FULL directo (sin paso 2).
- 2+ contextos → token BASE + selector obligatorio.
- `POST /auth/switch-context` para cambio sin re-login.
- `GET /auth/contexts` devuelve lista legible: nombre del owner, rol, permisos resumidos.

### 1.2 OwnerMembership como fuente común
Un registro por cada relación usuario↔owner. Fuente de verdad del contexto para PROPERTY_ADMIN, ACCOUNTANT, TENANT, REAL_ESTATE_AGENT y MAINTENANCE_PROVIDER.

### 1.3 Purga total de OWNER
Elimina owner, inmuebles, expedientes, pagos, tickets, configuraciones, asociaciones y auditoría del owner. Requiere:
- Reautenticación del SUPER_ADMIN.
- MFA.
- Advertencia extrema.
- Confirmación explícita con texto.
- Emisión de `OWNER_PURGED` a n8n (opcional).
- Irreversible.

### 1.4 CRUD de MAINTENANCE_PROVIDER de plataforma
Desde SUPER_ADMIN. Identidad/contacto separados, credenciales temporales con `mustChangePassword=true`, estados activo/inactivo.

### 1.5 Siembra automática de proveedores
Al crear un OWNER, el sistema asocia automáticamente los proveedores de plataforma activos como **disponibles** para ese owner. No duplica cuentas. El owner decide activarlos, desactivarlos o añadir propios.

### 1.6 Centro de preferencias de notificación
Cada usuario configura por **evento × canal**:

**Canales visibles para usuario:**
- `IN_APP` (siempre activo, no se puede desactivar).
- `EMAIL` (opcional, enviado por el backend; solo salida, no recibe mensajes).
- `WHATSAPP` (opcional, entregado por adapter de WhatsApp vía n8n; único canal bidireccional).

**Eventos configurables:**
- Resumen mensual
- Pago validado
- Pago pendiente / vencido
- Comprobante SPEI pendiente o rechazado
- Ticket de mantenimiento nuevo / actualizado / asignado
- Vacancia creada
- Desocupación iniciada
- Convenio solicitado / aprobado / rechazado / incumplido
- Solicitud de borrado de inmueble
- Recordatorios operativos
- `OWNER_CREATED` es evento interno de bootstrap/auditoría; no aparece en la matriz configurable.

### 1.7 Infraestructura Notification + ActionTask
- `Notification`: informa. Todo evento importante la crea.
- `ActionTask`: requiere decisión. Todo evento que exige acción la crea además.
- Ambas son visibles en la bandeja del usuario y en dashboards por rol.
- `EmailNotificationAdapter`: salida de correo desde backend según preferencia `EMAIL`.
- `WhatsAppNotificationAdapter`: salida opcional vía n8n según preferencia `WHATSAPP`.
- El correo operativo siempre se envía al `contactEmail` del usuario; `loginEmail` no se usa como destino salvo migración controlada y explícita.

### 1.8 Contrato con adapters externos
- `EMAIL` lo resuelve el backend; no sale por n8n.
- n8n es adapter de salida para `WHATSAPP`, nunca lógica principal.
- `EMAIL` es exclusivamente outbound.
- `WHATSAPP` es el único canal externo inbound/outbound: tickets, quejas y comprobantes SPEI entran por webhook y backend decide identidad, autorización, validación y persistencia.
- La identidad de mensajes entrantes por WhatsApp se resuelve por `contactPhone` normalizado a `E.164`; si no hay match único y autorizado, no se automatiza y se manda a revisión.
- Eventos hacia WhatsApp/n8n: `OWNER_CONTACT_UPDATED`, `OWNER_PURGED`, `MAINTENANCE_PROVIDER_CREATED`, `PAYMENT_VALIDATED`, `MAINTENANCE_TICKET_CREATED`, `MAINTENANCE_TICKET_ASSIGNED`, `VACATE_REQUEST_CREATED`, `VACANCY_CREATED`, `MONTHLY_SUMMARY_READY`, `PROPERTY_DELETE_REQUESTED`.
- `OWNER_CREATED` queda como evento interno de onboarding/auditoría; no es evento configurable ni disparador de WhatsApp.
- **Prohibidos hacia n8n:** password reset, MFA reset, recovery, cambio de contraseña, tokens, secretos, OTPs.
- n8n identifica por IDs internos (`ownerId`, `tenantProfileId`, `ticketId`), **nunca por email**.
- Auditoría de integración: `SENT`, `SKIPPED`, `FAILED`.
- Si n8n falla, la operación local no se bloquea.

### 1.9 Configuración global en SUPER_ADMIN
Desde panel: Mercado Pago, Banxico/CEP adapter, adapter de WhatsApp (n8n URL + API key), R2, SMTP (`Gmail` como proveedor inicial), plantillas de email, plantillas de WhatsApp, plantillas de permisos, parámetros globales (días de gracia default, % recargo default).

Además, la plataforma debe soportar configuración cifrada por `OWNER` para:
- cuentas bancarias receptoras oficiales (CLABE/cuenta/titular/banco);
- perfil propio de cobro en Mercado Pago;
- secreto de webhook y metadatos de conciliación por owner.

## APIs de Etapa 1

```
POST   /auth/login
POST   /auth/mfa/challenge
POST   /auth/mfa/verify
POST   /auth/refresh
POST   /auth/logout
GET    /auth/contexts
POST   /auth/select-context
POST   /auth/switch-context
GET    /admin/dashboard
POST   /admin/owners
PUT    /admin/owners/{id}
DELETE /admin/owners/{id}/purge
POST   /admin/owners/{id}/recovery
POST   /admin/platform-providers
PUT    /admin/platform-providers/{id}
GET    /admin/users?role=
GET    /admin/audit
GET    /admin/settings
PUT    /admin/settings
GET    /notifications
PUT    /notifications/{id}/read
GET    /tasks
PUT    /tasks/{id}/resolve
GET    /preferences/notifications
PUT    /preferences/notifications
```

## Pantallas SUPER_ADMIN

- **Dashboard global:** dueños activos, inmuebles, ocupación, inquilinos, pagos del mes, tickets abiertos, vacancias y alertas del sistema.
- **Gestión de dueños:** CRUD, crear dispara onboarding, desactivar requiere reauth.
- **Proveedores de plataforma:** CRUD de maintenance y real_estate_agent con métricas de uso.
- **Auditoría global:** filtros por fecha, evento, actor, dueño, recurso.
- **Gestión de usuarios:** buscar, ver contextos, resetear contraseña, desactivar, historial de login.
- **Configuración del sistema:** WhatsApp, Mercado Pago, n8n, R2, SMTP, plantillas, parámetros globales.

## Pruebas Etapa 1

- Login con MFA por cada rol.
- Refresh rotation, revocación, detección de reuse.
- Selector: 1 contexto = FULL directo, 2+ = BASE → selección obligatoria.
- Switch-context sin re-login.
- CRUD y purga de OWNER con reauth + MFA.
- CRUD de MAINTENANCE_PROVIDER de plataforma.
- Siembra automática al crear owner (proveedores disponibles sin duplicar cuentas).
- Preferencias activadas: interno + externo. Apagadas: solo interno.
- n8n apagado: sistema funciona normal.
- n8n fallando: sistema funciona normal, evento queda FAILED en auditoría.
- Recovery sin tocar n8n.

---

# ETAPA 2 — Inmuebles, expediente tenant integral, contratos y permisos finos

## 2.1 Property como activo arrendable principal

`Property` es el centro operativo. No existe módulo funcional de unidades; cualquier rastro de `Unit` queda solo como deuda técnica de migración a retirar.

**Dirección completa obligatoria:** calle, número exterior, número interior (opcional), colonia, ciudad, estado, CP, referencia (opcional).

**`Property.status`:** `AVAILABLE`, `OCCUPIED`, `MAINTENANCE`, `DELETED`. Gobernado por el expediente activo y por mantenimiento.

## 2.2 Alta integral del tenant (endpoint transaccional único)

`POST /api/tenants` crea en una sola transacción:
- Cuenta `UserEntity` TENANT nueva o reutiliza existente por email.
- `OwnerMembership` si no existía para ese owner.
- `TenantProfile` (expediente completo).
- `Lease` ACTIVE por `propertyId`.
- `Property.status = OCCUPIED`.
- PDF de contrato opcional al alta.
- `PropertyMovementEntity` (movimiento inicial).
- Auditoría y evento de dominio.

**Validaciones:**
- Falla si el inmueble ya tiene lease ACTIVE.
- Falla si el email existe con otro rol distinto de TENANT.
- Reutiliza cuenta TENANT existente sin resetear contraseña.

## 2.3 Expediente del tenant

Contenido:
- Datos personales.
- Inmueble por dirección completa.
- Renta, depósito, día de pago.
- Recargos configurables (tipo fijo/porcentaje, monto, día de inicio).
- Incremento anual (fijo, porcentaje, INPC, ninguno).
- Penalización por terminación anticipada.
- Servicios incluidos (agua, luz, gas, internet).
- Contrato actual PDF + contratos históricos.
- Documentos adjuntos.
- Convenios.
- Estado contable básico.

## 2.4 Edición y traslados

`PUT /api/tenants/{id}` **no permite cambiar `propertyId`**. Si una persona deja un inmueble y toma otro:
1. Se archiva el expediente anterior.
2. Se crea un expediente nuevo.

"Mover tenant de inmueble" no existe como operación directa.

## 2.5 Archive del expediente (baja operativa)

`POST /api/tenants/{tenantProfileId}/archive` con payload mínimo:
- `vacancyRoutingMode: PRIVATE | PLATFORM`
- `reason` (opcional)

**Efectos transaccionales:**
- Archiva expediente (`TenantProfile.status = ARCHIVED`).
- Termina lease activo.
- Libera inmueble (`Property.status = AVAILABLE`).
- Cancela factura abierta del periodo actual (deja de contar como ingreso esperado).
- Crea `Vacancy` automáticamente.
- Crea `ActionTask` para el agente o para el owner.
- Crea `PropertyMovementEntity`.
- Despacha evento a n8n si está configurado.
- **Revocación de acceso del tenant:**
  - Si era su último expediente activo con ese owner → revoca OwnerMembership.
  - Si era su último owner activo → bloquea acceso total del tenant.

## 2.6 Contratos dentro del expediente

- El contrato vive dentro del expediente, no como módulo separado.
- Soporta: PDF firmado opcional al alta, reemplazo posterior, históricos, descarga.
- La UI del owner **no tiene** pestaña principal "Contratos".
- ADMINDI no genera ni firma contratos; solo almacena el PDF.

## 2.7 PROPERTY_ADMIN con permisos finos

**Plantillas de permisos:**
- Administrador Operativo (gestión diaria, sin financiero sensible).
- Administrador de Cobranza (pagos, convenios, reportes).
- Administrador Completo (todo excepto eliminar inmuebles/expedientes).
- Personalizado.

**Permisos individuales (cada uno con explicación de riesgo en UI):**

| Permiso | Qué permite | Riesgo |
|---------|-------------|--------|
| PROPERTY_CREATE / UPDATE | Crear / editar inmuebles | Bajo |
| PROPERTY_DELETE | Eliminar inmueble | ALTO |
| TENANT_CREATE / UPDATE | Alta / edición de inquilinos | Medio |
| TENANT_ARCHIVE | Archivar expediente | Medio |
| LEASE_CREATE / UPDATE | Gestión de contrato | Medio |
| LEASE_TERMINATE | Terminar contrato activo | ALTO |
| PAYMENT_VIEW / REVIEW / APPLY | Ver, revisar, aplicar pagos | Bajo / Medio |
| AGREEMENT_VIEW / APPROVE / REJECT | Ver, aprobar, rechazar convenios | Bajo / Medio |
| QUOTE_APPROVE / QUOTE_REJECT | Aprobar cotizaciones mantenimiento | Medio |
| EXPENSE_PAY / EXPENSE_SETTLEMENT_CONFIRM | Pagar egresos, confirmar settlement | Medio |
| VACANCY_ROUTE / VACANCY_MANAGE | Gestionar vacancias | Medio |
| PROVIDER_MANAGE | Agregar/editar proveedores propios | Bajo |
| REPORT_VIEW / EXPORT | Ver / exportar reportes | Bajo |
| EXPEDIENT_DELETE | Eliminar expedientes antiguos | ALTO |
| TEAM_MANAGE | Gestionar admins (solo si owner lo delega) | ALTO |

**Para permisos de riesgo ALTO:** el owner reautentica (contraseña + MFA), ve advertencia explícita de impacto, y queda en audit log quién otorgó, cuándo y desde qué IP.

## 2.8 Solicitud de borrado de inmueble

`PROPERTY_ADMIN` no puede eliminar inmuebles directamente. Crea `PropertyDeleteRequest`:
- Owner recibe `ActionTask` + `Notification`.
- Owner aprueba con reauth + MFA o rechaza.
- Si aprueba, se procesa soft delete con audit detallado.

## APIs de Etapa 2

```
POST   /properties
PUT    /properties/{id}
GET    /properties
GET    /properties/{id}
POST   /properties/delete-requests
PUT    /properties/delete-requests/{id}/approve
PUT    /properties/delete-requests/{id}/reject
POST   /properties/{id}/files
GET    /properties/{id}/files
POST   /tenants                                   (alta integral)
PUT    /tenants/{id}
GET    /tenants
GET    /tenants/{id}
POST   /tenants/{tenantProfileId}/archive
POST   /tenants/{tenantProfileId}/contract-document
GET    /tenants/{tenantProfileId}/contracts
GET    /permissions/templates
POST   /permissions/grants
PUT    /permissions/grants/{id}
POST   /owner/team/admins
POST   /owner/team/accountants
GET    /owner/team
```

## Pantallas OWNER (Etapa 2)

- **Dashboard:** resumen financiero, estado de inmuebles y ocupación por dirección, cola de pendientes (pagos por validar, convenios, presupuestos, desocupaciones, solicitudes de borrado).
- **Inmuebles:** CRUD con galería categorizada (fachada, sala, cocina, etc.) y propósito (baseline, current, move_out_inspection, maintenance).
- **Arrendatarios:** lista, alta integral (un solo formulario), expediente detallado.
- **Equipo y proveedores:** tres bloques: admins (con permisos finos), mantenimiento (platform/private), agentes (platform/private).
- **Configuración:** perfil, servicios, cobranza, overrides por inmueble, preferencias de notificación.

## Pruebas Etapa 2

- Alta integral reutiliza cuenta TENANT existente, crea expediente nuevo, ocupa inmueble.
- Alta falla si inmueble ya tiene lease ACTIVE.
- Alta falla si email existe con rol distinto de TENANT.
- Tenant con varios owners: token BASE → selecciona owner → ve solo ese owner.
- Tenant con varios inmuebles del mismo owner: ve todos sus expedientes.
- Edición no permite cambiar inmueble.
- Archive: termina lease, libera inmueble, abre vacancia, revoca acceso si último expediente, cancela factura abierta.
- Contrato: sube PDF, reemplaza, descarga desde expediente.
- Solicitud de borrado por PROPERTY_ADMIN → ActionTask al OWNER → aprobación con MFA.
- PROPERTY_ADMIN sin permisos no ve botones de acción.
- PROPERTY_ADMIN con permisos ALTO requiere MFA del OWNER al otorgarse.

---

# ETAPA 3 — Cobranza, SPEI/CEP, convenios, morosidad y resumen financiero

## 3.1 Generación automática de cargos mensuales

`@Scheduled` corre día 1 de cada mes:
- Genera `MonthlyLedger` por cada lease ACTIVE.
- Calcula: renta base + cargos extra + descuentos.
- Notifica al tenant (según preferencias) con monto y link de pago.

## 3.2 Medios de pago

### Mercado Pago (pasarela principal)
- Cada `OWNER` cobra con su propio perfil de Mercado Pago; no existe una credencial única global para todos los dueños.
- Las credenciales, tokens y secretos por owner se almacenan cifrados en reposo y nunca salen por n8n.
- Tenant paga desde portal con Checkout Pro embebido usando la configuración del owner activo.
- Webhook `payment.updated` → verifica HMAC y pertenencia al owner correcto → crea `Payment` VALIDATED → actualiza `MonthlyLedger`.
- Replay protection: rechaza timestamps > 5 min.
- Todo pago con tarjeta validado por webhook firmado se marca automáticamente como correcto y conciliado por origen `MP_WEBHOOK`.

### SPEI con validación CEP Banxico
Flujo completo dentro de la app:
1. Tenant sube comprobante (foto o PDF).
2. Sistema usa IA/OCR/visión para leer la imagen o PDF y extraer fecha, monto, clave de rastreo, banco emisor, cuenta receptora y referencia.
3. Si la imagen no es un comprobante válido o no contiene datos suficientes, el sistema le pide al tenant que mande los datos correctamente escritos para poder validar el SPEI.
4. Sistema intenta conciliación/CEP.
5. Si entra por `WHATSAPP`, n8n manda a backend mensaje, adjuntos, `whatsappMessageId` y teléfono normalizado; backend identifica por `contactPhone` en `E.164` y, si no hay match único/autorizado, pasa a `MANUAL_REVIEW`.
6. Si faltan datos: sistema solicita al tenant los faltantes exactos por portal o por `WHATSAPP` según canal de origen.
7. El sistema reintenta validación CEP hasta **3 intentos** con trazabilidad completa.
8. Si al tercer intento no es posible validar por datos insuficientes, imagen incorrecta o respuesta inconclusa, crea alerta al owner/admin con `PAYMENT_REVIEW` indicando arrendatario e inmueble para que confirme si sí recibió la transferencia.
9. Solo después de esa confirmación explícita puede entrar a contabilidad y al resumen mensual con origen `MANUAL_OVERRIDE`, guardando actor, motivo y evidencia.
10. Marca `VALIDATED`, `PENDING_DATA`, `PENDING_VALIDATION`, `MANUAL_REVIEW` o `REJECTED`.
11. Si el CEP valida, el sistema debe descargar y preservar los artefactos oficiales disponibles de Banxico/CEP: XML, PDF/acuse y payload oficial que entregue el proveedor o adapter.
12. Esos artefactos se guardan ligados al pago, a la factura y al expediente mensual del inmueble con hash, fecha de descarga y fuente de verificación.

### Efectivo manual auditado
Owner/admin registra con evidencia (foto, nota, referencia, monto). Queda `PAYMENT_MANUAL`.

El pago en efectivo debe poder marcarse explícitamente como:
- `BANK_STATEMENT_PENDING`
- `BANK_STATEMENT_MATCHED`
- `BANK_STATEMENT_MISMATCH`

Cada confirmación contra estado de cuenta debe guardar:
- quién la confirmó;
- fecha/hora;
- evidencia adjunta;
- nota de conciliación;
- referencia bancaria o folio interno.

## 3.2.1 Expediente mensual del inmueble

Cada inmueble debe tener un expediente mensual navegable por `monthYear` donde se vea:
- cuánto se esperaba cobrar;
- cuánto se cobró realmente;
- método de pago;
- origen de verificación (`MP_WEBHOOK`, `CEP_BANXICO`, `BANK_STATEMENT_MATCH`, `MANUAL_OVERRIDE`);
- artefactos descargables (CEP XML/PDF/acuse, comprobante, evidencia de efectivo);
- actor o sistema que confirmó;
- estado final de conciliación.

## 3.3 Ingresos esperados y archive

- El ingreso esperado se basa en facturas vigentes y expedientes activos del periodo.
- Al archivar un expediente: la factura abierta del periodo actual se cancela/void.
- Deja de contar como ingreso esperado y pendiente.
- Lo ya cobrado queda histórico.
- No hay prorrateo en esta fase.

## 3.4 Pago parcial: razón obligatoria

Cuando un pago confirmado no liquida la deuda, el sistema pide razón al tenant:

| Razón | Comportamiento |
|-------|----------------|
| `PARTIAL_SAME_MONTH` | Saldo sigue abierto. Si no paga antes del vencimiento + gracia → mora automática. |
| `PARTIAL_NEXT_MONTH` | Sistema dirige a solicitud de convenio. Sin convenio, aplica mora al vencer. |
| `REQUESTING_AGREEMENT` | Inicia flujo de convenio. |
| `BANK_ISSUE` / `OTHER` | Informativo. Saldo sigue sujeto a mora. |

Campos: `shortfallReason`, `shortfallDescription` (opcional), `promisedCompletionDate` (opcional).

Si el tenant no da razón → `ActionTask` automática.

## 3.5 Convenios

**Flujo:**
1. Tenant solicita convenio sobre factura o saldo pendiente.
2. Owner (o PROPERTY_ADMIN con `AGREEMENT_APPROVE`) aprueba/rechaza **desde la app**, nunca por WhatsApp libre.
3. Si aprueba: queda obligación original, monto aprobado ahora, monto diferido, parcialidades futuras, evidencia opcional.

**Tipos:** `MOVE_DUE_DATE`, `INSTALLMENTS`, `DISCOUNT`.

**Estados:** `REQUESTED`, `PENDING_OWNER_APPROVAL`, `APPROVED`, `REJECTED`, `ACTIVE`, `COMPLETED`, `BREACHED`, `CANCELLED`.

**Evidencia opcional:** `POST /api/agreements/{id}/evidence` con PDF o imagen, `evidenceType`, `description`.

**Incumplimiento automático:**
- Si una parcialidad vence sin pago:
  - installment → `LATE`
  - agreement → `BREACHED`
  - Movimiento del inmueble + alerta al owner (según preferencias).

## 3.6 Morosidad automática

Si el owner configuró morosidad en `TenantProfile`:
- Tipo: fijo o porcentaje.
- Monto configurable.
- Día de inicio del recargo.
- Aplica a saldos vencidos **no cubiertos ni protegidos por convenio activo**.
- El monto diferido de un convenio aprobado no genera mora hasta la fecha de sus parcialidades.

## 3.7 Escalamiento automático (configurable por dueño/inmueble)

```
Día 1:  recordatorio amigable (inbox + canal preferido)
Día 5:  segundo aviso firme
Día 8:  recargo automático aplicado
Día 15: aviso formal PDF por canal preferido
Día 30+: notificación al dueño con recomendación
```

Cada paso respeta preferencias de notificación.

## 3.8 Portal del TENANT (Etapa 3)

Ya funciona MP y SPEI. Se agrega:
- Razón de pago parcial obligatoria.
- Solicitud de convenio con evidencia opcional.
- Ver estado de convenio.
- Ver saldo pendiente / saldo a favor.
- Dashboard organizado por owner y expediente (si multi-contexto).

## 3.9 Resumen financiero del OWNER

`OwnerMonthlyAccountingSummaryDTO`:
```
expectedIncome, collectedIncome, outstandingIncome, overpaidCredits
approvedExpenses, paidExpenses, pendingExpenses
lateFeeAccrued
activeAgreementsCount, breachedAgreementsCount
delinquentTenantsCount, propertiesWithIssuesCount
```

Listados de detalle:
- **Receivables:** inmueble, tenant, total renta, pagado, pendiente, convenio o mora.
- **Expenses:** inmueble, tipo (`MAINTENANCE`, `COMMERCIAL`, `MANUAL`), aprobado, pagado, pendiente.
- **Alerts:** mora, convenio incumplido, ticket abierto, vacancia activa.

## 3.10 ACCOUNTANT portal

- **Dashboard financiero:** ingresos cobrados/pendientes, egresos pagados, resultado neto, alertas ("5 pagos sin CFDI").
- **Reporte de facturación** (pantalla principal): todos los datos para CFDI — fecha, inquilino, RFC, inmueble + predial, monto, método PUE/PPD, forma de pago SAT (01/03/04), clave SAT 80131500, retenciones si persona moral. Exportable Excel/PDF.
- **Resumen mensual por inmueble:** mismo que ve el owner, solo lectura.
- **Expediente mensual del inmueble:** detalle mes a mes de facturas, pagos, método, verificación, evidencia y conciliación.
- **Historial de pagos:** filtros, exportable.

Solo lectura. NO ve: tickets, mensajes WhatsApp, fotos, configuración.

## APIs de Etapa 3

```
GET    /payments
POST   /payments
GET    /payments/{id}
POST   /payments/{invoiceId}/shortfall-reason
GET    /payments/proofs
POST   /payments/proofs
POST   /payments/cep-validation
POST   /payments/{id}/review
POST   /payments/{id}/apply
GET    /agreements
GET    /agreements/my
POST   /agreements
PUT    /agreements/{id}/approve
PUT    /agreements/{id}/reject
POST   /agreements/{id}/evidence
GET    /ledger/monthly/{leaseId}
GET    /reports/monthly-summary
GET    /reports/billing
GET    /reports/export
GET    /owner/accounting-summary
POST   /integrations/mercadopago/webhook
```

## Pruebas Etapa 3

- Pago exacto, dos parciales acumulativos, excedente, saldo a favor.
- Razón de pago parcial obligatoria (no puede omitirla).
- Mora aplicada a saldo vencido sin convenio.
- No mora sobre saldo cubierto por convenio activo.
- Convenio: solicitud → aprobación con parcialidades → estado ACTIVE.
- Convenio rechazado: queda `REJECTED`.
- Convenio con evidencia: PDF sube y descarga.
- Convenio incumplido: parcialidad vence → `LATE` → agreement `BREACHED`.
- SPEI/CEP: validación correcta.
- SPEI/CEP incompleto: sistema pide datos faltantes, reintenta, valida.
- SPEI/CEP rechazado: manual review disponible.
- Escalamiento automático día 1, 5, 8, 15, 30.
- Archive cancela factura abierta → deja de aparecer como ingreso esperado.
- Resumen mensual del owner coincide con libro mayor del accountant.

---

# ETAPA 4 — Mantenimiento, vacancia, comercial y pagos a terceros bilaterales

## 4.1 Centro operativo del inmueble (timeline)

Nueva entidad `PropertyMovementEntity`:
```
id, propertyId, ownerId, resourceType, resourceId,
actorUserId, actorRole, eventType, title, description,
occurredAt, metadataJson, attachmentFileId (opcional)
```

**Eventos mínimos:**
```
PROPERTY_CREATED, PROPERTY_UPDATED, PROPERTY_FILE_UPLOADED
LEASE_CREATED, LEASE_TERMINATED
PAYMENT_VALIDATED, PAYMENT_PARTIALLY_APPLIED, PAYMENT_OVERPAID
AGREEMENT_REQUESTED, AGREEMENT_APPROVED, AGREEMENT_REJECTED,
  AGREEMENT_ACTIVE, AGREEMENT_COMPLETED, AGREEMENT_BREACHED
MAINTENANCE_TICKET_CREATED, MAINTENANCE_QUOTE_SUBMITTED,
  MAINTENANCE_QUOTE_APPROVED, MAINTENANCE_QUOTE_REJECTED,
  MAINTENANCE_WORK_STARTED, MAINTENANCE_WORK_COMPLETED,
  MAINTENANCE_PAYMENT_RECORDED
VACANCY_OPENED
REAL_ESTATE_VISIT_RECORDED, REAL_ESTATE_PHOTOS_UPLOADED,
  REAL_ESTATE_COMMISSION_QUOTED, REAL_ESTATE_COMMISSION_APPROVED,
  REAL_ESTATE_COMMISSION_PAID
VACANCY_CLOSED
```

El detalle del inmueble consume esta timeline como bitácora viva. No se reconstruye historia desde métricas.

## 4.2 Galería con contexto operativo

`PropertyFileDTO` enriquecido:
- `uploadedBy`, `uploaderRole`, `uploadedAt`
- `label`: `BASELINE`, `UPDATE`, `BEFORE`, `AFTER`, `VISIT`, `MAINTENANCE`, `VACANCY`, `AGREEMENT_EVIDENCE`, `OTHER`
- `note`

El owner ve quién subió cada foto, cuándo y para qué.

## 4.3 Mantenimiento

### Configuración del owner
`maintenanceRoutingMode: PRIVATE | PLATFORM | MIXED`

**Sin fallback silencioso:** si eligió PRIVATE y no tiene proveedor privado activo → bloquea resolución automática + crea `ActionTask`.

### Flujo completo
1. TENANT abre ticket desde portal.
2. Sistema asigna/notifica al proveedor según configuración.
3. Proveedor recibe tarea (inbox + canal preferido con ActionToken si aplica).
4. Proveedor acepta o rechaza.
5. Proveedor visita, sube evidencia y cotización.
6. OWNER (o PROPERTY_ADMIN con `QUOTE_APPROVE`) aprueba/rechaza **desde la app**.
7. Si aprueba: se registra egreso en estado `APPROVED_PENDING_PAYMENT`.
8. Proveedor ejecuta trabajo, sube evidencia final.
9. Owner/admin registra pago al proveedor.
10. Confirmación bilateral (ver 4.5).

### Entidades
- `MaintenanceTicketEntity`
- `MaintenanceQuoteEntity`
- `MaintenanceWorkLogEntity`
- `MaintenanceEvidenceEntity`
- `ExpenseEntity` con `sourceType = MAINTENANCE`

### Estados del ticket
`OPEN`, `ASSIGNED`, `ACCEPTED`, `QUOTED`, `APPROVED`, `IN_PROGRESS`, `COMPLETED`, `REJECTED`, `CANCELLED`.

### Estados de la cotización/pago
`DRAFT`, `SUBMITTED`, `APPROVED_PENDING_PAYMENT`, `PARTIALLY_PAID`, `PAID`, `REJECTED`.

## 4.4 Vacancia y comercial

### Apertura automática
Al archivar expediente se abre vacancia automáticamente con `vacancyRoutingMode: PRIVATE | PLATFORM` elegido en el archive.

**Regla obligatoria:** si elige `PRIVATE` y no tiene agente privado activo → **bloquea** el archive y ofrece:
- Registrar/vincular agente privado.
- Continuar con agentes de plataforma.

### Flujo del agente
1. Recibe tarea (inbox + canal preferido).
2. Acepta o rechaza.
3. Inspecciona el inmueble.
4. Sube fotos del estado (label `VACANCY` o `VISIT`).
5. Registra visitas, notas, interesados.
6. Sube cotización de comisión si aplica.
7. Owner aprueba/rechaza comisión desde la app.
8. Cuando consigue nuevo tenant, reporta cliente encontrado.
9. Owner registra nuevo tenant (alta integral).
10. Vacancia se cierra automáticamente.

### Entidades
- `VacancyEntity`
- `RealEstateActivityEntity`
- `RealEstateQuoteEntity`
- `ExpenseEntity` con `sourceType = COMMERCIAL`

### Estados
`VACANCY_OPEN`, `LISTING_ACTIVE`, `VISIT_RECORDED`, `COMMISSION_QUOTED`, `COMMISSION_APPROVED`, `COMMISSION_REJECTED`, `COMMISSION_PAID`, `VACANCY_CLOSED`.

## 4.5 Pago a proveedores/agentes con confirmación bilateral

**El punto más crítico de la etapa.** Los pagos a terceros ya no son unilaterales.

### Capa de settlement del egreso
`ExpenseEntity` con:
- `approvedAmount`
- `paidAmount`
- `outstandingAmount`
- `paymentSettlementStatus`: `UNPAID` | `PARTIALLY_PAID` | `PAID_IN_FULL` | `DISPUTED`
- `paymentMethod`: `SPEI` | `CASH` | `OTHER`
- `ownerConfirmationStatus`: `PENDING` | `CONFIRMED`
- `providerConfirmationStatus`: `PENDING` | `CONFIRMED` | `DISPUTED`

### Flujo de confirmación
1. Owner/admin registra pago (parcial o completo) al proveedor/agente.
2. Se crea `ActionTask` para el proveedor/agente.
3. Proveedor/agente confirma desde su portal:
   - "Me pagaron completo"
   - "Me pagaron parcial"
   - "No corresponde / hay diferencia"
4. **Resolución:**
   - Ambos confirman completo → `PAID_IN_FULL`.
   - Ambos reconocen parcial → `PARTIALLY_PAID` con saldo pendiente.
   - Desacuerdo → `DISPUTED` + `ActionTask` para owner/admin/accountant. n8n puede notificar; no resuelve.

**Aplica igual** a mantenimiento y a comisiones de agentes inmobiliarios.

## 4.6 Equipo y proveedores (sección unificada en Config del OWNER)

Tres bloques:
- **Administradores:** lista con plantilla, permisos finos, acciones.
- **Mantenimiento:** cada fila muestra origen (`PLATFORM`/`PRIVATE`), estado, contacto, vínculo, acciones.
- **Agentes inmobiliarios:** mismo esquema que mantenimiento.

## 4.7 Portales de MAINTENANCE_PROVIDER y REAL_ESTATE_AGENT

### MAINTENANCE_PROVIDER
- Dashboard: tickets nuevos, en progreso, completados del mes, presupuestos pendientes, pagos pendientes.
- Mis tickets: aceptar, rechazar, enviar presupuesto, marcar en progreso, completar con evidencia, confirmar pago bilateral.
- Mi expediente: métricas, historial, pagos recibidos/pendientes, perfil, MFA.

### REAL_ESTATE_AGENT
- Dashboard: vacancias asignadas, aceptadas/pendientes, días promedio, comisiones pendientes.
- Mis vacancias: aceptar/rechazar, actualizar con notas e interesados, subir fotos, reportar cliente encontrado, confirmar pago bilateral.
- Mi expediente: métricas, historial, comisiones pagadas/pendientes, perfil, MFA.

## APIs de Etapa 4

```
GET    /properties/{id}/timeline
POST   /maintenance/tickets
GET    /maintenance/tickets
GET    /maintenance/tickets/my
GET    /maintenance/tickets/assigned
GET    /maintenance/tickets/{id}
POST   /maintenance/tickets/{id}/accept
POST   /maintenance/tickets/{id}/reject
POST   /maintenance/quotes
PUT    /maintenance/quotes/{id}/approve
PUT    /maintenance/quotes/{id}/reject
POST   /maintenance/work-logs
POST   /maintenance/evidence
POST   /vacancies
GET    /vacancies
GET    /vacancies/assigned
PUT    /vacancies/{id}/route
POST   /vacancies/{id}/accept
POST   /vacancies/{id}/activity
POST   /vacancies/{id}/client-found
PUT    /vacancies/{id}/close
POST   /expenses/{id}/payments
POST   /expenses/{id}/confirmations/owner
POST   /expenses/{id}/confirmations/provider
GET    /expenses/{id}/settlement
GET    /owner/team/routing-preferences
PUT    /owner/team/routing-preferences
GET    /providers/my-profile
GET    /providers/{id}/metrics
```

## Pruebas Etapa 4

- Ticket: tenant abre, proveedor recibe, acepta, sube cotización y fotos, owner aprueba → egreso, owner rechaza → sin egreso.
- Mantenimiento PRIVATE sin proveedor → bloquea + ActionTask (no fallback).
- Mantenimiento PLATFORM → asigna y notifica.
- Pago parcial al proveedor → confirmación bilateral → `PARTIALLY_PAID`.
- Pago completo al proveedor → ambas confirmaciones → `PAID_IN_FULL`.
- Desacuerdo → `DISPUTED` + `ActionTask`.
- Vacancia automática al archivar expediente.
- Vacancia PRIVATE sin agente → bloquea archive + ofrece opciones.
- Agente sube fotos etiquetadas, registra visitas, cotiza comisión.
- Comisión aprobada → egreso COMMERCIAL + settlement bilateral.
- Vacancia cierra con nuevo tenant registrado.
- Timeline del inmueble refleja todos los eventos ordenados.
- Galería muestra autor, fecha, etiqueta.

---

# ETAPA 5 — Reportería integrada, UX, IA copiloto, hardening y producción

## 5.1 Resumen mensual por inmueble

`PropertyMonthlySummaryDTO`:
```
propertyId, propertyName, monthYear, occupancyStatus
expectedIncome, collectedIncome, outstandingIncome
partialPaymentsCount
activeAgreementsCount, breachedAgreementsCount
approvedMaintenanceExpense, paidMaintenanceExpense
approvedCommercialExpense, paidCommercialExpense
ticketsOpenCount, vacancyStatus
newPhotosCount, keyEvents
```

## 5.2 Resumen anual por inmueble

`PropertyAnnualSummaryDTO`: agregación 12 meses de ingresos, egresos, morosidad, convenios, mantenimiento, vacancias, actividad comercial.

## 5.3 Reportería integrada

Debe reflejar con la **misma historia** para OWNER, PROPERTY_ADMIN autorizado y ACCOUNTANT:

- Ingresos esperados/cobrados/pendientes.
- Facturas canceladas por archive.
- Convenios activos/incumplidos.
- Egresos aprobados/pagados/pendientes con settlement bilateral.
- Mantenimiento: presupuestado, aprobado, pagado, pendiente, confirmado, disputado.
- Comercial/agentes: comisión aprobada, pagada, pendiente, confirmada, disputada.
- Inmuebles ocupados, liberados, en vacancia.
- Seguimiento comercial activo.
- Tareas pendientes relevantes.

Reporte interno adicional: qué se pagó completo, qué falta pagar a terceros, qué falta cobrar, qué convenios están abiertos y de qué tipo.

**Exportaciones:** Excel (hojas separadas), PDF ejecutivo, JSON/ZIP para backup.

## 5.4 IA como copiloto opcional

Solo para:
- Redactar resumen narrativo mensual por inmueble.
- Clasificar tono/causa libre de convenios o pagos parciales.
- Resaltar riesgos y anomalías.

**Reglas estrictas:**
- IA **no** cambia estados financieros.
- IA **no** aprueba pagos, convenios ni egresos.
- Si IA falla, el sistema sigue con resumen determinístico.
- Toda salida de IA queda marcada como "generada por IA" y es editable por el humano.

## 5.5 UX consolidada

### Navegación principal OWNER / PROPERTY_ADMIN
```
Resumen · Inmuebles · Arrendatarios · Pendientes · Convenios · Equipo y proveedores
```

- **No más pestañas principales** de "Contratos" ni módulo de "Unidades".
- El expediente del tenant es el centro de trabajo.
- Navbar con selector de contexto para multi-owner.

### Dashboard TENANT
Organizado por owner y expediente (si multi-contexto).

### Date picker (owner/accountant)
- Mínimo válido por fecha de owner/property.
- Máximo mes actual.
- Sin fechas futuras ni previas fuera de rango.

### Secciones por inmueble (panel del dueño)
```
Resumen · Galería · Timeline · Cobranza · Convenios · Mantenimiento · Vacancia/Comercial
```

## 5.6 Hardening para producción

- **Cifrado en reposo** endurecido. `ENCRYPTION_KEY` obligatoria en producción; el sistema falla si no está. Error claro si ciphertext no se puede descifrar.
- **CEP real** (reemplaza mock).
- **CSP, HSTS, CORS, Content-Type** estrictos.
- **Rate limiting** revisado según carga.
- **Docker distroless**, Cloudflare WAF.
- **Hibernate Envers** para versionado histórico si aplica.
- **Vault** para secretos sensibles.
- **ARCO endpoints** (LFPDPPP): exportar datos (Acceso), rectificar, cancelar, oponerse.

## 5.7 QA final por bloques

1. Seguridad y contextos (login, MFA, selector, switch).
2. Tenant multi-owner multi-inmueble (alta, selección, edición, archive).
3. Cobranza (MP, SPEI/CEP, parcial, razón obligatoria, mora).
4. Convenios (solicitud, aprobación, incumplimiento, evidencia).
5. Mantenimiento (routing, sin fallback silencioso, aprobación, settlement).
6. Vacancia/comercial (apertura automática, routing, fotos, comisión, settlement).
7. Confirmación bilateral (pago completo, parcial, disputa).
8. Reportes (consistencia numérica entre owner/admin/accountant).
9. Preferencias de notificación (interno, email, WhatsApp).
10. n8n apagado + n8n fallando (sistema no se bloquea).
11. Timeline del inmueble (eventos ordenados, correctos).
12. IA copiloto (si falla, sistema sigue).

**Evidencia real requerida:** request/response, logs, queries SQL, capturas UI, verificación de exports.

---

# MATRIZ DE ACCESO POR ENDPOINT (referencia rápida)

```
Leyenda: SA=SUPER_ADMIN  OW=OWNER  PA=PROPERTY_ADMIN(P=con permiso)
         AC=ACCOUNTANT  TE=TENANT  RE=REAL_ESTATE_AGENT  MA=MAINTENANCE_PROVIDER

ENDPOINT                               │ SA │ OW │ PA │ AC │ TE │ RE │ MA │ Extra
───────────────────────────────────────┼────┼────┼────┼────┼────┼────┼────┼─────
/auth/login, /refresh                  │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ Rate limit
/auth/contexts, /select-context, /switch│ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ Token BASE
/admin/**                              │ ✅ │ ❌ │ ❌ │ ❌ │ ❌ │ ❌ │ ❌ │ —
/admin/owners/{id}/purge               │ ✅ │ ❌ │ ❌ │ ❌ │ ❌ │ ❌ │ ❌ │ Reauth+MFA
/properties, POST/PUT                  │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ PROPERTY_*
/properties/{id} DELETE                │ ✅ │ ✅ │ P* │ ❌ │ ❌ │ ❌ │ ❌ │ *DeleteRequest
/properties/{id}/timeline              │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ RLS
/tenants POST (alta integral)          │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ TENANT_CREATE
/tenants/{id}/archive                  │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ TENANT_ARCHIVE
/payments                              │ ✅ │ ✅ │ P  │ ✅ │ ❌ │ ❌ │ ❌ │ PAYMENT_VIEW
/payments/my, POST                     │ ❌ │ ❌ │ ❌ │ ❌ │ ✅ │ ❌ │ ❌ │ Solo suyos
/payments/{id}/apply                   │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ PAYMENT_APPLY
/payments/cep-validation               │ ✅ │ ✅ │ P  │ ❌ │ ✅ │ ❌ │ ❌ │ PAYMENT_REVIEW
/agreements                            │ ✅ │ ✅ │ P  │ ✅ │ ❌ │ ❌ │ ❌ │ AGREEMENT_VIEW
/agreements/my, POST                   │ ❌ │ ❌ │ ❌ │ ❌ │ ✅ │ ❌ │ ❌ │ Solo suyos
/agreements/{id}/approve               │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ AGREEMENT_APPROVE
/maintenance/tickets                   │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ RLS
/maintenance/tickets/my                │ ❌ │ ❌ │ ❌ │ ❌ │ ✅ │ ❌ │ ❌ │ Solo suyos
/maintenance/tickets/assigned          │ ❌ │ ❌ │ ❌ │ ❌ │ ❌ │ ❌ │ ✅ │ Solo asignados
/maintenance/quotes/{id}/approve       │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ QUOTE_APPROVE
/vacancies                             │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ RLS
/vacancies/assigned                    │ ❌ │ ❌ │ ❌ │ ❌ │ ❌ │ ✅ │ ❌ │ Solo asignadas
/expenses/{id}/payments                │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ EXPENSE_PAY
/expenses/{id}/confirmations/owner     │ ✅ │ ✅ │ P  │ ❌ │ ❌ │ ❌ │ ❌ │ EXPENSE_SETTLEMENT_CONFIRM
/expenses/{id}/confirmations/provider  │ ❌ │ ❌ │ ❌ │ ❌ │ ❌ │ ✅ │ ✅ │ Solo destinatario
/reports/**                            │ ✅ │ ✅ │ P  │ ✅ │ ❌ │ ❌ │ ❌ │ REPORT_VIEW
/reports/export                        │ ✅ │ ✅ │ P  │ ✅ │ ❌ │ ❌ │ ❌ │ REPORT_EXPORT+audit
/notifications, /tasks                 │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ Solo suyos
/preferences/notifications             │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ ✅ │ Solo suyas
/webhooks/mercadopago, /whatsapp, /n8n │ PÚBLICO con HMAC + idempotencia + replay protection
```

---

# RESUMEN EJECUTIVO DE LAS 5 ETAPAS

| Etapa | Foco | Estado actual | Bloqueadores |
|-------|------|---------------|--------------|
| **1** | Seguridad, identidad, contextos, SUPER_ADMIN, notificaciones, n8n | 80% | Contextos multi-owner, siembra, notification center |
| **2** | Property, expediente tenant integral, contratos, permisos finos | 30% | Alta integral transaccional, archive con cancelación de factura, permisos finos |
| **3** | Cobranza, SPEI/CEP, convenios, morosidad, resumen financiero | 50% | CEP real, razón parcial obligatoria, incumplimiento automático |
| **4** | Mantenimiento, vacancia, comercial, pagos bilaterales | 20% | Timeline, settlement bilateral, routing sin fallback |
| **5** | Reportería integrada, UX final, IA copiloto, hardening | 10% | Resumen anual, IA copiloto, hardening producción |

## Orden recomendado de trabajo

1. **Cerrar Etapa 1** al 100% (contextos, siembra, notificaciones).
2. **Cerrar Etapa 2** (el modelo tenant multi-owner es el cimiento; sin esto nada más funciona).
3. **Etapa 3** (cobranza completa con CEP real).
4. **Etapa 4** (el diferenciador: timeline + bilateral).
5. **Etapa 5** (pulido, reportería final, producción).

## Supuestos fijos (no negociables)

- Una sola cuenta tenant por email en toda la plataforma.
- Muchos expedientes por tenant (multi-owner + multi-inmueble).
- Un solo lease ACTIVE por inmueble.
- Crear expediente = ocupación inmediata.
- Archivar ≠ borrar. "Eliminar tenant" operativamente es archivar expediente.
- Revocación de acceso es por owner; sin owners activos = sin acceso total.
- Ingreso esperado se cancela al archivar si la factura del periodo sigue abierta.
- Toda aprobación sensible ocurre dentro de la app.
- n8n solo notifica y acompaña; nunca reemplaza lógica principal.
- PROPERTY_ADMIN solo autoriza si tiene permisos explícitos.
- ACCOUNTANT observa y reporta todo, no aprueba.
- Pagos a terceros requieren confirmación bilateral.
- Toda notificación externa tiene equivalente interno.
- IA es copiloto opcional, nunca motor de decisiones financieras.

---

**Este documento es la fuente de verdad. Cualquier desviación requiere actualización aquí primero.**
