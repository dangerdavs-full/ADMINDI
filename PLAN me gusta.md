# ADMINDI — Plan Maestro Reacomodado

## Resumen
ADMINDI es una plataforma multi-tenant para administrar inmuebles, cobranza, operación, mantenimiento, vacancias y reportes contables, con `SUPER_ADMIN` como orquestador global, `OWNER` como dueño del portafolio, `PROPERTY_ADMIN` como operador delegado, `ACCOUNTANT` como lector financiero multi-owner, `TENANT` como arrendatario y `MAINTENANCE_PROVIDER` / `REAL_ESTATE_AGENT` como proveedores externos.

Base ya suficientemente encaminada en el repo:
- seguridad base con JWT RS256, MFA, refresh rotation, revocación y selector de contexto
- CRUD base de `OWNER`
- recuperación administrativa de cuenta
- separación entre `loginEmail` y datos de contacto del `OWNER`
- integración n8n por eventos operativos del `OWNER`

Lo que falta fijar para cerrar Fase 0 y Fase 1 como tú las quieres:
- política definitiva de purga total de `OWNER`
- centro de preferencias de notificación por evento/canal
- módulo `SUPER_ADMIN` para `MAINTENANCE_PROVIDER` de plataforma
- contrato rector entre sistema interno, n8n y validaciones financieras

## Arquitectura funcional del sistema
### 1. Seguridad, identidad y contexto
- Todos los roles usan login con JWT RS256.
- `SUPER_ADMIN`, `OWNER`, `PROPERTY_ADMIN` y `ACCOUNTANT` usan MFA obligatorio.
- Refresh token real con rotación, detección de reuse y revocación por `jti`.
- `BASE` token solo sirve para selección de contexto.
- `FULL` token sirve para operar dentro del contexto.
- `OWNER`, `PROPERTY_ADMIN`, `ACCOUNTANT`, `REAL_ESTATE_AGENT` y `MAINTENANCE_PROVIDER` pueden operar por contexto de dueño según corresponda.
- `SUPER_ADMIN` opera globalmente sin contexto de owner.
- `RLS` protege recursos con `owner_id`.
- Recovery de password/MFA/full recovery es interno, auditado y nunca debe salir por n8n.

### 2. Identidad vs contacto
- Toda entidad humana relevante tendrá separación entre identidad de acceso y datos de contacto.
- `loginEmail` sirve solo para autenticación.
- `contactEmail` y `contactPhone` sirven para comunicación operativa.
- n8n nunca debe identificar por email; debe identificar por `ownerId`, `providerId`, `tenantId` o `ticketId`.
- Cambiar datos de contacto no debe romper auth, MFA, refresh ni sesiones.
- Recovery no modifica contacto salvo que el operador lo haga explícitamente en otro flujo.

### 3. SUPER_ADMIN
- Crea `OWNER`.
- Actualiza datos de contacto del `OWNER`.
- Purga totalmente `OWNER` y todo su universo cuando decida que ya no debe existir.
- Recupera password, MFA o ambos.
- Crea otros `SUPER_ADMIN`.
- Lista usuarios por rol.
- Ve auditoría global.
- Crea `MAINTENANCE_PROVIDER` de plataforma.
- Administra estado, contacto y credenciales temporales de proveedores de plataforma.
- Más adelante podrá crear `REAL_ESTATE_AGENT` de plataforma con el mismo patrón, pero no es el foco inmediato.

### 4. OWNER
- Gestiona su portafolio completo.
- Crea inmuebles, arrendatarios, contratos y administradores.
- Puede usar proveedores de plataforma o proveedores propios.
- Puede modificar sus datos de contacto y su cuenta bancaria/CLABE para conciliación y validación de transferencias.
- Administra permisos finos de sus `PROPERTY_ADMIN`.
- Revisa tickets, vacancias, cobranza, convenios, reportes y facturación.
- Configura preferencias de notificación por evento/canal.
- Todo evento importante debe ser visible dentro del sistema aunque el canal externo esté apagado.

### 5. PROPERTY_ADMIN
- Ve el mismo universo del `OWNER`, pero filtrado por permisos.
- Puede crear inmuebles, inquilinos y expedientes si el dueño lo autorizó.
- No puede eliminar inmuebles directamente.
- Si quiere eliminar un inmueble, genera una solicitud al `OWNER`.
- El `OWNER` recibe notificación interna y opcional externa.
- La aprobación del borrado debe requerir reautenticación fuerte y MFA del `OWNER`.
- Puede ver tickets, historial, SPEI validados y operación diaria según plantilla.

### 6. ACCOUNTANT
- Es multi-owner.
- Entra a un contexto de dueño y ve todo el portafolio financiero de ese dueño.
- Solo lectura y exportación.
- Descarga `MonthlySummary`, `MonthlyLedger` y reporte de facturación.
- No ve operación sensible no financiera.

### 7. TENANT
- Ve su contrato, pagos, mantenimiento, convenio y desocupación.
- Puede pagar por Mercado Pago o reportar SPEI manual.
- Puede abrir tickets y solicitar convenio.
- Puede actualizar su contacto y perfil.

### 8. MAINTENANCE_PROVIDER
- Es un usuario externo creado por `SUPER_ADMIN` si es proveedor de plataforma, o por `OWNER` si es proveedor privado.
- Tiene login, contacto, credenciales temporales y estado.
- Recibe tickets, presupuestos, aprobaciones, completados y notificaciones de vacancia/desocupación según asignación.
- Su portal completo puede llegar en fase posterior, pero el modelo y el CRUD base deben existir ya.

## Contrato con n8n y notificaciones
### 1. Regla principal
- Todo lo que n8n notifique también debe existir como notificación interna en el sistema.
- El usuario decide por evento si además quiere canal externo.
- Esto evita spam y evita depender de n8n para enterarse de algo importante.
- `EMAIL` lo envía el backend. n8n solo se usa para `WHATSAPP`.

### 2. Centro de preferencias de notificación
- Cada usuario relevante tendrá switches por evento.
- Eventos iniciales:
  - resumen mensual
  - transferencia validada / SPEI validado
  - pago pendiente o vencido
  - ticket de mantenimiento nuevo
  - ticket actualizado
  - vacancia
  - desocupación
  - solicitud de borrado de inmueble
  - convenio aprobado/rechazado
- Canales:
  - en app
  - `EMAIL`
  - `WHATSAPP`
- El inbox interno siempre queda disponible.
- Los canales externos obedecen preferencia por evento.
- `OWNER_CREATED` es bootstrap interno/auditoría y no aparece en preferencias del usuario.

### 3. Eventos a n8n
- `OWNER_CONTACT_UPDATED`
- `OWNER_PURGED`
- `MAINTENANCE_PROVIDER_CREATED`
- `MAINTENANCE_PROVIDER_CONTACT_UPDATED`
- `MAINTENANCE_PROVIDER_DEACTIVATED` o `PURGED`
- `PAYMENT_VALIDATED`
- `MAINTENANCE_TICKET_CREATED`
- `MAINTENANCE_TICKET_ASSIGNED`
- `VACANCY_CREATED`
- `VACATE_REQUEST_CREATED`
- `MONTHLY_SUMMARY_READY`
- `PROPERTY_DELETE_REQUESTED`
- Todos los eventos son no bloqueantes.
- Si n8n falla, la operación local se mantiene.
- Se audita `SENT`, `SKIPPED` o `FAILED`.
- n8n solo entrega `WHATSAPP`; el correo sale desde backend.

### 4. Eventos prohibidos hacia n8n
- password reset
- force-reset
- MFA reset
- full recovery
- cambio de contraseña
- cualquier token, secreto o credencial

### 5. “Sembrado” automático de proveedores de plataforma
- `SUPER_ADMIN` crea una sola vez los `MAINTENANCE_PROVIDER` de plataforma.
- Cuando se crea un `OWNER`, el sistema no duplica usuarios; crea asociaciones automáticas entre el nuevo `OWNER` y los proveedores globales activos.
- Eso deja “disponibles” a los proveedores de plataforma desde el día uno para ese owner.
- El `OWNER` puede:
  - usarlos
  - desactivarlos para su contexto
  - añadir proveedores propios
- Lo mismo puede modelarse después para `REAL_ESTATE_AGENT`.

## Finanzas, Mercado Pago y Banxico
### 1. Mercado Pago
- Única pasarela real de v1.
- El `TENANT` paga desde portal.
- El sistema registra `Payment`, referencia, método, fecha y conciliación.
- El `OWNER` y `ACCOUNTANT` reflejan el resultado en cobranza y reportes.

### 2. SPEI manual + validación Banxico
- El `OWNER` define su cuenta bancaria/CLABE oficial de recepción.
- El `TENANT` puede subir comprobante SPEI o enviarlo por el canal definido.
- El sistema extrae monto, fecha, referencia y cuenta destino.
- Se valida por adapter con Banxico/CEP.
- Estados mínimos:
  - recibido
  - pendiente validación
  - validado
  - rechazado
- El `OWNER` y el `PROPERTY_ADMIN` con permiso ven:
  - qué SPEI ya fue validado
  - cuál no
  - motivo de rechazo
- Cuando el SPEI se valida:
  - se actualiza el ledger
  - se notifica al `OWNER` según preferencias
  - se puede notificar al `PROPERTY_ADMIN`
  - se puede disparar evento a n8n

### 3. Reportes financieros
- `MonthlyLedger` por contrato/inquilino.
- `MonthlySummary` por inmueble y owner.
- Vista caja + compromisos.
- Salidas:
  - PDF ejecutivo
  - Excel contable con hojas separadas
- `OWNER` y `ACCOUNTANT` exportan.
- `PROPERTY_ADMIN` solo con `REPORT_EXPORT`.

## APIs e interfaces principales
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

Contratos clave:
- `JWT`: `sub`, `type`, `ownerId`, `role`, `permissions`, `providerType`, `jti`, `iat`, `exp`
- `OWNER`: `loginEmail`, `contactEmail`, `contactPhone`, `contactCountryCode`
- `MAINTENANCE_PROVIDER`: mismo patrón de identidad/contacto
- preferencias de notificación por evento y canal
- eventos a n8n con identificadores estables, nunca por email

## Validaciones y reglas críticas
- MFA obligatorio para roles críticos.
- Reautenticación obligatoria para:
  - purge de `OWNER`
  - cambios sensibles de permisos
  - borrado de expediente
  - autorización de borrado de inmueble
  - exportaciones sensibles si así se decide
- Rate limiting por endpoint.
- Audit log para toda escritura.
- Content type estricto.
- CSP, headers y CORS correctos.
- RLS para recursos de owner.
- `SUPER_ADMIN` fuera de RLS o con bypass administrativo controlado.
- Ninguna automatización debe salir si el usuario desactivó ese evento/canal.
- Todo debe seguir visible dentro del sistema.

## Política final de borrado
- Para `OWNER`, la instrucción actual es purga total.
- La purga borra:
  - owner
  - propiedades
  - inmuebles
  - arrendatarios
  - contratos
  - pagos y vínculos del owner
  - tickets y presupuestos ligados
  - configuraciones
  - usuarios subordinados
  - asociaciones a proveedores
  - auditoría ligada al owner
  - preferencias y eventos asociados
- También debe mandar `OWNER_PURGED` a n8n para que n8n lo elimine de su lado.
- Esto debe ser irreversible y visible con advertencia extrema en `SUPER_ADMIN`.

## Qué falta realmente para cerrar Fase 0 y Fase 1 como tú quieres
- cerrar la política de purga total del `OWNER` y reemplazar el soft delete donde hoy siga vivo
- completar `SUPER_ADMIN` como módulo rector:
  - users por rol
  - CRUD completo de dueños
  - creación de `MAINTENANCE_PROVIDER`
  - auditoría usable
- modelar y construir preferencias de notificación por evento/canal
- dejar contrato definitivo sistema interno + n8n
- asegurar que `PROPERTY_ADMIN` no elimina inmuebles directo y que el borrado pasa por autorización del `OWNER`
- dejar Banxico/CEP claramente dentro del flujo de validación SPEI
- dejar listas las bases para `MAINTENANCE_PROVIDER` de plataforma sembrado automáticamente en owners

## Siguiente orden de trabajo recomendado
1. cerrar definitivamente `SUPER_ADMIN + OWNER + purge + notification preferences + n8n`
2. cerrar `MAINTENANCE_PROVIDER` de plataforma en capa administrativa
3. cerrar flujo `PROPERTY_ADMIN solicita borrar inmueble / OWNER autoriza`
4. después entrar a Fase 2 y 3 con inmuebles, contratos, cobranza y reportes ya sobre arquitectura estable

## Plan de pruebas
- login con MFA de `SUPER_ADMIN`, `OWNER`, `PROPERTY_ADMIN`, `ACCOUNTANT`
- refresh rotation y revocación
- create/update/purge de `OWNER`
- create/update de `MAINTENANCE_PROVIDER`
- recovery interno sin tocar n8n
- `OWNER_CONTACT_UPDATED`, `OWNER_PURGED`
- preferencia activada: interno + externo
- preferencia apagada: solo interno
- validación SPEI con Banxico/CEP
- solicitud de borrado de inmueble por `PROPERTY_ADMIN`
- autorización o rechazo por `OWNER`
- seeded providers de plataforma disponibles al crear owner
- n8n apagado
- n8n fallando
- Mercado Pago reflejado en cobranza y reportes

## Asunciones fijas
- n8n complementa, no reemplaza al sistema.
- Todo evento importante existe en inbox interno.
- `SUPER_ADMIN` es quien crea `OWNER` y `MAINTENANCE_PROVIDER` de plataforma.
- Los proveedores de plataforma se asocian automáticamente a nuevos owners sin duplicar cuentas.
- El borrado total del `OWNER` es deliberado e irreversible.
- Recovery y seguridad nunca se mezclan con n8n.
