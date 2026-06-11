# ADMINDI — Plan maestro consolidado de plataforma

## Resumen
ADMINDI será una plataforma inmobiliaria multi-owner y multi-contexto, con operación completa dentro de la app y n8n solo como capa opcional de automatización/mensajería. El activo arrendable principal será `Property`, identificado por dirección completa con número interior opcional; no existe módulo funcional de `Unit/Unidades` en el producto.

El modelo operativo consolidado queda así:
- Un `OWNER` gobierna su negocio completo.
- `PROPERTY_ADMIN`, `ACCOUNTANT`, `TENANT`, `REAL_ESTATE_AGENT` y `MAINTENANCE_PROVIDER` pueden existir en varios contextos.
- El `TENANT` es una sola cuenta global por email y puede tener muchos expedientes en distintos inmuebles y con distintos dueños.
- Cada expediente tenant es una relación `tenant + owner + property`.
- Crear expediente ocupa el inmueble de inmediato.
- Archivar expediente libera el inmueble, abre vacancia, revoca acceso a ese owner cuando corresponda y cancela la factura abierta del periodo actual para que deje de contar como ingreso esperado.
- Los pagos a mantenimiento y agentes deben soportar pago parcial/total y confirmación bilateral.
- `OWNER`, `PROPERTY_ADMIN` con permisos y `ACCOUNTANT` deben ver la misma historia operativa y contable.

## Fase 0 — Seguridad, identidad y automatización base
### Seguridad y sesión
- JWT RS256 con `access token` corto y `refresh token` rotatorio.
- Revocación por `jti` y detección de reuse.
- MFA obligatorio para `SUPER_ADMIN`, `OWNER`, `PROPERTY_ADMIN`, `ACCOUNTANT`.
- MFA opcional/recomendado para `TENANT`, `REAL_ESTATE_AGENT`, `MAINTENANCE_PROVIDER`.
- Reautenticación + MFA para acciones destructivas, aprobaciones sensibles, exportaciones críticas y recovery administrativo.

### Contextos y acceso
- `SUPER_ADMIN` no usa contexto owner.
- `OWNER` entra directo a su owner.
- `PROPERTY_ADMIN`, `ACCOUNTANT`, `TENANT`, `REAL_ESTATE_AGENT` y `MAINTENANCE_PROVIDER` usan selector de contexto cuando tengan más de un owner activo.
- El flujo técnico estándar es:
  - login primer factor;
  - si aplica MFA, challenge y verify;
  - token `BASE`;
  - `GET /auth/contexts`;
  - `POST /auth/select-context`;
  - token `FULL` con owner activo.
- `OwnerMembership` se vuelve la fuente común de contexto para todos los roles multi-owner, incluido `TENANT`.
- El tenant selecciona primero owner; dentro del owner ve o elige sus expedientes de ese dueño.

### Notificaciones y n8n
- Toda notificación externa debe tener equivalente interno.
- Todo evento importante crea `Notification`.
- Todo evento que exige decisión crea además `ActionTask`.
- n8n consume eventos de salida, pero nunca contiene la lógica principal ni aprueba nada.
- Preferencias por usuario, evento y canal:
  - en app obligatorio;
  - `EMAIL` opcional y enviado por backend;
  - `WHATSAPP` opcional vía n8n.
- `OWNER_CREATED` queda como bootstrap interno/auditable, no como evento configurable para usuario.

## Fase 1 — Organización, roles y operación de plataforma
### SUPER_ADMIN
- Dashboard global.
- CRUD y purga total de `OWNER`.
- Alta y recovery administrativa de usuarios críticos.
- Configuración global de Mercado Pago, CEP/Banxico, n8n, almacenamiento y parámetros.
- Catálogo base de proveedores de mantenimiento y agentes inmobiliarios de plataforma.
- Al crear un `OWNER`, se enlazan automáticamente como disponibles los proveedores/agentes de plataforma activos.

### OWNER
- Control total del portafolio, configuración, equipo, reportería y aprobaciones.
- Panel de resumen y bandeja de pendientes real.
- Gestión de inmuebles, expedientes, convenios, mantenimiento, vacancias, agentes y proveedores.
- Configuración de preferencias de routing:
  - mantenimiento: `PRIVATE | PLATFORM`;
  - vacancia/comercial: `PRIVATE | PLATFORM` al momento de liberar inmueble.
- Gestión de cuenta receptora oficial para conciliación SPEI/CEP.
- Gestión de preferencias de notificación.

### PROPERTY_ADMIN
- Opera dentro del owner activo con permisos finos.
- No autoriza nada sensible por default.
- Permisos mínimos explícitos:
  - `QUOTE_APPROVE`
  - `QUOTE_REJECT`
  - `EXPENSE_PAY`
  - `EXPENSE_SETTLEMENT_CONFIRM`
  - `VACANCY_ROUTE`
  - `TEAM_MANAGE`
  - `PROPERTY_ARCHIVE_TENANT`
- Eliminar inmueble nunca es acción directa: crea `PropertyDeleteRequest` para aprobación del owner con reauth + MFA.

### ACCOUNTANT
- Multi-owner con selector de contexto.
- Solo lectura/exportación.
- Debe ver ingresos, egresos, convenios, vacancias, pagos parciales/completos a terceros, disputas y consistencia contable del owner activo.

## Fase 2 — Inmueble, expediente tenant y contratos
### Dominio principal
- `Property` es el activo arrendable.
- No existe módulo funcional de `Unit/Unidades`.
- La UI ya no expone “Unidades” y no trata “Contrato” como módulo principal separado del dueño.
- Dirección completa obligatoria:
  - calle
  - número exterior
  - número interior opcional
  - colonia
  - ciudad
  - estado
  - CP
  - referencia opcional

### Tenant y expediente
- `UserEntity` TENANT representa a la persona.
- `TenantProfile` representa el expediente de esa persona en un owner y un property.
- Un mismo tenant puede tener:
  - muchos expedientes con el mismo owner;
  - muchos expedientes con distintos owners.
- Un inmueble solo puede tener una tenencia `ACTIVE`.

### Alta integral
- `POST /api/tenants` crea en una sola transacción:
  - cuenta tenant nueva o reutilizada;
  - `OwnerMembership` si no existía para ese owner;
  - expediente `TenantProfile`;
  - `Lease`/tenencia `ACTIVE` por `propertyId`;
  - `Property.status = OCCUPIED`;
  - PDF de contrato opcional;
  - movimiento y evento.
- Si el email ya existe como `TENANT`, se reutiliza sin reset de contraseña.
- Si el email ya existe con otro rol, falla con error de negocio.
- Si el inmueble ya tiene tenencia activa, falla.

### Edición y traslados
- `PUT /api/tenants/{id}` no permite cambiar `propertyId`.
- “Mover tenant de inmueble” no existe como edición.
- Si una persona deja un inmueble y toma otro:
  - se archiva/cierra la relación anterior;
  - se crea un expediente nuevo.

### Contratos
- El contrato vive dentro del expediente.
- Debe soportar:
  - PDF firmado opcional al alta;
  - reemplazo posterior;
  - históricos;
  - descarga visible desde expediente.
- El flujo principal del owner/admin no pasa por abrir un modal de contrato aparte.

## Fase 3 — Cobranza, SPEI/CEP, convenios y reportería
### Ingresos
- Medios soportados:
  - Mercado Pago;
  - SPEI con validación CEP;
  - efectivo manual auditado.
- Flujo SPEI:
  - tenant sube comprobante;
  - sistema intenta conciliación/CEP;
  - si faltan datos, los pide;
  - reintenta;
  - marca `VALIDATED`, `PENDING` o `REJECTED`.
- El owner/admin con permiso ve el estado exacto de la validación.

### Ingresos esperados
- El ingreso esperado se basa en facturas vigentes y expedientes activos del periodo.
- Cuando se archiva un expediente:
  - la factura abierta del periodo actual se cancela/void;
  - deja de contar como ingreso esperado y pendiente;
  - lo ya cobrado queda histórico.
- No hay prorrateo en esta fase.

### Convenios
- Convenios ligados al expediente/tenantProfile.
- Estados, evidencia, incumplimiento y tipo de convenio visibles en dashboards y reportes.
- Los convenios deben aparecer en owner/admin/accountant con la misma lectura del mes.

### Reportería
- Resumen mensual/anual por owner y por inmueble.
- Exportaciones JSON/ZIP/Excel.
- Deben reflejar:
  - ingresos esperados/cobrados/pendientes;
  - facturas canceladas por archive;
  - convenios activos/incumplidos;
  - egresos aprobados/pagados/pendientes;
  - mantenimiento;
  - comercial/vacancia;
  - settlement de pagos a terceros.
- `OWNER`, `PROPERTY_ADMIN` autorizado y `ACCOUNTANT` ven la misma historia para el mismo owner y periodo.

## Fase 4 — Mantenimiento, vacancia, comercial y pagos a terceros
### Mantenimiento
- El owner define `maintenanceRoutingMode: PRIVATE | PLATFORM`.
- No hay fallback silencioso si eligió privados y no tiene proveedor privado activo.
- Flujo:
  - ticket;
  - asignación;
  - aceptación;
  - cotización;
  - aprobación/rechazo;
  - egreso;
  - pago;
  - settlement.
- Si falta proveedor privado, se bloquea la resolución automática y se crea tarea pendiente.

### Vacancia y comercial
- Archivar un expediente libera el inmueble y crea vacancia automáticamente.
- El flujo de vacancia crea:
  - movimiento;
  - `ActionTask`;
  - evento a n8n si está activo;
  - routing a agente `PRIVATE | PLATFORM`.
- Si se elige `PRIVATE` y no hay agente privado activo:
  - se bloquea la baja final;
  - se ofrece registrar/vincular agente privado o cambiar a plataforma.
- El agente asignado puede:
  - subir fotos del estado del inmueble;
  - registrar visitas, notas y seguimiento;
  - registrar comisión;
  - cerrar la vacancia con nuevo expediente tenant.

### Equipo y proveedores
- Sección única “Equipo y proveedores” con tres bloques:
  - administradores;
  - mantenimiento;
  - agentes inmobiliarios.
- En mantenimiento/agentes cada fila muestra:
  - origen `PLATFORM | PRIVATE`;
  - estado;
  - contacto;
  - vínculo;
  - acciones de vincular/desvincular.

### Settlement de egresos
- Los pagos a proveedores/agentes soportan:
  - parcial;
  - completo;
  - saldo pendiente;
  - disputa.
- Campos mínimos:
  - `approvedAmount`
  - `paidAmount`
  - `outstandingAmount`
  - `paymentSettlementStatus`
  - `paymentMethod`
  - `ownerConfirmationStatus`
  - `providerConfirmationStatus`
- El proveedor/agente debe confirmar si recibió pago completo, parcial o si hay diferencia.
- Si hay desacuerdo:
  - queda `DISPUTED`;
  - se crea tarea para owner/admin/accountant.
- Esto aplica tanto a mantenimiento como a comisiones comerciales.

## Fase 5 — UX, endurecimiento y salida a operación
### UX consolidada
- Navegación principal owner/admin:
  - Resumen
  - Inmuebles
  - Arrendatarios
  - Pendientes
  - Convenios
  - Equipo y proveedores
- No más pestañas principales de “Contratos” ni módulo de “Unidades”.
- El expediente del tenant es el centro de trabajo.
- El dashboard tenant se organiza por owner y expediente.
- El date picker de owner/accountant debe respetar:
  - mínimo válido por fecha de owner/property;
  - máximo mes actual;
  - sin fechas futuras ni previas fuera de rango.

### Hardening y QA
- QA por bloques:
  - seguridad/contextos;
  - tenant multi-owner;
  - alta integral;
  - archive y revocación de acceso;
  - cobranza SPEI/CEP;
  - convenios;
  - mantenimiento;
  - vacancia/comercial;
  - reportes;
  - consistencia numérica.
- Evidencia real requerida:
  - request/response;
  - logs;
  - queries SQL;
  - capturas UI;
  - verificación de exports.
- MFA asistido solo cuando el bloqueo real lo exija.
- n8n debe ser opcional y no bloquear operación local.

## APIs e interfaces públicas
- Mantener y ajustar:
  - `/auth/*`
  - `/owners/*`
  - `/permissions/*`
  - `/properties/*`
  - `/tenants/*`
  - `/leases/*`
  - `/payments/*`
  - `/agreements/*`
  - `/maintenance/*`
  - `/vacancies/*`
  - `/reports/*`
  - `/files/*`
- Nuevos/normalizados:
  - `/auth/contexts`
  - `/auth/select-context`
  - `/notifications/*`
  - `/tasks/*`
  - `/owner/team/*`
  - `/owner/preferences/*`
  - `/properties/delete-requests/*`
  - `/payments/proofs/*`
  - `/payments/cep-validation/*`
  - `/expenses/{id}/payments`
  - `/expenses/{id}/confirmations/owner`
  - `/expenses/{id}/confirmations/provider`
  - `/expenses/{id}/settlement`
  - `/tenants/{tenantProfileId}/archive`
  - `/tenants/{tenantProfileId}/contracts`
  - `/tenants/{tenantProfileId}/contract-document`

## Pruebas y escenarios
- Login con MFA y selección de contexto por roles multi-owner.
- Tenant con varios owners:
  - token base;
  - selección de owner;
  - acceso solo al owner elegido.
- Tenant con varios inmuebles en el mismo owner:
  - acceso a todos sus expedientes de ese owner.
- Alta integral tenant:
  - reutiliza cuenta si ya existe;
  - crea expediente nuevo;
  - ocupa inmueble;
  - falla si el inmueble ya está ocupado.
- Edición tenant:
  - no cambia inmueble.
- Archive tenant:
  - termina lease;
  - libera inmueble;
  - abre vacancia;
  - revoca acceso a ese owner si era el último expediente en ese owner;
  - si era el último owner activo, bloquea acceso total del tenant;
  - cancela factura abierta del periodo actual.
- SPEI/CEP:
  - validación correcta, incompleta y rechazada.
- Convenios:
  - evidencia, tipo, estado y reflejo en reportes.
- Mantenimiento:
  - routing private/platform;
  - ausencia de privado sin fallback;
  - aprobación;
  - settlement parcial/completo.
- Comercial:
  - vacancia;
  - agente;
  - fotos;
  - seguimiento;
  - comisión;
  - settlement.
- Resúmenes y reportes:
  - owner/admin/accountant cuentan la misma historia del mismo owner/periodo.

## Suposiciones y defaults
- Una sola cuenta tenant por email en toda la plataforma.
- Muchos expedientes por tenant.
- Un solo lease `ACTIVE` por inmueble.
- “Eliminar tenant” operativamente significa archivar expediente, no borrar historial.
- La revocación de acceso del tenant es por owner; si ya no quedan owners activos, pierde acceso total.
- El ingreso esperado se cancela al archivar si la factura del periodo sigue abierta.
- Toda aprobación sensible ocurre dentro de la app.
- n8n solo notifica o acompaña; nunca reemplaza la lógica principal.
