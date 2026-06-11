# Plantillas de WhatsApp Business (Twilio Content Template Builder)

**Propósito**: registrar en Twilio / Meta las plantillas pre-aprobadas que la plataforma ADMINDI necesita para mensajes **iniciados por la plataforma** fuera de la ventana de 24h de conversación (obligatorio por Meta Business Platform).

**Quién las da de alta**: el administrador del proyecto (tú) en el portal de Twilio, pestaña **Content Template Builder** → **Create new template**.

**Tiempo estimado de aprobación por Meta**: entre 1 y 3 días hábiles por plantilla. Hazlo en paralelo para ganar tiempo.

**URL directa**: <https://console.twilio.com/us1/develop/sms/content-template-builder>

---

## Estado actual (abril 2026)

**Resumen rápido del registro de SIDs (ver `application.yml → twilio.templates`):**

| Bloque | Estado | Total |
|---|---|---|
| Fase 1 (welcome, recordatorios, reportes) | ✅ Aprobadas por Meta y cargadas | 11 de 12 |
| Fase 1 restante: `account-activation` | ⏳ Pendiente de aprobación Meta | 1 |
| Fase 2 (agentes inmobiliarios y proveedores de mantenimiento) | ✅ Aprobadas el 19-abr-2026 y cargadas | 23 |
| **Total aprobadas hoy** | | **34 / 34 eventos mapeados · 33 / 34 con SID** |

> ⚠️ La única plantilla todavía sin SID en default es `admindi_account_activation_v1`. Mientras Meta la aprueba, el backend cae a body libre (solo llega dentro de la ventana 24h). El email de activación sigue saliendo siempre con el link completo, así que la experiencia del usuario está protegida.

**Feature flag global**: `twilio.templates.enabled=true` por default (`application.yml`). Si algún entorno (staging, sandbox, dry-run) necesita apagarlo, se sobrescribe con `TWILIO_TEMPLATES_ENABLED=false`.

---

## Smoke test de plantillas (SUPER_ADMIN)

El backend expone un panel **solo para SUPER_ADMIN** en:

- `GET /api/admin/notifications/templates` — lista las 34 plantillas con estado (habilitada, SID redactado `HXXX…YYY`, conteo de slots, variables de ejemplo y descripción humana).
- `POST /api/admin/notifications/templates/{eventType}/render` — dry-run. No envía nada, pero devuelve:
  - `contentSid` completo (solo visible para SUPER_ADMIN ya autenticado — lo necesita para cruzar contra Twilio Console).
  - `templateVariables` que el dispatcher enviaría (usa las de `TemplateSamples` por default, acepta `templateVariables` por body para override).
  - `fallbackBody`: lo que iría al canal WhatsApp si la plantilla no estuviera configurada (body libre, solo válido en ventana 24h).
- `POST /api/admin/notifications/templates/{eventType}/send-test` — envío real al propio SUPER_ADMIN autenticado. Requiere **reauth (password + MFA)**, fuerza los 3 canales (IN_APP, EMAIL, WHATSAPP) aunque el usuario tenga opt-out, y queda en `audit_events` como `ADMIN_WHATSAPP_SMOKE_SEND`.

### Controles de seguridad del smoke test

- **AuthZ**: `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- **Reauth**: obligatorio en `send-test` (misma política que el panel SUPERADMIN de usuarios).
- **Rate limit por usuario**: máximo **10 envíos por hora** por cada SUPER_ADMIN (bucket in-memory en `AdminNotificationTestController`). La interceptora global de rate limit por IP sigue aplicando también.
- **Destinatario cerrado**: solo el propio SUPER_ADMIN. No se permite disparar a terceros, ni a un número arbitrario.
- **SID redactado por default**: el listado muestra `HXXX…YYY`; el SID completo solo se devuelve en la respuesta del dry-run.
- **Auditoría**: cada acción persiste `ADMIN_WHATSAPP_SMOKE_{RENDER|SEND|RATE_LIMIT}` con actor, plantilla y metadata.

### Flujo recomendado de verificación después de aprobar una plantilla en Meta

1. Pegar el SID en `application.yml` (o su env var) y redeploy.
2. `GET /api/admin/notifications/templates` → confirmar que `hasSid=true` y `redactedSid` aparece para el eventType.
3. `POST .../render` con o sin `templateVariables` personalizados → revisar que el cuerpo preview sea exactamente lo que Meta aprobó.
4. `POST .../send-test` con password + MFA → verificar que llegue la plantilla al WhatsApp del SUPER_ADMIN. Si Twilio responde error (p.ej. mismatch de slots), corregir el sample en `TemplateSamples.java`.

---

## Decisiones de seguridad y UX (léelo antes de empezar)

1. **Las plantillas NO contienen contraseñas temporales**. Aunque hoy el backend las manda por email, en WhatsApp se omiten por dos razones:
   - Meta rechaza plantillas que parezcan compartir credenciales en texto claro.
   - WhatsApp no es el canal seguro para credenciales; el email sí lo es (y seguirá siendo el canal primario de bienvenida).
   - El mensaje de WhatsApp siempre pide al usuario revisar su email para obtener acceso.

2. **Idioma**: `es` (español genérico). Meta trata `es` y `es_MX` como dos entidades distintas; si registras `es` cubres todos los hispanohablantes sin duplicar plantillas.

3. **Categoría**: todas van como `UTILITY`. Esta categoría está pensada para confirmaciones, recordatorios y actualizaciones transaccionales, que es exactamente lo que hacemos. `AUTHENTICATION` solo aplica a OTP de un solo uso y `MARKETING` a promociones (más cara y con opt-out obligatorio de Meta).

4. **Formato de variables**: Twilio usa `{{1}}`, `{{2}}`, `{{3}}`… La primera variable debe aparecer de corrido, no al inicio ni al final del mensaje (requisito Meta desde 2024).

5. **Botones**: salvo excepciones marcadas, ninguna plantilla incluye botones (evitamos depender del feature "call to action" que Twilio cobra aparte).

6. **Nombre de las plantillas (friendly name en Twilio)**: sigue el patrón `admindi_<evento>_v1` para que sea fácil versionar. Si Meta pide cambios, registramos `v2` sin borrar `v1`.

---

## Paso a paso genérico (repite para cada plantilla)

1. Entra a <https://console.twilio.com/us1/develop/sms/content-template-builder>.
2. Click **Create new content template**.
3. **Content name**: usa el `friendly name` indicado en cada plantilla (snake_case, minúsculas).
4. **Language**: selecciona `Spanish - es` (NO `Spanish (Mexico) - es_MX`).
5. **Content type**: selecciona `Text`.
6. **Body**: copia tal cual el bloque "Body" indicado (respeta acentos, saltos de línea y espacios).
7. **Variables**: Twilio detecta automáticamente los `{{1}}`, `{{2}}`…  
   En la sección **Sample values** pega los valores de muestra indicados en cada plantilla (Meta los usa para aprobar).
8. **Category**: selecciona `Utility`.
9. Click **Submit for WhatsApp approval**.
10. Twilio te devolverá un `Content SID` con el patrón `HXxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`.  
    **Copia ese Content SID y pégalo en la tabla final de este documento** (sección "Registro de Content SIDs"). El backend lo cargará desde `application-secrets.yml` cuando migremos a envío por plantilla.
11. Espera el veredicto de Meta (el portal mostrará `Approved`, `Rejected` o `Pending`).

---

## Plantilla 1 — `admindi_owner_welcome_v2` ✅ aprobada

**Evento backend**: `OWNER_WELCOME` (disparado en `OwnerService.createOwner`).

**Destinatario**: el nuevo dueño recién dado de alta por el administrador.

**Categoría**: `Utility`.  
**Language**: `es`.  
**Friendly name aprobado**: `admindi_owner_welcome_v2`.  
**Content SID**: `HXddc8ce4292e4933e107e30c254462590`.

**Body final aprobado por Meta (17/04/2026)**:

```
Hola {{1}}, tu cuenta de ADMINDI fue activada para administrar tus inmuebles.

Correo registrado: {{2}}.
Para completar tu acceso, revisa el correo que enviamos con las instrucciones de ingreso.

Portal: {{3}}

Desde ahí podrás registrar inmuebles, configurar tu cuenta bancaria y administrar tus notificaciones.
```

**Variables**:

| # | Nombre lógico | Ejemplo para aprobación |
|---|---|---|
| {{1}} | Nombre del dueño | `Israel` |
| {{2}} | Identificador de login (username) | `israel-2026` |
| {{3}} | URL del portal | `https://app.admindi.mx` |

**Nota**: la v1 original decía "Tu correo de acceso es {{2}}. Por seguridad, la contraseña temporal fue enviada por email." Meta pidió suavizar la referencia a credenciales para evitar marcado como phishing; la v2 aprobada solo confirma el alta y redirige al email.

> **Decisión V50 — plantilla Meta se mantiene sin cambios**
> El body aprobado por Meta dice literal "Correo registrado: {{2}}" y no se re-envía a aprobación: cambiar el texto implica nueva revisión, riesgo de rechazo y ventana sin plantilla disponible.
> El backend pasa al slot `{{2}}` el `username` del dueño (identificador de login canónico). El usuario recibe el WhatsApp con el rótulo "Correo registrado" pero el valor que ve es su username — es la forma acordada para no tocar Meta y que siga sirviendo como credencial de acceso.

---

## Plantilla 2 — `admindi_tenant_welcome_v2` ✅ aprobada

**Evento backend**: `TENANT_WELCOME` (disparado en `TenantService` cuando se abre expediente nuevo).

**Destinatario**: el arrendatario recién dado de alta.

**Categoría**: `Utility`.  
**Language**: `es`.  
**Friendly name aprobado**: `admindi_tenant_welcome_v2`.  
**Content SID**: `HX07cb001df7b63655ab3fb67622cfe79e`.

**Body final aprobado por Meta (17/04/2026)**:

```
Hola {{1}}, tu expediente de arrendamiento en ADMINDI fue activado para el inmueble {{2}}.

Correo registrado: {{3}}.
Para completar tu acceso, revisa el correo que enviamos con las instrucciones de ingreso.

Portal: {{4}}

Desde ahí podrás consultar comprobantes, registrar tu pago y recibir recordatorios relacionados con tu renta.
```

**Variables**:

| # | Nombre lógico | Ejemplo para aprobación |
|---|---|---|
| {{1}} | Nombre del arrendatario | `David` |
| {{2}} | Nombre del inmueble | `Depto Reforma 201` |
| {{3}} | Identificador de login (username) | `david-2026` |
| {{4}} | URL del portal | `https://app.admindi.mx` |

**Nota**: misma racionalidad que owner_welcome — la v2 aprobada no menciona contraseña temporal directamente; redirige al email para credenciales.

> **Decisión V50 — plantilla Meta se mantiene sin cambios**
> El body aprobado dice "Correo registrado: {{3}}". El backend pasa al slot `{{3}}` el `username` del arrendatario; el rótulo no se re-envía a aprobación para no perder la plantilla activa.

---

## Plantilla 3 — `admindi_owner_profile_updated_v3` ✅ aprobada

**Evento backend**: `OWNER_PROFILE_UPDATED` (disparado en `OwnerProfileController.updateProfile` tras reauth + cambio de CLABE / contacto).

**Destinatario**: el propio dueño (notificación de auditoría para que detecte cambios no autorizados).

**Categoría**: `Utility`.  
**Language**: `es`.  
**Friendly name aprobado**: `admindi_owner_profile_updated_v3`.  
**Content SID**: `HX4eb0c265334423af2118f7a247f83a2a`.

**Body final aprobado por Meta (17/04/2026, tras 2 rechazos)**:

```
Hola {{1}}, confirmamos que el {{2}} se actualizó información de tu perfil en ADMINDI.

Campos modificados: {{3}}.

Este mensaje corresponde al cambio registrado en tu cuenta. Puedes revisar el detalle en {{4}} cuando lo necesites.
```

**Variables**:

| # | Nombre lógico | Ejemplo para aprobación |
|---|---|---|
| {{1}} | Nombre del dueño | `Israel` |
| {{2}} | Fecha + hora del cambio | `17/04/2026 10:15` |
| {{3}} | Lista de campos | `CLABE, teléfono de contacto` |
| {{4}} | URL del portal | `https://app.admindi.mx` |

**Nota**: las v1/v2 fueron rechazadas por Meta por lenguaje de alerta ("detectamos…", "si no reconoces este cambio…"), que tagea la plantilla como quasi-autenticación. La v3 aprobada es una confirmación neutral. **El comportamiento de auditoría del backend NO se pierde**: el dueño sigue viendo el cambio en `audit_events` con ubicación/IP, y si detecta algo raro usa el flujo existente de reset de contraseña + contacto con soporte. El WhatsApp deja de ser el canal de alerta — ahora solo es canal de confirmación.

---

## Plantilla 4 — `admindi_payment_reminder_5d_v1`

**Evento backend**: `TENANT_PAYMENT_REMINDER_5D` (cron diario 08:00 CDMX, `PaymentReminderScheduler`).

**Destinatario**: arrendatario con factura vigente a 5 días del vencimiento.

**Categoría**: `Utility`.  
**Language**: `es`.  
**Friendly name**: `admindi_payment_reminder_5d_v1`.

**Body**:

```
Hola {{1}}, te recordamos que tu renta de {{2}} por ${{3}} vence el {{4}} (faltan 5 días).

Para pagar por SPEI usa estos datos:
Banco: {{5}}
CLABE: {{6}}

Si ya pagaste, puedes ignorar este mensaje.
```

**Variables**:

| # | Nombre lógico | Ejemplo para aprobación |
|---|---|---|
| {{1}} | Nombre del arrendatario | `David` |
| {{2}} | Nombre del inmueble | `Depto Reforma 201` |
| {{3}} | Monto total con separadores | `18,500.00` |
| {{4}} | Fecha de vencimiento | `22/04/2026` |
| {{5}} | Nombre del banco del dueño | `BBVA` |
| {{6}} | CLABE del dueño | `012180001234567890` |

---

## Plantilla 5 — `admindi_payment_reminder_3d_v1`

Idéntica a la 5D pero con `3 días` en el texto.  
**Friendly name**: `admindi_payment_reminder_3d_v1`.

**Body**:

```
Hola {{1}}, tu renta de {{2}} por ${{3}} vence el {{4}} (faltan 3 días).

Para pagar por SPEI:
Banco: {{5}}
CLABE: {{6}}

Si ya pagaste, ignora este mensaje.
```

Variables idénticas a la plantilla 4.

---

## Plantilla 6 — `admindi_payment_reminder_2d_v1`

**Friendly name**: `admindi_payment_reminder_2d_v1`.

**Body**:

```
Hola {{1}}, tu renta de {{2}} por ${{3}} vence el {{4}} (faltan 2 días).

Para pagar por SPEI:
Banco: {{5}}
CLABE: {{6}}

Si ya pagaste, ignora este mensaje.
```

Variables idénticas a la plantilla 4.

---

## Plantilla 7 — `admindi_payment_reminder_1d_v1`

**Friendly name**: `admindi_payment_reminder_1d_v1`.

Diferencia clave: `mañana` en lugar de `en N días`.

**Body**:

```
Hola {{1}}, tu renta de {{2}} por ${{3}} vence mañana ({{4}}).

Para pagar por SPEI:
Banco: {{5}}
CLABE: {{6}}

Si ya pagaste, ignora este mensaje.
```

Variables idénticas a la plantilla 4.

---

## Plantilla 8 — `admindi_transfer_confirmed_owner_v1` (Bloque B)

**Evento backend**: `TRANSFER_CONFIRMED` (nuevo, disparado en `LedgerService.autoConfirmPayment` dirigido al dueño).

**Destinatario**: el dueño del inmueble al que le acaba de caer un SPEI validado.

**Categoría**: `Utility`.  
**Language**: `es`.  
**Friendly name**: `admindi_transfer_confirmed_owner_v1`.

**Body**:

```
Hola {{1}}, se validó un pago SPEI a tu cuenta el {{2}}.

Inmueble: {{3}}
Arrendatario: {{4}}
Monto: ${{5}}
Clave de rastreo: {{6}}

Puedes consultar el detalle en tu portal: {{7}}
```

**Variables**:

| # | Nombre lógico | Ejemplo para aprobación |
|---|---|---|
| {{1}} | Nombre del dueño | `Israel` |
| {{2}} | Fecha + hora de validación | `17/04/2026 11:42` |
| {{3}} | Nombre del inmueble | `Depto Reforma 201` |
| {{4}} | Nombre del arrendatario | `David Rodríguez` |
| {{5}} | Monto | `18,500.00` |
| {{6}} | Clave de rastreo SPEI (Banxico) | `MBAN01202604170000000123456789` |
| {{7}} | URL del portal | `https://app.admindi.mx` |

---

## Plantilla 9 — `admindi_unpaid_digest_v1` (Bloque B)

**Evento backend**: `OWNER_UNPAID_TENANTS_DIGEST` (nuevo, cron diario 09:00 CDMX).

**Destinatario**: dueños que tienen uno o más inquilinos con factura vencida.

**Categoría**: `Utility`.  
**Language**: `es`.  
**Friendly name**: `admindi_unpaid_digest_v1`.

**Body**:

```
Hola {{1}}, tienes {{2}} factura(s) vencida(s) al día de hoy por un total de ${{3}}.

Arrendatarios con pago pendiente: {{4}}

Consulta el detalle y envía recordatorios manuales desde: {{5}}
```

**Variables**:

| # | Nombre lógico | Ejemplo para aprobación |
|---|---|---|
| {{1}} | Nombre del dueño | `Israel` |
| {{2}} | Número de facturas vencidas | `3` |
| {{3}} | Monto total pendiente | `42,800.00` |
| {{4}} | Lista corta separada por coma | `David R. (Reforma 201), María L. (Insurgentes 45), Carlos G. (Tlalpan 120)` |
| {{5}} | URL del portal | `https://app.admindi.mx/owner/delinquency` |

---

## Plantilla 10 — `admindi_monthly_report_v1` (Bloque B)

**Evento backend**: `OWNER_MONTHLY_REPORT` (nuevo, cron día 1 de cada mes 08:00 CDMX).

**Destinatario**: todos los dueños activos.

**Categoría**: `Utility`.  
**Language**: `es`.  
**Friendly name**: `admindi_monthly_report_v1`.

**Body**:

```
Hola {{1}}, tu reporte mensual de {{2}} ya está disponible.

Resumen:
Ingresos cobrados: ${{3}}
Pendiente por cobrar: ${{4}}
Gastos registrados: ${{5}}
Ocupación: {{6}}

Descarga el PDF desde tu portal: {{7}}
```

**Variables**:

| # | Nombre lógico | Ejemplo para aprobación |
|---|---|---|
| {{1}} | Nombre del dueño | `Israel` |
| {{2}} | Mes + año del reporte | `Marzo 2026` |
| {{3}} | Ingresos cobrados | `87,500.00` |
| {{4}} | Pendiente por cobrar | `18,500.00` |
| {{5}} | Gastos | `12,340.00` |
| {{6}} | Ocupación (texto corto) | `4 de 5 unidades ocupadas (80%)` |
| {{7}} | URL del portal | `https://app.admindi.mx/owner/reports` |

---

## Estado de aprobación (10/10 aprobadas por Meta) y mapping a config

Al 17 de abril de 2026 Meta aprobó las 10 plantillas. Algunas se reescribieron durante el proceso: abajo está el mapeo de **nombre aprobado → property del backend → Content SID**. Pega los SIDs en `application-secrets.yml` (`twilio.templates.*`) o como variables de entorno `TWILIO_TEMPLATE_*` y luego pon `twilio.templates.enabled=true` + `twilio.enabled=true`.

| # | Plantilla aprobada en Meta/Twilio | Property YAML (`twilio.templates.*`) | Variable de entorno | Content SID (cargado 17/04/2026) | Cambios vs v1 |
|---|---|---|---|---|---|
| 1 | `admindi_owner_welcome_v2` | `owner-welcome` | `TWILIO_TEMPLATE_OWNER_WELCOME` | `HXddc8ce4292e4933e107e30c254462590` | Redacción más transaccional. |
| 2 | `admindi_tenant_welcome_v2` | `tenant-welcome` | `TWILIO_TEMPLATE_TENANT_WELCOME` | `HX07cb001df7b63655ab3fb67622cfe79e` | Claridad en alta/activación del expediente. |
| 3 | `admindi_owner_profile_updated_v3` | `owner-profile-updated` | `TWILIO_TEMPLATE_OWNER_PROFILE_UPDATED` | `HX4eb0c265334423af2118f7a247f83a2a` | v1/v2 rechazadas; v3 reescrita como confirmación neutral. |
| 4 | `admindi_payment_reminder_5d_v1` | `payment-reminder-5d` | `TWILIO_TEMPLATE_PAYMENT_REMINDER_5D` | `HXac97f3585f94cf1a224a209f3ee8e436` | Sin cambios. |
| 5 | `admindi_payment_reminder_3d_v1` | `payment-reminder-3d` | `TWILIO_TEMPLATE_PAYMENT_REMINDER_3D` | `HX5b90af984e990de9cf10fe8bddcd5379` | Sin cambios. |
| 6 | `admindi_payment_reminder_2d_v1` | `payment-reminder-2d` | `TWILIO_TEMPLATE_PAYMENT_REMINDER_2D` | `HX6ce9f3f64088fbdf6d6f04e761dc2fa1` | Sin cambios. |
| 7 | `admindi_payment_reminder_1d_v1` | `payment-reminder-1d` | `TWILIO_TEMPLATE_PAYMENT_REMINDER_1D` | `HXa03762776cdca96548908db47cfda4ad` | Sin cambios. |
| 8 | `admindi_transfer_confirmed_owner_v2` | `transfer-confirmed` | `TWILIO_TEMPLATE_TRANSFER_CONFIRMED` | `HXb44fe328103ffbf99459b32b244fa9de` | Se agregó texto después de la URL final (variable dejó de cerrar el mensaje). |
| 9 | `admindi_unpaid_digest_v2` | `unpaid-digest` | `TWILIO_TEMPLATE_UNPAID_DIGEST` | `HX0effd135079bc25238426aa18f57627e` | Se agregó texto después de la URL final. |
| 10 | `admindi_monthly_report_v2` | `monthly-report` | `TWILIO_TEMPLATE_MONTHLY_REPORT` | `HX6e77e194c33f68305020006129bf45c5` | Se agregó texto después de la URL final. |

---

## Verificación crítica antes de activar `templates.enabled=true`

El backend ya tiene cableadas las variables para cada plantilla (ver `OwnerService`, `TenantService`, `OwnerProfileController`, `PaymentReminderScheduler`, `LedgerService`, `UnpaidTenantsDigestScheduler`, `MonthlyReportScheduler`). El mapeo asume la misma cantidad y orden de variables que las plantillas v1 originales de este documento.

**Antes de activar en prod, valida que cada plantilla aprobada mantenga el mismo mapeo de slots**:

1. En Twilio Console > Messaging > Content Builder, abre cada plantilla aprobada.
2. Copia el **body final aprobado**.
3. Compáralo con la sección correspondiente de este documento (`Plantilla 1..10`).
4. Si el número o el significado de algún `{{N}}` cambió, avísame con el body final y actualizo el mapeo en el caller correspondiente.

Plantillas con riesgo más alto de haber cambiado variables (por el tipo de reescritura declarado):

- `admindi_owner_welcome_v2` → verificar si todavía hay 3 slots (nombre, email, URL).
- `admindi_tenant_welcome_v2` → verificar si todavía hay 4 slots (nombre, inmueble, email, URL).
- `admindi_owner_profile_updated_v3` → **alto riesgo**: fue reescrita completamente. Verificar slots (nombre, fecha-hora, campos, URL).

Las 4 plantillas de `payment_reminder_*_v1` no cambiaron; las v2 de `transfer_confirmed`, `unpaid_digest` y `monthly_report` solo agregaron texto después de la URL, por lo que el mapeo se mantiene.

---

## Notas importantes para el registro

1. **No uses emojis en el body**. Meta los acepta pero eleva la probabilidad de rechazo en plantillas transaccionales.
2. **Respeta la puntuación y los acentos** del body al pegarlo. Meta compara byte a byte al validar mensajes salientes.
3. **No cambies el orden de las variables** una vez enviado. Si necesitas modificar el body, crea una nueva versión y deja la anterior viva hasta que el backend migre.
4. **En la sección "Sample values"** de Twilio mete los ejemplos de las tablas. Esto es lo que revisa el moderador de Meta.
5. Mientras las plantillas están en `Pending`, puedes probar con el **Twilio Sandbox** (`+14155238886`) porque el sandbox permite body libre sin plantilla durante 24h desde el último join del destinatario.
6. Si alguna plantilla se rechaza, Twilio te muestra el motivo exacto (normalmente: variable al inicio/final, redacción promocional, o falta de claridad). Ajusta y reenvía como `vN+1`.

---

## Activación en producción (checklist)

1. Pega los 10 Content SIDs aprobados en `application-secrets.yml` bajo `twilio.templates.*` (o en tus variables de entorno `TWILIO_TEMPLATE_*`).
2. Pon `twilio.templates.enabled: true` y `twilio.enabled: true`.
3. Cambia `twilio.whatsapp-from` del sandbox (`+14155238886`) al número propio aprobado (`+12183057952` u otro con WhatsApp Sender activado).
4. Reinicia el backend con `.\run-local.ps1` o el profile `local,secrets`.
5. En los logs debe aparecer: `[TWILIO-TPL] Template mapping initialized: enabled=true, configured=10/10 event types`.
6. Dispara un evento de prueba (por ejemplo, alta de un OWNER de QA) y revisa:
   - Audit event `WHATSAPP_SENT_OWNER_WELCOME` con `mode=template` y `tpl=HX…`.
   - Que el destinatario reciba el mensaje con la redacción aprobada (no el body libre).
7. Repite para los otros 9 eventos antes de marcar el rollout como completo.

Si algún envío queda auditado como `WHATSAPP_SKIPPED` con motivo `template configured but no templateVariables provided`, significa que un caller viejo no migró; avísame y lo reviso.

---

# Fase 2 — Plantillas del flujo unificado de agentes (inmobiliarios + mantenimiento)

Estas plantillas se agregan para cubrir los 24 nuevos `eventType` del bloque **Fase 2** (autorización de mantenimiento, ciclo completo del agente inmobiliario, comisiones y CLABE). Siguen las mismas reglas que las 10 plantillas del bloque original (todas `UTILITY`, language `es`, sin emojis, variable al centro).

**Estado actual**: los 22 Content SID están vacíos en `application.yml` — backend envía por email + in-app al 100% y por WhatsApp como body libre (solo ventana 24h) hasta que subas cada plantilla a Meta.

**Orden sugerido de aprobación** (si quieres priorizar: las más disparadas primero):

1. `admindi_maintenance_ticket_awaiting_owner_auth_v1` (se dispara cada vez que un inquilino abre ticket).
2. `admindi_prospect_proposed_v1` + `admindi_prospect_reminder_v1` (flujo comercial).
3. `admindi_maintenance_quote_uploaded_v1` + `admindi_maintenance_payment_required_v1`.
4. El resto en paralelo.

---

## Plantilla 11 — `admindi_maintenance_ticket_awaiting_owner_auth_v1`

**Evento backend**: `MAINTENANCE_TICKET_AWAITING_OWNER_AUTH` (`MaintenanceWorkflowService.createTicketWithOwnerAuth`).
**Destinatario**: dueño del inmueble.
**Categoría**: `Utility` · **Language**: `es`.

**Body**:

```
Hola {{1}}, tu inquilino abrió un ticket de mantenimiento en {{2}}.

Asunto: {{3}}
Urgencia: {{4}}

Entra a tu portal para autorizarlo y elegir proveedor (en la pestaña "Bandeja de decisiones"): {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Nombre del dueño | `Israel` |
| {{2}} | Nombre del inmueble | `Depto Reforma 201` |
| {{3}} | Título del ticket | `Fuga en tubería del baño` |
| {{4}} | Urgencia (NORMAL/ALTA) | `ALTA` |
| {{5}} | URL del portal | `https://app.admindi.mx/owner` |

---

## Plantilla 12 — `admindi_maintenance_ticket_rejected_by_owner_v1`

**Evento backend**: `MAINTENANCE_TICKET_REJECTED_BY_OWNER`.
**Destinatario**: inquilino que abrió el ticket.

**Body**:

```
Hola {{1}}, el dueño revisó tu ticket de mantenimiento "{{2}}" en {{3}} y no lo autorizó en este momento.

Motivo: {{4}}

Puedes consultar el detalle o abrir un nuevo ticket desde tu portal: {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Nombre del inquilino | `David` |
| {{2}} | Título del ticket | `Pintura de sala` |
| {{3}} | Inmueble | `Depto Reforma 201` |
| {{4}} | Motivo del rechazo | `Fuera de cobertura del contrato` |
| {{5}} | URL portal inquilino | `https://app.admindi.mx/tenant` |

---

## Plantilla 13 — `admindi_maintenance_provider_rejected_v1`

**Evento backend**: `MAINTENANCE_PROVIDER_REJECTED`.
**Destinatario**: dueño. Se dispara cuando un proveedor rechaza o se le agota el tiempo (72h); la cadena avanza al siguiente.

**Body**:

```
Hola {{1}}, el proveedor {{2}} no aceptó tu ticket "{{3}}" en {{4}}.

{{5}}

Consulta el estado en tu portal: {{6}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Nombre del dueño | `Israel` |
| {{2}} | Nombre del proveedor rechazante | `Plomería López` |
| {{3}} | Título del ticket | `Fuga en baño principal` |
| {{4}} | Inmueble | `Depto Reforma 201` |
| {{5}} | Detalle del siguiente paso | `Notificamos al siguiente proveedor de tu lista: Mantenimientos Díaz.` |
| {{6}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 14 — `admindi_maintenance_ticket_assigned_v1`

**Evento backend**: `MAINTENANCE_TICKET_ASSIGNED`.
**Destinatario**: proveedor de mantenimiento asignado.

**Body**:

```
Hola {{1}}, tienes un ticket de mantenimiento asignado.

Inmueble: {{2}}
Asunto: {{3}}
Urgencia: {{4}}

Tienes 72 horas para aceptar o rechazar desde tu portal: {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Nombre del proveedor | `Plomería López` |
| {{2}} | Inmueble | `Depto Reforma 201` |
| {{3}} | Título del ticket | `Fuga en baño` |
| {{4}} | Urgencia | `ALTA` |
| {{5}} | URL portal provider | `https://app.admindi.mx/provider` |

---

## Plantilla 15 — `admindi_maintenance_quote_uploaded_v1`

**Evento backend**: `MAINTENANCE_QUOTE_UPLOADED`.
**Destinatario**: dueño.

**Body**:

```
Hola {{1}}, el proveedor {{2}} subió la cotización del ticket "{{3}}" en {{4}}.

Monto propuesto: ${{5}}

Revísala y aprueba o rechaza desde tu portal: {{6}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Proveedor | `Plomería López` |
| {{3}} | Título ticket | `Fuga en baño` |
| {{4}} | Inmueble | `Depto Reforma 201` |
| {{5}} | Monto de la cotización | `3,500.00` |
| {{6}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 16 — `admindi_maintenance_payment_required_v1`

**Evento backend**: `MAINTENANCE_PAYMENT_REQUIRED`.
**Destinatario**: dueño (tras aprobar la cotización).

**Body**:

```
Hola {{1}}, aprobaste la cotización de {{2}} por ${{3}} para el ticket "{{4}}".

{{5}}

Registra el pago SPEI y sube el comprobante desde tu portal: {{6}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Proveedor | `Plomería López` |
| {{3}} | Monto a pagar | `3,500.00` |
| {{4}} | Título ticket | `Fuga en baño` |
| {{5}} | Nota de descuento (opcional) | `La plataforma absorbe 15% (crédito: $525.00).` o `Sin descuento aplicable.` |
| {{6}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 17 — `admindi_property_vacancy_opened_v1`

**Evento backend**: `PROPERTY_VACANCY_OPENED` (cuando el dueño presiona "Notificar a agentes" o cuando arranca la cadena por cualquier otra vía).
**Destinatario**: agente inmobiliario en turno.

**Body**:

```
Hola {{1}}, se te asignó un nuevo inmueble disponible para comercialización.

Inmueble: {{2}}
Dirección: {{3}}
Renta mensual esperada: ${{4}}

Tienes 72 horas para aceptar o rechazar antes de que pase al siguiente agente de la lista: {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Agente | `Laura Martínez` |
| {{2}} | Inmueble | `Depto Reforma 201` |
| {{3}} | Dirección corta | `Reforma 201, CDMX` |
| {{4}} | Renta esperada | `18,500.00` |
| {{5}} | URL portal agente | `https://app.admindi.mx/agent` |

---

## Plantilla 18 — `admindi_vacancy_agent_assigned_v1`

**Evento backend**: `VACANCY_AGENT_ASSIGNED` (legacy, persistente en `VacancyService`).
**Destinatario**: dueño.

**Body**:

```
Hola {{1}}, el agente {{2}} aceptó tomar la vacancia de {{3}}.

Seguirá el proceso: levantamiento de fotos, prospección y cierre. Recibirás avisos cuando proponga un inquilino.

Consulta el detalle en tu portal: {{4}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Agente | `Laura Martínez` |
| {{3}} | Inmueble | `Depto Reforma 201` |
| {{4}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 19 — `admindi_vacancy_agent_needed_v1`

**Evento backend**: `VACANCY_AGENT_NEEDED`.
**Destinatario**: dueño (cuando no logra asignarse agente automáticamente).

**Body**:

```
Hola {{1}}, necesitamos agente comercial para el inmueble {{2}}.

Configura tus prioridades en "Equipo y proveedores" para activar la cadena, o asigna uno manualmente desde: {{3}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Inmueble | `Depto Reforma 201` |
| {{3}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 20 — `admindi_vacancy_agent_rejected_v1`

**Evento backend**: `VACANCY_AGENT_REJECTED`.
**Destinatario**: dueño.

**Body**:

```
Hola {{1}}, el agente {{2}} no aceptó la vacancia de {{3}}.

{{4}}

Sigue el estado en tu portal: {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Agente que rechazó | `Laura Martínez` |
| {{3}} | Inmueble | `Depto Reforma 201` |
| {{4}} | Siguiente paso | `Pasamos al siguiente agente de tu lista: Carlos Díaz.` o `No hay más agentes en la cadena.` |
| {{5}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 21 — `admindi_vacancy_agent_timeout_v1`

**Evento backend**: `VACANCY_AGENT_TIMEOUT` (se dispara cuando un agente no responde en 72h; la cadena avanza).
**Destinatario**: dueño + agente (mismo texto dinámico).

**Body**:

```
Hola {{1}}, se agotó el plazo de 72 horas para responder a la vacancia de {{2}}.

{{3}}

Detalle en tu portal: {{4}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Destinatario | `Laura` o `Israel` |
| {{2}} | Inmueble | `Depto Reforma 201` |
| {{3}} | Mensaje contextual | `Pasamos al siguiente agente de la cadena.` o `Ya no hay agentes disponibles en la lista.` |
| {{4}} | URL portal correspondiente | `https://app.admindi.mx/owner` |

---

## Plantilla 22 — `admindi_vacancy_photos_uploaded_v1`

**Evento backend**: `VACANCY_PHOTOS_UPLOADED`.
**Destinatario**: dueño.

**Body**:

```
Hola {{1}}, el agente {{2}} subió {{3}} fotos de {{4}} y dejó el inmueble listo para prospección.

Revisa las fotos desde tu portal y aprueba la publicación: {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Agente | `Laura Martínez` |
| {{3}} | Número de fotos | `12` |
| {{4}} | Inmueble | `Depto Reforma 201` |
| {{5}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 23 — `admindi_vacancy_chain_exhausted_v1`

**Evento backend**: `VACANCY_CHAIN_EXHAUSTED`.
**Destinatario**: dueño (cuando todos los agentes de la lista rechazaron o se les agotó el tiempo).

**Body**:

```
Hola {{1}}, ningún agente de tu lista de prioridades aceptó la vacancia de {{2}}.

Puedes agregar más agentes en "Equipo y proveedores" o reiniciar la cadena desde tu portal: {{3}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Inmueble | `Depto Reforma 201` |
| {{3}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 24 — `admindi_prospect_proposed_v1`

**Evento backend**: `PROSPECT_PROPOSED`.
**Destinatario**: dueño.

**Body**:

```
Hola {{1}}, el agente {{2}} te propuso un prospecto de inquilino para {{3}}.

Prospecto: {{4}}
Contacto: {{5}}

Revisa y decide desde la Bandeja de decisiones: {{6}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Agente | `Laura Martínez` |
| {{3}} | Inmueble | `Depto Reforma 201` |
| {{4}} | Nombre prospecto | `Jorge Ramírez` |
| {{5}} | Contacto corto | `55 1234 5678 · jorge@ejemplo.com` |
| {{6}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 25 — `admindi_prospect_reminder_v1`

**Evento backend**: `PROSPECT_REMINDER` (recordatorio diario al dueño mientras el prospecto sigue PENDING).
**Destinatario**: dueño.

**Body**:

```
Hola {{1}}, sigue pendiente tu decisión sobre el prospecto {{2}} para {{3}}.

Llevas {{4}} esperando. Aceptar o rechazar desde: {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Nombre prospecto | `Jorge Ramírez` |
| {{3}} | Inmueble | `Depto Reforma 201` |
| {{4}} | Tiempo esperando | `2 días` |
| {{5}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 26 — `admindi_prospect_owner_accepted_v1`

**Evento backend**: `PROSPECT_OWNER_ACCEPTED`.
**Destinatario**: agente inmobiliario que propuso.

**Body**:

```
Hola {{1}}, el dueño aceptó al prospecto {{2}} para {{3}}.

Coordina la firma del contrato y, al cerrar, sube la evidencia desde tu portal: {{4}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Agente | `Laura Martínez` |
| {{2}} | Prospecto | `Jorge Ramírez` |
| {{3}} | Inmueble | `Depto Reforma 201` |
| {{4}} | URL portal agente | `https://app.admindi.mx/agent` |

---

## Plantilla 27 — `admindi_prospect_owner_rejected_v1`

**Evento backend**: `PROSPECT_OWNER_REJECTED`.
**Destinatario**: agente inmobiliario que propuso.

**Body**:

```
Hola {{1}}, el dueño rechazó al prospecto {{2}}.

Motivo: {{3}}

El inmueble vuelve a estado disponible para que propongas a otro interesado: {{4}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Agente | `Laura Martínez` |
| {{2}} | Prospecto | `Jorge Ramírez` |
| {{3}} | Motivo | `Ingresos no suficientes para el contrato` |
| {{4}} | URL portal | `https://app.admindi.mx/agent` |

---

## Plantilla 28 — `admindi_contract_signed_commission_due_v1`

**Evento backend**: `CONTRACT_SIGNED_COMMISSION_DUE`.
**Destinatario**: dueño (recibe recordatorio para pagar la comisión del agente).

**Body**:

```
Hola {{1}}, se registró la firma del contrato para {{2}} con el agente {{3}}.

Comisión pendiente de pago: ${{4}}

Realiza la transferencia SPEI y sube el comprobante desde la Bandeja de decisiones: {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Dueño | `Israel` |
| {{2}} | Inmueble | `Depto Reforma 201` |
| {{3}} | Agente | `Laura Martínez` |
| {{4}} | Monto comisión | `8,325.00` |
| {{5}} | URL portal | `https://app.admindi.mx/owner` |

---

## Plantilla 29 — `admindi_commission_approved_v1`

**Evento backend**: `COMMISSION_APPROVED` (legacy, ver `CommercialActivityService`).
**Destinatario**: agente inmobiliario.

**Body**:

```
Hola {{1}}, el dueño aprobó tu comisión por la comercialización de {{2}}.

Monto: ${{3}}

Te avisaremos cuando reciba el pago. Revisa el estado en: {{4}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Agente | `Laura Martínez` |
| {{2}} | Inmueble | `Depto Reforma 201` |
| {{3}} | Monto | `8,325.00` |
| {{4}} | URL portal agente | `https://app.admindi.mx/agent` |

---

## Plantilla 30 — `admindi_commission_spei_pending_manual_v1`

**Evento backend**: `COMMISSION_SPEI_PENDING_MANUAL` (cuando la validación automática contra Banxico falla 3 veces).
**Destinatario**: agente inmobiliario.

**Body**:

```
Hola {{1}}, el dueño registró el pago de tu comisión por ${{2}} pero la validación automática falló.

Clave de rastreo SPEI: {{3}}

Por favor confirma manualmente si recibiste el depósito desde tu portal: {{4}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Agente | `Laura Martínez` |
| {{2}} | Monto | `8,325.00` |
| {{3}} | Clave rastreo | `MBAN01202604180000000123456789` |
| {{4}} | URL portal agente | `https://app.admindi.mx/agent` |

---

## Plantilla 31 — `admindi_commission_paid_v1`

**Evento backend**: `COMMISSION_PAID`.
**Destinatario**: agente inmobiliario + dueño (mismo texto parametrizado).

**Body**:

```
Hola {{1}}, la comisión de la comercialización de {{2}} quedó liquidada.

Monto: ${{3}}
Fecha: {{4}}

Consulta el expediente en tu portal: {{5}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Destinatario | `Laura` o `Israel` |
| {{2}} | Inmueble | `Depto Reforma 201` |
| {{3}} | Monto | `8,325.00` |
| {{4}} | Fecha pago | `18/04/2026` |
| {{5}} | URL portal | `https://app.admindi.mx/agent` o `/owner` |

---

## Plantilla 32 — `admindi_agent_bank_account_validated_v1`

**Evento backend**: `AGENT_BANK_ACCOUNT_VALIDATED`.
**Destinatario**: agente inmobiliario o proveedor de mantenimiento (ambos usan el mismo flujo CLABE).

**Body**:

```
Hola {{1}}, tu CLABE **** {{2}} ({{3}}) quedó registrada y validada correctamente.

Ya puedes recibir pagos automatizados en ADMINDI.
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Nombre | `Laura Martínez` |
| {{2}} | Últimos 4 dígitos de CLABE | `7890` |
| {{3}} | Banco | `BBVA` |

---

## Plantilla 33 — `admindi_agent_bank_account_failed_v1`

**Evento backend**: `AGENT_BANK_ACCOUNT_FAILED`.
**Destinatario**: agente o proveedor que intentó registrar CLABE y falló validación.

**Body**:

```
Hola {{1}}, no pudimos validar la CLABE que registraste.

Motivo: {{2}}

Revísala y vuelve a guardarla desde tu portal: {{3}}
```

| # | Variable | Ejemplo |
|---|---|---|
| {{1}} | Nombre | `Laura Martínez` |
| {{2}} | Motivo | `La CLABE no tiene 18 dígitos válidos` |
| {{3}} | URL portal | `https://app.admindi.mx/agent` |

---

## Registro de Content SIDs — Fase 2 (pegar aquí al aprobarse)

Una vez Meta apruebe cada plantilla, Twilio te devolverá un `HX...`. Pégalo en la columna correspondiente y copia como variable de entorno en tu `.env` / `application-secrets.yml`.

| # | Plantilla | Property YAML (`twilio.templates.*`) | Variable de entorno | Content SID |
|---|---|---|---|---|
| 11 | `admindi_maintenance_ticket_awaiting_owner_auth_v1` | `ticket-awaiting-owner-auth` | `TWILIO_TEMPLATE_TICKET_AWAITING_OWNER_AUTH` | _(pendiente)_ |
| 12 | `admindi_maintenance_ticket_rejected_by_owner_v1` | `maintenance-ticket-rejected-by-owner` | `TWILIO_TEMPLATE_MAINTENANCE_TICKET_REJECTED_BY_OWNER` | _(pendiente)_ |
| 13 | `admindi_maintenance_provider_rejected_v1` | `maintenance-provider-rejected` | `TWILIO_TEMPLATE_MAINTENANCE_PROVIDER_REJECTED` | _(pendiente)_ |
| 14 | `admindi_maintenance_ticket_assigned_v1` | `maintenance-ticket-assigned` | `TWILIO_TEMPLATE_MAINTENANCE_TICKET_ASSIGNED` | _(pendiente)_ |
| 15 | `admindi_maintenance_quote_uploaded_v1` | `maintenance-quote-uploaded` | `TWILIO_TEMPLATE_MAINTENANCE_QUOTE_UPLOADED` | _(pendiente)_ |
| 16 | `admindi_maintenance_payment_required_v1` | `maintenance-payment-required` | `TWILIO_TEMPLATE_MAINTENANCE_PAYMENT_REQUIRED` | _(pendiente)_ |
| 17 | `admindi_property_vacancy_opened_v1` | `property-vacancy-opened` | `TWILIO_TEMPLATE_PROPERTY_VACANCY_OPENED` | _(pendiente)_ |
| 18 | `admindi_vacancy_agent_assigned_v1` | `vacancy-agent-assigned` | `TWILIO_TEMPLATE_VACANCY_AGENT_ASSIGNED` | _(pendiente)_ |
| 19 | `admindi_vacancy_agent_needed_v1` | `vacancy-agent-needed` | `TWILIO_TEMPLATE_VACANCY_AGENT_NEEDED` | _(pendiente)_ |
| 20 | `admindi_vacancy_agent_rejected_v1` | `vacancy-agent-rejected` | `TWILIO_TEMPLATE_VACANCY_AGENT_REJECTED` | _(pendiente)_ |
| 21 | `admindi_vacancy_agent_timeout_v1` | `vacancy-agent-timeout` | `TWILIO_TEMPLATE_VACANCY_AGENT_TIMEOUT` | _(pendiente)_ |
| 22 | `admindi_vacancy_photos_uploaded_v1` | `vacancy-photos-uploaded` | `TWILIO_TEMPLATE_VACANCY_PHOTOS_UPLOADED` | _(pendiente)_ |
| 23 | `admindi_vacancy_chain_exhausted_v1` | `vacancy-chain-exhausted` | `TWILIO_TEMPLATE_VACANCY_CHAIN_EXHAUSTED` | _(pendiente)_ |
| 24 | `admindi_prospect_proposed_v1` | `prospect-proposed` | `TWILIO_TEMPLATE_PROSPECT_PROPOSED` | _(pendiente)_ |
| 25 | `admindi_prospect_reminder_v1` | `prospect-reminder` | `TWILIO_TEMPLATE_PROSPECT_REMINDER` | _(pendiente)_ |
| 26 | `admindi_prospect_owner_accepted_v1` | `prospect-owner-accepted` | `TWILIO_TEMPLATE_PROSPECT_OWNER_ACCEPTED` | _(pendiente)_ |
| 27 | `admindi_prospect_owner_rejected_v1` | `prospect-owner-rejected` | `TWILIO_TEMPLATE_PROSPECT_OWNER_REJECTED` | _(pendiente)_ |
| 28 | `admindi_contract_signed_commission_due_v1` | `contract-signed-commission-due` | `TWILIO_TEMPLATE_CONTRACT_SIGNED_COMMISSION_DUE` | _(pendiente)_ |
| 29 | `admindi_commission_approved_v1` | `commission-approved` | `TWILIO_TEMPLATE_COMMISSION_APPROVED` | _(pendiente)_ |
| 30 | `admindi_commission_spei_pending_manual_v1` | `commission-spei-pending-manual` | `TWILIO_TEMPLATE_COMMISSION_SPEI_PENDING_MANUAL` | _(pendiente)_ |
| 31 | `admindi_commission_paid_v1` | `commission-paid` | `TWILIO_TEMPLATE_COMMISSION_PAID` | _(pendiente)_ |
| 32 | `admindi_agent_bank_account_validated_v1` | `agent-bank-account-validated` | `TWILIO_TEMPLATE_AGENT_BANK_ACCOUNT_VALIDATED` | _(pendiente)_ |
| 33 | `admindi_agent_bank_account_failed_v1` | `agent-bank-account-failed` | `TWILIO_TEMPLATE_AGENT_BANK_ACCOUNT_FAILED` | _(pendiente)_ |

---

## Importante — migración de callers al activar plantillas Fase 2

Hoy los services de Fase 2 (`MaintenanceWorkflowService`, `ContractClosureService`, `AgentCommissionService`, `ProspectService`, `VacancyAgentOrchestrationService`, `AgentBankAccountService`) invocan `domainEventDispatcher.dispatch(...)` **sin** el argumento `templateVariables`. Eso significa:

- Con `templates.enabled=false`: WhatsApp usa body libre (OK para ventana 24h).
- Con `templates.enabled=true` **y** SID configurado: el dispatcher audita `WHATSAPP_SKIPPED` con motivo "template configured but no templateVariables provided" — el email e in-app siguen funcionando, pero WhatsApp no sale.

**Por lo tanto, antes de activar `templates.enabled=true` en producción** hay que migrar esos 22 callers para que pasen el `Map<String, String>` con los valores de `{{1}}`, `{{2}}`, … en el mismo orden que el body de cada plantilla de arriba. Esa migración se hará en un bloque posterior, idealmente cuando tengas al menos la mitad de las plantillas aprobadas por Meta (no tiene sentido migrar callers cuyos templates todavía están en revisión).

**Recomendación operativa**:
1. Sube las 23 plantillas a Twilio en paralelo (1-3 días de aprobación Meta por cada una).
2. Conforme te aprueben, pega los SID en `application-secrets.yml`.
3. Cuando tengas ≥50% aprobadas, pídeme la migración de callers — cada una es un `Map.of("1", valor1, "2", valor2, ...)` en la llamada `dispatch` correspondiente.
4. Activa `twilio.templates.enabled=true` solo cuando los 23 callers estén migrados y al menos 20 plantillas aprobadas. Las pendientes caerán a body libre y el sistema sigue operando.

---

## Bloque C — Pagos y chatbot WhatsApp (mayo 2026)

**Contexto**: el backend ya no usa n8n. Twilio envía notificaciones y el chatbot inbound valida comprobantes. Estas 4 plantillas son **nuevas** (sin SID hasta que Meta apruebe).

**Prioridad de registro**: empieza por la **11** (`cash_payment_pending_owner`) — es la que empuja al dueño a validar por WhatsApp sin entrar al portal.

| # | Friendly name | Property YAML | Variable entorno | Estado SID |
|---|---------------|---------------|------------------|------------|
| 11 | `admindi_cash_payment_pending_owner_v1` | `cash-payment-pending-owner` | `TWILIO_TEMPLATE_CASH_PAYMENT_PENDING_OWNER` | `HX8602f66fb94e0b6c920d010210aec94d` | Aprobada 24-may-2026 |
| 12 | `admindi_cash_payment_approved_v1` | `cash-payment-approved` | `TWILIO_TEMPLATE_CASH_PAYMENT_APPROVED` | `HX44b2bdd7b375c9d546c5a9d40bc25e57` | Aprobada 24-may-2026 |
| 13 | `admindi_cash_payment_rejected_v1` | `cash-payment-rejected` | `TWILIO_TEMPLATE_CASH_PAYMENT_REJECTED` | `HXffa5e40f35ec10ed3a6ba0b61ab0838f` | Aprobada 24-may-2026 |
| 14 | `admindi_payment_auto_validated_v1` | `payment-auto-validated` | `TWILIO_TEMPLATE_PAYMENT_AUTO_VALIDATED` | `HXd771d5490c973edc5c219efb17003099` | Aprobada 24-may-2026 |

> La plantilla **8** (`transfer-confirmed`) sigue cubriendo el SPEI confirmado con CEP. La **14** es un aviso alternativo cuando el evento es `PAYMENT_AUTO_VALIDATED` (puedes registrarla o reutilizar la 8 si prefieres menos plantillas).

---

## Plantilla 11 — `admindi_cash_payment_pending_owner_v1` (prioritaria)

**Evento backend**: `CASH_PAYMENT_PENDING_OWNER` (efectivo, SPEI sin CLABE, o Banxico caído).

**Destinatario**: dueño del inmueble.

**Categoría**: `Utility`  
**Language**: `Spanish - es`  
**Content type**: `Text`  
**Friendly name**: `admindi_cash_payment_pending_owner_v1`

**Body** (copiar tal cual en Twilio Content Template Builder):

```
Hola {{1}}, tienes un comprobante de pago por validar en ADMINDI.

Inquilino: {{2}}
Inmueble: {{3}}
Periodo: {{4}}
Tipo: {{5}}
Monto: ${{6}}

Tienes {{7}} horas para aprobar o rechazar. Responde VALIDAR por WhatsApp o entra a tu portal.
```

**Sample values** (pegar en la sección Sample values de Twilio):

| Slot | Valor de ejemplo |
|------|------------------|
| {{1}} | `Israel` |
| {{2}} | `David Rodríguez` |
| {{3}} | `Depto Reforma 201` |
| {{4}} | `2026-04` |
| {{5}} | `EFECTIVO` |
| {{6}} | `18,500.00` |
| {{7}} | `120` |

**Mapeo backend** (`LedgerService.notifyOwnerPaymentPendingValidation`): slots `1`–`7` en ese orden.

---

## Plantilla 12 — `admindi_cash_payment_approved_v1`

**Evento backend**: `CASH_PAYMENT_APPROVED` (y notificación al inquilino tras aprobación del dueño).

**Destinatario**: arrendatario.

**Categoría**: `Utility` · **Language**: `es` · **Friendly name**: `admindi_cash_payment_approved_v1`

**Body**:

```
Hola {{1}}, tu arrendador confirmó tu pago en efectivo en ADMINDI.

Inmueble: {{2}}
Periodo: {{3}}
Monto aplicado: ${{4}}

Tu renta quedó registrada. Si tienes dudas, contacta a tu arrendador.
```

**Sample values**:

| Slot | Ejemplo |
|------|---------|
| {{1}} | `David` |
| {{2}} | `Depto Reforma 201` |
| {{3}} | `2026-04` |
| {{4}} | `18,500.00` |

---

## Plantilla 13 — `admindi_cash_payment_rejected_v1`

**Evento backend**: `CASH_PAYMENT_REJECTED`

**Destinatario**: arrendatario.

**Categoría**: `Utility` · **Language**: `es` · **Friendly name**: `admindi_cash_payment_rejected_v1`

**Body**:

```
Hola {{1}}, tu arrendador no pudo confirmar el comprobante de pago en ADMINDI.

Inmueble: {{2}}
Periodo: {{3}}
Motivo: {{4}}

Sube un comprobante nuevo o contacta a tu arrendador para aclarar.
```

**Sample values**:

| Slot | Ejemplo |
|------|---------|
| {{1}} | `David` |
| {{2}} | `Depto Reforma 201` |
| {{3}} | `2026-04` |
| {{4}} | `El monto no coincide con lo acordado` |

---

## Plantilla 14 — `admindi_payment_auto_validated_v1`

**Evento backend**: `PAYMENT_AUTO_VALIDATED` (SPEI validado por Banxico CEP).

**Destinatario**: dueño (opcional si ya usas plantilla 8 `transfer-confirmed`).

**Categoría**: `Utility` · **Language**: `es` · **Friendly name**: `admindi_payment_auto_validated_v1`

**Body**:

```
Hola {{1}}, se validó automáticamente un pago SPEI en ADMINDI.

Inmueble: {{2}}
Arrendatario: {{3}}
Periodo: {{4}}
Monto: ${{5}}
Clave de rastreo: {{6}}

El pago ya está aplicado a la factura. Consulta el detalle en tu portal cuando quieras.
```

**Sample values**:

| Slot | Ejemplo |
|------|---------|
| {{1}} | `Israel` |
| {{2}} | `Depto Reforma 201` |
| {{3}} | `David Rodríguez` |
| {{4}} | `2026-04` |
| {{5}} | `18,500.00` |
| {{6}} | `MBAN01202604170000000123456789` |

---

## Checklist Twilio + chatbot (después de aprobar plantilla 11)

1. Pegar `HX…` en `TWILIO_TEMPLATE_CASH_PAYMENT_PENDING_OWNER` (o `application-secrets.yml`).
2. Variables de entorno del bot:
   - `TWILIO_ENABLED=true`
   - `WHATSAPP_BOT_ENABLED=true`
   - `WHATSAPP_BOT_OWNER_ENABLED=true`
   - `TWILIO_WEBHOOK_PUBLIC_URL=https://tu-dominio/api/webhooks/twilio/whatsapp`
3. En Twilio Console → Messaging → Senders → tu WhatsApp Sender → **Webhook URL** = la misma URL pública.
4. Teléfono del dueño en perfil ADMINDI (`phone` o `contact_phone`).
5. Probar: inquilino sube comprobante → dueño recibe plantilla 11 → dueño escribe `VALIDAR` → menú → `1` → aprobar.
