# ADMINDI — Plan Final Maestro

## Resumen
ADMINDI será una plataforma inmobiliaria multi-tenant que funciona completa por sí sola y usa n8n solo como adapter opcional de salida para WhatsApp. Todo flujo crítico debe poder operarse dentro del sistema web, con trazabilidad, aprobaciones, auditoría y preferencias de notificación por usuario y por evento. El correo lo envía el backend vía SMTP configurable.

La base ya encaminada en Fase 0/1 es seguridad, JWT, MFA, refresh, recovery, `SUPER_ADMIN`, `OWNER`, permisos y separación entre identidad de acceso y datos de contacto. El cierre correcto ahora es ordenar la arquitectura final para que lo siguiente no se construya encima de ambigüedades.

## Principios rectores
- El sistema debe funcionar con n8n o sin n8n.
- Toda notificación externa debe tener equivalente dentro de la app.
- Todo usuario puede activar o desactivar canales externos por tipo de evento.
- `loginEmail` sirve para autenticación; `contactEmail/contactPhone` sirven para operación.
- n8n nunca identifica por email; siempre por IDs internos.
- `SUPER_ADMIN` gobierna dueños, seguridad, proveedores de plataforma y compatibilidad de integraciones.
- `OWNER` gobierna su negocio completo.
- `PROPERTY_ADMIN` opera el negocio diario bajo permisos finos.
- `ACCOUNTANT`, `PROPERTY_ADMIN` y `TENANT` pueden existir en múltiples contextos; el login debe resolver eso de forma limpia.
- La automatización principal debe vivir en la app; n8n amplifica, no reemplaza.

## Arquitectura de seguridad y contexto
- JWT RS256 con `access token` corto y `refresh token` rotatorio.
- Revocación por `jti` y detección de reuse en Redis.
- MFA obligatorio para `SUPER_ADMIN`, `OWNER`, `PROPERTY_ADMIN`, `ACCOUNTANT`.
- MFA recomendado para `TENANT`, `REAL_ESTATE_AGENT`, `MAINTENANCE_PROVIDER`.
- Selector de contexto para cualquier usuario con más de un contexto activo.
- `SUPER_ADMIN` no usa contexto de owner.
- `OWNER` entra directo a su owner.
- `PROPERTY_ADMIN` y `ACCOUNTANT` eligen owner activo.
- `TENANT` puede elegir contexto si tiene más de un lease activo o más de un owner relacionado.
- `REAL_ESTATE_AGENT` y `MAINTENANCE_PROVIDER` eligen contexto si trabajan para varios owners.
- RLS en PostgreSQL para todo recurso con `owner_id`.
- Reautenticación + MFA para purgas, cambios de permisos, exportaciones sensibles, recovery administrativa y autorizaciones destructivas.

## Modelo de identidad y contacto
- Toda cuenta humana relevante tendrá `loginEmail`.
- Toda cuenta operativa tendrá además `contactEmail`, `contactPhone`, `contactCountryCode`.
- Cambiar contacto no cambia credenciales.
- Recovery de cuenta no dispara n8n.
- Los eventos operativos sí usan contacto y preferencias.

## Módulo SUPER_ADMIN
- Dashboard global con métricas de plataforma.
- CRUD completo de `OWNER`.
- Alta de `OWNER` con `loginEmail`, `contactEmail`, `contactPhone`, `tempPassword`, `mustChangePassword=true`.
- Update de contacto del `OWNER`.
- Purga total e irreversible del `OWNER` y de todo su universo.
- Recovery administrativa: reset password, reset MFA, full recovery.
- Alta de otros `SUPER_ADMIN`.
- Listado global por rol.
- Auditoría global.
- Configuración global de Mercado Pago, Banxico adapter, n8n, S3 y parámetros.
- CRUD base de `MAINTENANCE_PROVIDER` de plataforma.
- CRUD base posterior de `REAL_ESTATE_AGENT` de plataforma.
- Al crear un `OWNER`, el sistema debe asociar automáticamente los proveedores de plataforma activos a ese owner como disponibles, sin duplicar cuentas.

## Módulo OWNER
- Panel de resumen con métricas, estados de inmuebles y cola de pendientes.
- Centro de pendientes del `OWNER` dentro de la app.
- La cola de pendientes debe incluir:
  - tickets de mantenimiento abiertos o con presupuesto por aprobar
  - solicitudes de convenio
  - pagos manuales/SPEI por validar
  - solicitudes de desocupación
  - solicitudes de borrado de inmueble iniciadas por `PROPERTY_ADMIN`
  - vacancias sin atender
- El `OWNER` puede gestionar todo el portafolio.
- Crea inmuebles, arrendatarios, contratos, administradores y contadores.
- Puede usar proveedores de plataforma, propios o mixtos.
- Puede modificar sus datos de contacto.
- Puede registrar cuenta CLABE/cuenta receptora oficial para validar transferencias.
- Puede administrar permisos finos.
- Puede configurar preferencias de notificación por evento y canal.

## Módulo PROPERTY_ADMIN
- Opera el mismo universo del `OWNER`, filtrado por permisos.
- Puede crear inmuebles, contratos, arrendatarios y documentos si tiene permiso.
- No puede eliminar inmuebles directamente.
- Si quiere eliminar un inmueble, crea una `solicitud de eliminación`.
- El `OWNER` recibe una tarea pendiente interna y, si así lo quiere, una notificación externa.
- El `OWNER` debe autorizar con reauth + MFA.
- Puede ver pagos, estado de validación SPEI, tickets, historial, vacancias y tareas operativas según permisos.

## Módulo ACCOUNTANT
- Multi-owner con selector de contexto.
- Dentro del owner activo ve todo el portafolio financiero.
- Solo lectura y exportación.
- Ve `MonthlyLedger`, `MonthlySummary`, historial de pagos y reporte de facturación.
- No ve detalles operativos innecesarios.

## Módulo TENANT
- Debe funcionar completo desde web, sin depender de n8n.
- Puede pagar por Mercado Pago.
- Puede subir comprobante de transferencia SPEI desde la app.
- Si el sistema no logra validar automáticamente el comprobante, debe pedirle los datos faltantes para intentar obtener/validar el CEP de Banxico.
- Datos faltantes posibles: fecha, monto exacto, clave de rastreo, banco emisor, cuenta receptora, referencia.
- El sistema reintenta validación CEP con esos datos.
- El `TENANT` puede ver estado del comprobante: recibido, pendiente, validado, rechazado.
- Puede crear tickets de mantenimiento desde web.
- Puede ver tickets abiertos e historial.
- Puede solicitar convenio.
- Puede iniciar proceso de desocupación.
- Puede modificar sus datos de contacto.
- Puede activar o desactivar notificaciones externas.

## Módulo MAINTENANCE_PROVIDER
- Usuario base creado por `SUPER_ADMIN` si es proveedor de plataforma, o por `OWNER` si es privado.
- Tiene `loginEmail` y contacto separados.
- Recibe credenciales temporales y `mustChangePassword=true`.
- Si tiene notificaciones externas apagadas, entra al sistema y ve sus tickets pendientes.
- Ve tickets asignados, presupuestos, estados, historial y pagos pendientes.
- Puede aceptar, rechazar, presupuestar, marcar avances y completar.
- Puede modificar sus datos de contacto.
- Puede configurar preferencias de notificación.

## Módulo REAL_ESTATE_AGENT
- Modelado desde v1 con identidad, contacto, asignación por owner y estados de vacancia.
- El sistema debe estar listo para mostrarle vacancias asignadas y su trabajo pendiente dentro de la app.
- Las notificaciones externas son opcionales.
- El portal completo puede esperar, pero el flujo operativo debe quedar pensado desde ahora.

## Notificaciones y automatización
- Crear un sistema interno de `Notification + ActionTask`.
- `Notification` sirve para informar.
- `ActionTask` sirve para cosas que requieren atención o aprobación.
- Todo evento importante genera al menos una `Notification`.
- Todo evento que requiere decisión genera además una `ActionTask`.
- Las `ActionTask` son visibles en la bandeja del usuario y en dashboards por rol.
- El backend resuelve el mismo evento de dominio y decide `EMAIL` o `WHATSAPP` según preferencia y canal.
- Eventos configurables inicialmente:
  - resumen mensual
  - pago validado
  - pago pendiente/vencido
  - comprobante SPEI pendiente o rechazado
  - ticket nuevo
  - ticket actualizado
  - ticket asignado
  - vacancia creada
  - desocupación iniciada
  - convenio solicitado
  - convenio aprobado/rechazado
  - solicitud de borrado de inmueble
  - recordatorios operativos
- Canales:
  - en app obligatorio
  - `EMAIL` opcional, enviado por backend
  - `WHATSAPP` opcional, vía adapter n8n

## Integración con n8n
- n8n es un adapter de salida para `WHATSAPP`, no la lógica principal.
- `EMAIL` se envía desde backend y no pasa por n8n.
- Eventos iniciales hacia n8n:
  - `OWNER_CONTACT_UPDATED`
  - `OWNER_PURGED`
  - `MAINTENANCE_PROVIDER_CREATED`
  - `MAINTENANCE_PROVIDER_CONTACT_UPDATED`
  - `PAYMENT_VALIDATED`
  - `MAINTENANCE_TICKET_CREATED`
  - `MAINTENANCE_TICKET_ASSIGNED`
  - `VACATE_REQUEST_CREATED`
  - `MONTHLY_SUMMARY_READY`
  - `PROPERTY_DELETE_REQUESTED`
- `OWNER_CREATED` queda como evento interno de onboarding/auditoría y no es configurable para usuario.
- Eventos prohibidos hacia n8n:
  - password reset
  - MFA reset
  - full recovery
  - cambio de contraseña
  - cualquier credencial, OTP, token o secreto
- Auditoría de integración:
  - `SENT`
  - `SKIPPED`
  - `FAILED`
- La operación local jamás se bloquea por un fallo de n8n.

## Integración financiera
### Mercado Pago
- Única pasarela real de v1.
- `TENANT` paga desde portal web.
- El resultado actualiza cobranza, ledger y reportes.

### SPEI y Banxico/CEP
- El sistema soporta carga manual de comprobante desde la web.
- Puede haber también canal externo por n8n/WhatsApp, pero no es obligatorio.
- Flujo:
  - tenant sube comprobante
  - sistema intenta extracción y conciliación
  - si faltan datos, solicita información complementaria dentro de la app
  - reintenta validación CEP Banxico
  - marca validado, pendiente o rechazado
- `OWNER` y `PROPERTY_ADMIN` con permiso ven:
  - qué transferencia ya fue validada
  - cuál sigue pendiente
  - cuál fue rechazada y por qué
- Una validación exitosa dispara actualización financiera y notificaciones según preferencias.

## Política de eliminación
- `SUPER_ADMIN` puede purgar completamente un `OWNER`.
- La purga elimina:
  - owner
  - propiedades
  - inmuebles
  - leases
  - tenants
  - admins
  - accountants
  - configuraciones
  - asociaciones con proveedores
  - tickets y operación asociada
  - documentos y archivos asociados
  - auditoría asociada al owner según tu decisión actual
- También se emite `OWNER_PURGED` a n8n para que lo elimine de su lado.
- La purga requiere:
  - reautenticación del `SUPER_ADMIN`
  - MFA
  - advertencia extrema
  - ejecución confirmada e irreversible
- Para `PROPERTY_ADMIN`, el borrado de inmueble sigue siendo solicitud y aprobación, no acción directa.

## APIs e interfaces públicas
- Mantener:
  - `/auth/*`
  - `/admin/*`
  - `/owners/*`
  - `/permissions/*`
  - `/properties/*`
  - sin módulo `/units/*` en el producto final
  - `/leases/*`
  - `/tenants/*`
  - `/payments/*`
  - `/agreements/*`
  - `/maintenance/*`
  - `/vacancies/*`
  - `/reports/*`
  - `/files/*`
  - `/integrations/mercadopago/*`
  - `/integrations/whatsapp/webhook`
  - `/integrations/n8n/events`
- Añadir/normalizar:
  - `/notifications/*`
  - `/tasks/*`
  - `/admin/platform-providers/*`
  - `/owner/preferences/*`
  - `/payments/proofs/*`
  - `/payments/cep-validation/*`
  - `/properties/delete-requests/*`

## Entidades y contratos que deben existir
- `User`
- `OwnerMembership`
- `PermissionTemplate`
- `PermissionGrant`
- `NotificationPreference`
- `Notification`
- `ActionTask`
- `MaintenanceContact`
- `MaintenanceTicket`
- `MaintenanceBudget`
- `Vacancy`
- `VacateRequest`
- `Payment`
- `PaymentAllocation`
- `MonthlyLedger`
- `MonthlySummary`
- `ActionToken`
- `WebhookReceipt`
- `OutboundEvent`
- `RefreshTokenSession`
- `PropertyDeleteRequest`
- `TransferProofSubmission`
- `CepValidationAttempt`

## Qué debe quedar completamente cerrado en Fase 0 y 1
- seguridad base estable
- JWT + MFA + refresh + revocación
- recovery administrativa sin n8n
- `SUPER_ADMIN` operativo real
- CRUD y purga de `OWNER`
- separación login/contacto
- siembra automática de proveedores de plataforma para nuevos owners
- preferencias de notificación base
- infraestructura de notificación en app + `EMAIL` backend + `WHATSAPP` vía n8n
- `MAINTENANCE_PROVIDER` administrativo base
- colas de pendientes del `OWNER`

## Qué sigue después de cerrar esas bases
- Fase 2:
  - inmuebles
  - inmuebles
  - contratos
  - expedientes
  - flujo de solicitud/autorización de borrado
- Fase 3:
  - cobranza
  - SPEI + Banxico/CEP
  - Mercado Pago
  - ledger y summary
  - portal contador
- Fase 4:
  - portal tenant completo
  - tickets
  - desocupación
  - vacancias
  - operación de mantenimiento y agentes
- Fase 5:
  - endurecimiento
  - E2E
  - producción

## Plan de pruebas
- login MFA por roles críticos
- refresh rotation y revoke
- create/update/purge de `OWNER`
- create/update de `MAINTENANCE_PROVIDER`
- siembra de proveedores de plataforma al crear owner
- update de contacto sin tocar `loginEmail`
- preferencias activadas y desactivadas por evento
- bandeja interna visible con n8n apagado
- `TENANT` paga por Mercado Pago
- `TENANT` sube comprobante SPEI
- sistema pide datos faltantes para CEP cuando no puede validar
- validación CEP correcta e incorrecta
- ticket creado por tenant y visible para owner/admin/proveedor aun con notificaciones externas apagadas
- solicitud de borrado por `PROPERTY_ADMIN`
- autorización del `OWNER`
- n8n apagado
- n8n fallando

## Asunciones fijas
- La app debe poder operar completamente sin n8n.
- n8n es opcional y controlado por preferencias por evento.
- `SUPER_ADMIN` crea dueños y proveedores de plataforma.
- Los proveedores de plataforma se asocian automáticamente a cada nuevo owner.
- `OWNER` siempre tiene cola de pendientes dentro de la plataforma.
- `TENANT` puede operar pagos, comprobantes y tickets completamente desde web.
- `PROPERTY_ADMIN`, `ACCOUNTANT` y `TENANT` pueden manejar múltiples contextos.
- Recovery y seguridad nunca salen a n8n.
