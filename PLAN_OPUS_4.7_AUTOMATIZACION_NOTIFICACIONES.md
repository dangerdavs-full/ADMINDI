# PLAN DE IMPLEMENTACIÓN PARA OPUS 4.7
## Automatización completa de notificaciones (Email + WhatsApp + Chatbot IA) — Proyecto ADMINDI

> **Audiencia:** este documento está dirigido a Claude Opus 4.7 trabajando como agente programador dentro del proyecto ADMINDI. Contiene TODO el contexto necesario: arquitectura actual, decisiones ya tomadas, eventos existentes, restricciones de seguridad, y pasos concretos de implementación. Opus debe ejecutar este plan sin pedir confirmación de decisiones ya tomadas aquí — solo pedir confirmación si encuentra ambigüedad técnica real no cubierta por este documento.

---

## 0. CONTEXTO DEL PROYECTO (imprescindible leer antes de codear)

### Stack técnico confirmado
- **Backend:** Java 21 + Spring Boot 3.2.4 + Spring Security 6 + Spring Data JPA
- **Base de datos:** PostgreSQL 16 con Row Level Security multi-tenant
- **Cache/Queue:** Redis 7 (ya configurado)
- **Migraciones:** Flyway 10.10.0
- **Frontend:** React 18 + TypeScript + Vite + Tailwind
- **Almacenamiento actual:** local filesystem (migración a Cloudflare R2 pospuesta)
- **Cifrado de datos sensibles:** `EncryptedStringConverter` ya implementado y en uso
- **Autenticación:** JWT + TOTP 2FA (jjwt + dev.samstevens.totp)
- **Mail:** `spring-boot-starter-mail` ya incluido (SMTP configurable)

### Paquete raíz del backend
`com.admindi.backend` — toda implementación nueva va bajo este paquete.

### Arquitectura de notificaciones ACTUAL (NO rehacer, solo extender)

El proyecto ya tiene una base sólida. **Opus debe reusar y extender**, no reemplazar:

**1. `com.admindi.backend.service.DomainEventDispatcher`** — bus central de eventos de dominio.
- Firma canónica:
  ```java
  dispatch(String eventType, String title, String body, String ownerId,
           String actorEmail, List<String> recipientUserIds, Runnable whatsappCallback)
  ```
- Para cada evento: (1) crea audit record, (2) crea `NotificationEntity` IN_APP obligatoria por recipiente, (3) envía email por recipiente si `NotificationPreferenceEntity` EMAIL está activa, (4) invoca `whatsappCallback` una sola vez si algún recipiente tiene WHATSAPP activo.
- Preferencias se consultan por `(userId, eventType, channel)`. Si no existe fila → default TRUE (habilitado).
- Fallos en EMAIL o WhatsApp NO rompen flujo principal, quedan en audit.

**2. `com.admindi.backend.service.EmailService`** — SMTP nativo.
- Método público: `sendEventEmail(recipientUserId, eventType, ownerId, subject, body)`
- Configuración en `application.yml`: `app.mail.enabled`, `app.mail.provider`, `app.mail.from`, `spring.mail.*`
- Hoy solo soporta `SimpleMailMessage` (texto plano). **Opus debe extender a HTML con plantillas** (ver Fase 1).

**3. `com.admindi.backend.service.N8nNotificationAdapter`** — adapter legado de WhatsApp vía n8n.
- Feature flag: `integrations.n8n.enabled` (default false)
- Tiene lista `REDACTED_FIELDS` que Opus debe reusar tal cual en el nuevo adapter de Twilio.
- **Opus NO debe borrar este archivo.** Queda como fallback durante migración.

**4. `com.admindi.backend.notifications.NotificationChannels`** — `IN_APP`, `EMAIL`, `WHATSAPP`.
- `NotificationChannels.WHATSAPP` ya existe. El nuevo adapter Twilio la implementa.

**5. `com.admindi.backend.notifications.NotificationEventCatalog`** — lista de eventos visibles en UI de preferencias.
- Opus debe agregar aquí los eventos nuevos que introduzca (ej. `MONTHLY_SUMMARY_READY`).

### Eventos de dominio YA dispachados en el código (NO reimplementar)

Listado completo encontrado mediante grep de `dispatcher.dispatch(`:

| Archivo | Event Types |
|---------|-------------|
| `OwnerService.java` | `OWNER_CREATED`, `OWNER_CONTACT_UPDATED`, `OWNER_DEACTIVATED`, `OWNER_PURGED`, `OWNER_ROUTING_UPDATED` |
| `PropertyService.java` | `PROPERTY_CREATED`, `PROPERTY_UPDATED`, `PROPERTY_DELETED`, `PROPERTY_DELETE_APPROVED` |
| `PropertyFileService.java` | `PROPERTY_FILE_UPLOADED` |
| `UnitService.java` | `UNIT_CREATED`, `UNIT_UPDATED` |
| `TenantService.java` | `TENANT_CREATED`, `TENANT_UPDATED` |
| `TenantExpedienteArchiveService.java` | `TENANT_EXPEDIENTE_ARCHIVED` |
| `StaffService.java` | `STAFF_CREATED`, `STAFF_UPDATED`, `STAFF_LINKED`, `STAFF_DEACTIVATED` |
| `LeaseService.java` | `LEASE_CREATED`, `LEASE_TERMINATED` |
| `LedgerService.java` | `PAYMENT_AUTO_VALIDATED`, `PAYMENT_MANUAL_OVERRIDE`, `TRANSFER_PROOF_INCOMPLETE`, `PAYMENT_CEP_REJECTED` |
| `MercadoPagoService.java` | `PAYMENT_MP_CONFIRMED` |
| `MaintenanceProviderService.java` | `PROVIDER_CREATED`, `PROVIDER_UPDATED`, `PROVIDER_DEACTIVATED` |
| `approval/ApprovalRequestService.java` | `APPROVAL_REQUEST_SUBMITTED` (genérico para PROPERTY_DELETE, TENANT_ARCHIVE, LEASE_TERMINATE) |
| `approval/handlers/*.java` | `PROPERTY_DELETE_APPROVED`, `TENANT_ARCHIVE_APPROVED`, `TENANT_ARCHIVE_REJECTED`, `LEASE_TERMINATE_APPROVED`, `LEASE_TERMINATE_REJECTED` |

**Importante:** el flujo de "admin pide eliminar, dueño aprueba" YA está implementado vía `com.admindi.backend.approval.ApprovalRequestService` + handlers. Opus NO debe rehacerlo. Solo debe enriquecer el contenido de los mensajes (subject/body) en los dispatch calls existentes y asegurar que las plantillas email/WhatsApp cubran estos eventos.

### Schedulers existentes (NO duplicar)
- `LedgerService.cronProcessLedger` — cron diario medianoche, genera facturas mensuales.
- `PaymentAgreementService` — cron diario 2am `America/Mexico_City` para BREACHED.
- `SessionCleanupScheduler` — cada 6h.

### Restricciones NO negociables (del plan definitivo del producto)

1. **Jamás enviar por WhatsApp/email externo:** contraseñas, MFA codes, tokens, recovery tokens, OTPs. La lista `REDACTED_FIELDS` en `N8nNotificationAdapter` es la fuente de verdad — copiar al nuevo adapter.
2. **Identidad externa:** se resuelve por `UserEntity.contactPhone` normalizado a E.164. Si no hay match único y autorizado → `MANUAL_REVIEW`, nunca automatizar.
3. **Eventos prohibidos en canales externos:** password reset, MFA reset, recovery, cambio de contraseña.
4. **IN_APP es obligatorio** y no se apaga desde preferencias.
5. **El agente IA NUNCA aprueba nada crítico:** no aprueba pagos en efectivo, no aprueba convenios, no modifica contratos, no resetea credenciales. Solo propone → backend valida → backend ejecuta.
6. **Multi-tenant con RLS:** toda query que el agente o adapters hagan debe respetar el scope del owner/tenant actual. Nunca cruzar ownerId.
7. **Replay protection en webhooks:** rechazar timestamps > 5 min, idempotencia con Redis por `MessageSid` o equivalente.
8. **HMAC obligatorio en webhooks entrantes:** WhatsApp/Twilio, Mercado Pago, Banxico.

---

## 1. DECISIONES ARQUITECTÓNICAS YA TOMADAS (no debatir)

Estas decisiones fueron tomadas por el usuario en la conversación de diseño. Opus las ejecuta:

| Decisión | Valor elegido | Razón |
|----------|---------------|-------|
| Proveedor WhatsApp | **Twilio directo** (no n8n) | Menos infra, mejor seguridad, SDK oficial Java, fit con event-driven existente |
| Proveedor email | **SMTP nativo** (actual) con migración fácil a Resend/SES vía config | Ya funciona, zero-touch |
| Proveedor IA para OCR + chatbot | **Claude API** (Anthropic) — Haiku default, Sonnet para casos complejos | Mejor español, tool-use estricto, visión integrada, costo bajo |
| Validación CEP Banxico | **Scraping directo al portal público** (gratis) + **self-healing con IA cada 24h** | Portal público, legal, migración a STP/Arcus cuando volumen lo justifique |
| Almacenamiento de archivos | **Local filesystem** estructurado por `owners/{ownerId}/...` | Migración a R2 posterior vía cambio de config |
| Formato de mensajes | **HTML rich email** + **plantillas WhatsApp pre-aprobadas** | Obligatorio por Meta para WhatsApp, profesional para email |
| Chatbot WhatsApp | **Sí**, con tool-calling de Claude | Arrendatario puede pagar, reportar, quejarse por WhatsApp sin abrir portal |

---

## 2. ALCANCE FUNCIONAL COMPLETO

Opus debe implementar notificaciones **EMAIL + WHATSAPP** para TODOS estos eventos, más un **chatbot IA inbound** por WhatsApp.

### 2.1 Alertas de bienvenida al sistema (onboarding)

Cuando un usuario es creado (cualquier rol), debe recibir:
- **Email HTML** con bienvenida, credenciales temporales (contraseña via canal seguro separado — NO en el mismo correo, usar link one-time), link al sistema, guía corta de primeros pasos.
- **WhatsApp** (si tiene `contactPhone`): mensaje de bienvenida con link al portal.

Eventos cubiertos:
- `OWNER_CREATED` — plantilla `owner_welcome`
- `TENANT_CREATED` — plantilla `tenant_welcome` (incluye info del inmueble, renta, día de pago)
- `STAFF_CREATED` — plantilla `staff_welcome` (incluye contexto del owner al que fue asignado)
- `PROVIDER_CREATED` — plantilla `provider_welcome`

### 2.2 Alertas de transferencias/pagos

- `PAYMENT_AUTO_VALIDATED` — dueño + admin reciben email/WA con: nombre del arrendatario, dirección del inmueble, monto, mes, claveRastreo, **link al CEP XML/PDF oficial**.
- `PAYMENT_MP_CONFIRMED` — mismo formato, source=Mercado Pago.
- `PAYMENT_MANUAL_OVERRIDE` — con motivo del override y quién lo hizo.
- `TRANSFER_PROOF_INCOMPLETE` — arrendatario recibe qué campos faltan; dueño/admin se enteran.
- `PAYMENT_CEP_REJECTED` — arrendatario y dueño reciben motivo.
- `PAYMENT_CASH_PENDING_OWNER_APPROVAL` (nuevo, de chatbot) — dueño recibe ActionTask para confirmar efectivo.

### 2.3 Alertas de reporte contable mensual (NUEVO)

- `MONTHLY_SUMMARY_READY` — **NUEVO evento que Opus debe crear**.
- Scheduler nuevo: día 1 de cada mes a las 7am `America/Mexico_City`.
- Para cada owner activo, genera un resumen del mes anterior:
  - ingresos esperados, ingresos recibidos, ingresos pendientes
  - desglose por inmueble
  - inquilinos morosos
  - tickets de mantenimiento del mes y sus costos
  - % de ocupación
- **Email HTML** con resumen visual + link a PDF descargable.
- **WhatsApp** con resumen corto + link al PDF (plantilla utility).
- PDF se genera con POI/PDFBox — ya hay `Apache POI` en `pom.xml` para Excel; agregar `pdfbox` para PDF.
- Reutilizar `OwnerAccountingSummaryService` existente (313 líneas) — ahí vive la lógica de cálculo.

### 2.4 Alertas de aprobación (admin → dueño)

El flujo `ApprovalRequestService` ya existe. Opus debe:
- Enriquecer el dispatch para que el dueño reciba email + WhatsApp con contexto claro.
- Plantillas por tipo:
  - `admin_requests_property_delete` — "El administrador Juan pide eliminar el inmueble Camino Real 201. Aprobar/Rechazar: [link]"
  - `admin_requests_tenant_archive` — similar
  - `admin_requests_lease_terminate` — similar
- El email incluye botones de acción (aprobar/rechazar) que apuntan a la app con tokens firmados cortos (expiración 24h) para que el dueño no tenga que loguearse manualmente si no quiere. **IMPORTANTE: la acción real en backend SIEMPRE requiere MFA aunque venga del token del email** (plan producto lo exige).

### 2.5 Alertas operativas generales

- `MAINTENANCE_TICKET_CREATED`, `_ASSIGNED`, `_RESOLVED` (si no existen, Opus los crea en `MaintenanceService`)
- `LEASE_CREATED`, `LEASE_TERMINATED`
- `TENANT_EXPEDIENTE_ARCHIVED`
- `PROPERTY_DELETE_REQUESTED`, `PROPERTY_DELETE_APPROVED`
- `OWNER_CONTACT_UPDATED`

### 2.6 Chatbot IA por WhatsApp (inbound)

Arrendatario escribe por WhatsApp al número de la plataforma. El bot:
1. Se identifica como IA (obligatorio Meta + ético).
2. Verifica identidad por `contactPhone` E.164.
3. Ofrece menú: registrar pago, reportar mantenimiento, queja, ver saldo.
4. Conversa naturalmente, usa tool-calling para ejecutar acciones reales.
5. Confirma antes de ejecutar. Audita todo.
6. Escala a humano cuando el usuario escribe "humano", "agente" o cuando la acción requiere aprobación.

**Tools que Opus debe implementar (todos invocan services existentes, no duplican lógica):**
- `get_tenant_context()` — contexto del arrendatario actual
- `submit_spei_proof(invoiceId, claveRastreo, amount, date, bankEmitter, accountReceiver, mediaUrl?)` — invoca `LedgerService.submitTransferProof`
- `register_cash_payment(invoiceId, amount, notes, evidenceUrl?)` — crea `Payment` con status `PENDING_OWNER_APPROVAL`
- `get_card_payment_link(invoiceId)` — invoca `MercadoPagoService` para generar link
- `create_maintenance_ticket(category, description, priority, mediaUrls?)` — invoca `MaintenanceService`
- `create_complaint(category, description, mediaUrls?)` — invoca servicio nuevo `ComplaintService`
- `get_pending_invoices()` — lista facturas pendientes del tenant
- `escalate_to_human(reason)` — marca conversación `ESCALATED` y notifica al owner/admin

---

## 3. FASES DE IMPLEMENTACIÓN (ORDEN OBLIGATORIO)

Opus debe ejecutar las fases en este orden. **No saltar fases.** Cada fase entrega valor y se puede testear sola.

### FASE 0 — Preparación (0.5 días)

**0.1** Crear ramas de trabajo: `feature/notifications-phase-0`, `feature/notifications-phase-1`, etc. Una fase = una rama = un PR.

**0.2** Agregar dependencias a `backend/pom.xml`:

```xml
<!-- Twilio Java SDK -->
<dependency>
  <groupId>com.twilio.sdk</groupId>
  <artifactId>twilio</artifactId>
  <version>10.6.6</version>
</dependency>

<!-- Anthropic Java SDK (oficial) -->
<dependency>
  <groupId>com.anthropic</groupId>
  <artifactId>anthropic-java</artifactId>
  <version>1.0.0</version>
</dependency>

<!-- Thymeleaf para plantillas email -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>

<!-- PDFBox para PDFs -->
<dependency>
  <groupId>org.apache.pdfbox</groupId>
  <artifactId>pdfbox</artifactId>
  <version>3.0.3</version>
</dependency>

<!-- Apache Tika para MIME detection -->
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-core</artifactId>
  <version>2.9.2</version>
</dependency>

<!-- Jsoup para parsing HTML Banxico -->
<dependency>
  <groupId>org.jsoup</groupId>
  <artifactId>jsoup</artifactId>
  <version>1.18.3</version>
</dependency>
```

**0.3** Agregar propiedades a `application.yml` (Opus crea o edita):

```yaml
app:
  mail:
    enabled: ${MAIL_ENABLED:false}
    provider: ${MAIL_PROVIDER:smtp-generic}
    from: ${MAIL_FROM:}
    from-name: "ADMINDI Notificaciones"
  storage:
    provider: LOCAL
    local:
      base-path: ${STORAGE_PATH:./storage}
  url: ${APP_URL:http://localhost:3000}

integrations:
  n8n:
    enabled: ${N8N_ENABLED:false}
    webhook-url: ${N8N_WEBHOOK_URL:}
  whatsapp:
    provider: ${WHATSAPP_PROVIDER:DISABLED}  # DISABLED | TWILIO | N8N
    identify-as-bot: true
  twilio:
    account-sid: ${TWILIO_ACCOUNT_SID:}
    auth-token: ${TWILIO_AUTH_TOKEN:}
    from-number: ${TWILIO_FROM:whatsapp:+12183957952}   # sender real aprobado por Meta
    webhook-secret: ${TWILIO_WEBHOOK_SECRET:}
    waba-id: ${TWILIO_WABA_ID:943251495241835}
    meta-bm-id: ${TWILIO_META_BM_ID:1363887795298910}
    business-display-name: ${TWILIO_BUSINESS_DISPLAY_NAME:Arfaxad Mindware}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:}
    default-model: claude-haiku-4-5-20251001
    complex-model: claude-sonnet-4-6
    max-tokens: 2048
  banxico:
    cep:
      enabled: ${BANXICO_CEP_ENABLED:false}
      base-url: "https://www.banxico.org.mx/cep/"
      rate-limit-per-minute: 30
      cache-ttl-hours: 24

agent:
  enabled: ${AGENT_ENABLED:false}
  max-tokens-per-turn: 2048
  conversation-window-hours: 24
  rate-limit-messages-per-minute: 20
  rate-limit-messages-per-day: 500

banxico:
  scraper:
    self-heal:
      enabled: true
      cron: "0 0 3 * * ?"  # 3am diario
      canary-sample-size: 5
```

**0.4** Documentar en `README.md` del backend cómo obtener las keys (ver Apéndice A del final de este plan).

**0.5** Crear script `scripts/setup-storage.sh` que crea la estructura:
```
./storage/
  owners/
  tmp/
  cep-artifacts/
  media-cache/
```
con `chmod 750` y dueño correcto.

---

### FASE 1 — Email HTML rico con plantillas (1 día)

**1.1** Crear estructura de plantillas Thymeleaf en `backend/src/main/resources/templates/email/`:

```
templates/email/
  layout/
    base.html              ← layout común (header/footer ADMINDI)
  events/
    owner_welcome.html
    tenant_welcome.html
    staff_welcome.html
    provider_welcome.html
    payment_validated.html
    payment_mp_confirmed.html
    payment_incomplete.html
    payment_rejected.html
    payment_manual_override.html
    approval_request_owner.html
    approval_decision.html
    monthly_summary.html
    maintenance_ticket.html
    lease_created.html
    lease_terminated.html
    tenant_archived.html
    property_delete_requested.html
    property_delete_approved.html
    generic_notification.html  ← fallback
```

**1.2** Cada plantilla recibe un modelo tipado. Crear records:

```java
// com.admindi.backend.notifications.templates
public record EmailTemplateModel(
    String subject,
    String recipientName,
    String appUrl,
    String actionUrl,   // link principal del email
    String actionLabel,
    Map<String, Object> eventData  // contexto específico del evento
) {}
```

**1.3** Crear `com.admindi.backend.service.EmailTemplateService`:
- Método `render(String templateName, EmailTemplateModel model) -> String html`
- Usa Spring Thymeleaf `TemplateEngine`
- Cachea plantillas compiladas

**1.4** Extender `EmailService` con método nuevo:
```java
public void sendTemplatedEmail(
    String recipientUserId, String eventType, String ownerId,
    String templateName, EmailTemplateModel model
)
```
- Usa `MimeMessage` + `MimeMessageHelper` (no `SimpleMailMessage`).
- Setea `Content-Type: text/html; charset=UTF-8`.
- Mantener método antiguo `sendEventEmail` como fallback para eventos sin plantilla (llama a `generic_notification.html`).

**1.5** Modificar `DomainEventDispatcher` para resolver la plantilla por `eventType`:
- Crear `EmailTemplateRegistry` que mapea `eventType → templateName + builder`.
- Si el evento no tiene plantilla registrada → usa `generic_notification`.
- El `builder` es una función que toma `(eventType, title, body, ownerId, recipientUserId, extraContext)` y devuelve `EmailTemplateModel`.

**1.6** Implementar las plantillas HTML una por una. Criterios de calidad:
- Responsive (max-width 600px, tabla HTML clásica).
- Colores de marca ADMINDI (pedir al usuario o usar azul profesional como default).
- Footer con enlace a preferencias de notificación (`{appUrl}/preferences/notifications`).
- Footer con "Este es un correo automático. Para soporte escribe a {supportEmail}."
- Links con parámetros UTM para tracking futuro (no obligatorio v1).

**1.7** Enriquecer los `dispatcher.dispatch(...)` existentes para que pasen datos ricos al email.

Ejemplo para `PAYMENT_AUTO_VALIDATED` en `LedgerService.java` línea 265:

**Antes:**
```java
dispatcher.dispatch("PAYMENT_AUTO_VALIDATED",
    "Pago SPEI validado automáticamente (CEP): " + invoice.getMonthYear(),
    "Clave: " + claveRastreo + " | Validación: automática",
    invoice.getOwnerId(), email, null);
```

**Después:**
```java
String tenantName = userRepo.findById(invoice.getTenantProfileId())
    .map(UserEntity::getName).orElse("Arrendatario");
String propertyAddr = propertyRepo.findById(invoice.getPropertyId())
    .map(PropertyEntity::getFullAddress).orElse("Inmueble");
String cepUrl = cepArtifactService.getSignedUrl(proof.getId(), Duration.ofDays(30));

Map<String, Object> ctx = Map.of(
    "tenantName", tenantName,
    "propertyAddress", propertyAddr,
    "amount", invoice.getTotalAmount(),
    "monthYear", invoice.getMonthYear(),
    "claveRastreo", claveRastreo,
    "cepUrl", cepUrl,
    "paymentMethod", "SPEI"
);

List<String> recipientIds = recipientResolver.ownerAndAdmins(invoice.getOwnerId());

dispatcher.dispatchWithContext(
    "PAYMENT_AUTO_VALIDATED",
    "Pago recibido: " + tenantName + " — " + propertyAddr,
    null,  // body ahora lo arma la plantilla
    invoice.getOwnerId(), email, recipientIds,
    ctx,
    () -> whatsAppAdapter.sendPaymentValidated(invoice.getOwnerId(), ctx)
);
```

**1.8** Agregar método nuevo a `DomainEventDispatcher`:
```java
public void dispatchWithContext(
    String eventType, String title, String body,
    String ownerId, String actorEmail,
    List<String> recipientUserIds,
    Map<String, Object> eventContext,
    Runnable whatsappCallback
)
```
- El dispatcher antes de enviar email llama a `EmailTemplateRegistry.resolveModel(eventType, title, ownerId, recipientUserId, eventContext)` y luego `EmailService.sendTemplatedEmail(...)`.
- Mantener `dispatch(...)` (sin context) funcional para back-compat.

**1.9** Crear `com.admindi.backend.service.RecipientResolverService`:
- Métodos: `ownerAndAdmins(ownerId)`, `justOwner(ownerId)`, `tenantOfInvoice(invoiceId)`, `ownerOfProperty(propertyId)`, `allAdminsOfOwner(ownerId)`.
- Devuelve `List<String>` de user IDs.
- Query con RLS respeta scope.

**1.10** Tests unitarios (`EmailTemplateServiceTest`, `EmailTemplateRegistryTest`). Test que cada plantilla renderiza sin error con un modelo válido.

**1.11** Test de integración con GreenMail (in-memory SMTP) que verifica que el email HTML se envía correctamente para al menos 3 eventos representativos.

**Criterio de aceptación Fase 1:**
- [ ] `PAYMENT_AUTO_VALIDATED` envía email HTML rico al owner con nombre del tenant, dirección, monto, link CEP.
- [ ] `OWNER_CREATED` envía email de bienvenida profesional con link al portal.
- [ ] `APPROVAL_REQUEST_SUBMITTED` (admin pide eliminar) envía email al owner con botones de acción.
- [ ] `NotificationEventCatalog` actualizado con cualquier evento nuevo agregado.

---

### FASE 2 — Almacenamiento local estructurado + CEP artifacts (0.5 días)

**2.1** Crear `com.admindi.backend.storage.FileStorageProvider` como interfaz:
```java
public interface FileStorageProvider {
    StoredFile store(String relativePath, InputStream content, String contentType);
    InputStream read(String key);
    String signedUrl(String key, Duration ttl);
    void delete(String key);
    boolean exists(String key);
}
```

**2.2** Implementación `LocalFileStorageProvider`:
- Base path desde `app.storage.local.base-path`.
- Estructura obligatoria:
  ```
  {basePath}/owners/{ownerId}/proofs/{proofId}/original.{ext}
  {basePath}/owners/{ownerId}/proofs/{proofId}/extracted.json
  {basePath}/owners/{ownerId}/cep/{paymentId}/{claveRastreo}.xml
  {basePath}/owners/{ownerId}/cep/{paymentId}/{claveRastreo}.pdf
  {basePath}/owners/{ownerId}/cep/{paymentId}/meta.json
  {basePath}/owners/{ownerId}/properties/{propertyId}/contracts/{uuid}.pdf
  {basePath}/owners/{ownerId}/monthly-reports/{yyyy-MM}/{propertyId}.pdf
  {basePath}/media-cache/whatsapp/{yyyy-MM-dd}/{twilio-sid}.{ext}
  ```
- Seguridad: nunca usar el nombre original del archivo, siempre UUID + extensión derivada de MIME.
- Validación: Apache Tika para detectar MIME real, no confiar en `Content-Type` del request ni en extensión.
- Permisos filesystem: `chmod 640` archivos, `chmod 750` carpetas.
- `signedUrl` genera JWT corto (15 min) firmado con la key de la app, devuelve URL `/secure/files/{jwt}`.

**2.3** `SecureFileController` ya existe — extenderlo para validar el JWT de `signedUrl`, verificar que el solicitante tiene permiso (RLS sobre ownerId embebido en JWT), y servir el archivo.

**2.4** Refactor de `FileStorageService` existente para delegar en el provider (mantener API pública).

**2.5** Migrar archivos existentes: Opus revisa `backend/uploads/` y mueve todo a la nueva estructura con un migration runner (Flyway Java migration o `ApplicationRunner` de una sola vez).

**2.6** Backup script `scripts/backup-storage.sh` (documentado, no obligatorio ejecutar).

**Criterio de aceptación Fase 2:**
- [ ] Tests de `LocalFileStorageProvider` cubren store/read/delete/exists.
- [ ] Subida de comprobante SPEI va a la ruta correcta.
- [ ] Signed URL funciona con expiración.
- [ ] Tika valida MIME y rechaza archivos con extensión engañosa (ej. `.pdf` con magic bytes de `.exe`).

---

### FASE 3 — Banxico CEP real + self-healing scraper (2 días)

**3.1** Crear tablas nuevas con migration Flyway `V{next}__banxico_scraper.sql`:

```sql
CREATE TABLE banxico_scraper_config (
  id UUID PRIMARY KEY,
  version INT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  selectors JSONB NOT NULL,
  html_structure_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  created_by VARCHAR(50),
  samples_validated JSONB,
  notes TEXT
);

CREATE INDEX idx_banxico_scraper_active ON banxico_scraper_config(active) WHERE active = true;

CREATE TABLE banxico_scraper_health_log (
  id UUID PRIMARY KEY,
  check_at TIMESTAMP NOT NULL,
  status VARCHAR(30),
  config_version_before INT,
  config_version_after INT,
  ai_tokens_used INT,
  details JSONB
);

CREATE TABLE cep_artifacts (
  id UUID PRIMARY KEY,
  payment_id VARCHAR(50) NOT NULL,
  invoice_id VARCHAR(50) NOT NULL,
  owner_id VARCHAR(50) NOT NULL,
  clave_rastreo VARCHAR(50) NOT NULL,
  xml_key VARCHAR(500),       -- path en storage
  pdf_key VARCHAR(500),
  xml_hash_sha256 VARCHAR(64),
  pdf_hash_sha256 VARCHAR(64),
  source VARCHAR(30),         -- CEP_BANXICO_PUBLIC | STP | ARCUS | etc
  downloaded_at TIMESTAMP,
  raw_payload JSONB
);
```

**3.2** Seed inicial de `banxico_scraper_config` con versión 1 y selectores conocidos al momento de escribir el código (Opus hace scraping manual una vez, identifica selectores, los commitea como seed en Flyway).

**3.3** Reemplazar la lógica MOCK dentro de `BanxicoCepAdapter.validate()`:
- Mantener el contrato `CepValidationResult` exactamente igual.
- Cuando `banxico.cep.enabled=true`, ejecutar el scraping real:

```java
// Pseudocódigo
HttpResponse<String> resp = httpClient.post(
    BANXICO_URL,
    Map.of("claveRastreo", ..., "monto", ..., "fecha", ..., etc.)
);
String html = resp.body();
Document doc = Jsoup.parse(html);

BanxicoScraperConfig config = configRepo.findActive().orElseThrow();
Map<String, String> selectors = parseSelectors(config.getSelectors());

ExtractedCepData extracted = tryExtract(doc, selectors);

if (extracted.isComplete()) {
    return buildValidResult(extracted);
} else {
    // FALLBACK: pedir a la IA que extraiga del HTML crudo
    logger.warn("[CEP] Selectors failed, invoking AI fallback");
    ExtractedCepData aiExtracted = aiCepExtractor.extract(html, claveRastreo);
    markConfigAsDrifting(config);
    if (aiExtracted.isComplete()) return buildValidResult(aiExtracted);
    return rejectedResult("Banxico no pudo validar");
}
```

**3.4** Crear `com.admindi.backend.service.AiCepExtractor`:
- Usa `AnthropicClient` (ver Fase 5).
- Recibe HTML crudo + claveRastreo.
- Prompt sistema: *"Eres un parser de HTML del portal CEP de Banxico. Extrae los campos listados. Responde SOLO JSON estricto, sin markdown."*
- Modelo: Haiku (rápido, barato).
- Rate limit: máximo 100 llamadas/hora (circuit breaker con Resilience4j o manual).

**3.5** Crear `com.admindi.backend.service.CepArtifactService`:
- Después de validación exitosa: descarga XML y PDF oficiales de Banxico.
- Calcula SHA-256 de cada uno.
- Guarda en storage bajo `owners/{ownerId}/cep/{paymentId}/`.
- Crea fila en `cep_artifacts`.
- Expone `getSignedUrl(proofId, ttl)` para uso desde plantillas email.
- Si la descarga falla, marca el `Payment` como VALIDADO pero con `cepArtifactMissing=true` y programa reintento asíncrono con backoff.

**3.6** Crear `com.admindi.backend.service.BanxicoScraperHealthService`:
- `@Scheduled(cron = "${banxico.scraper.self-heal.cron:0 0 3 * * ?}", zone = "America/Mexico_City")`
- Toma `canary-sample-size` samples de `TransferProofSubmission` de los últimos 7 días con status VALIDATED.
- Re-consulta Banxico para cada sample.
- Calcula hash del HTML estructural (remover contenido dinámico: fechas, valores — quedarse con el DOM).
- Compara contra `config.htmlStructureHash`.
- Si igual: `status=HEALTHY`, log y termina.
- Si distinto: invoca `AiSelectorDiscoveryService.discover(html, samples)`:
  - Manda el HTML a Claude con prompt: *"Este HTML es la respuesta del portal Banxico CEP. Encuentra los selectores CSS para estos campos: claveRastreo, monto, fecha, bancoEmisor, bancoReceptor, cuentaReceptora, estado. Responde JSON: `{\"claveRastreo\": \"...\", ...}`."*
  - Recibe selectores nuevos.
  - Valida contra los N samples: cada sample debe extraer correctamente.
  - Si pasa: crea nueva fila `banxico_scraper_config` con `version = prev+1`, `active=true`, desactiva la anterior. Loguea `status=HEALED`. Notifica a SUPER_ADMIN.
  - Si falla: loguea `status=HEAL_FAILED`, notifica URGENTE a SUPER_ADMIN. Sistema queda operando en modo "IA directa" (Fase 3.3 fallback) hasta que humano arregle.

**3.7** Endpoint admin `POST /admin/banxico/scraper/rollback` para volver a versión anterior (por si el self-heal se equivoca).

**3.8** Tests:
- `BanxicoCepAdapterTest` con HTML samples real cacheados en `src/test/resources/banxico-samples/`.
- Test de `AiCepExtractor` con mock de Anthropic client.
- Test de `BanxicoScraperHealthService.run()` simulando drift.

**Criterio de aceptación Fase 3:**
- [ ] Con `banxico.cep.enabled=false` el adapter sigue mockeando (no romper dev).
- [ ] Con `banxico.cep.enabled=true` y credenciales reales, un SPEI real se valida contra Banxico.
- [ ] CEP XML y PDF se descargan y almacenan.
- [ ] Email `PAYMENT_AUTO_VALIDATED` incluye link al CEP oficial.
- [ ] Self-heal cron detecta drift simulado y re-aprende selectores.

---

### FASE 4 — OCR de comprobantes SPEI con Claude Vision (1 día)

**4.1** Crear interfaz `com.admindi.backend.ocr.ProofOcrAdapter`:
```java
public interface ProofOcrAdapter {
    OcrResult extractSpeiFields(byte[] fileContent, String mimeType);
}

public record OcrResult(
    boolean success,
    Map<String, String> extractedFields,  // claveRastreo, amount, date, bankEmitter, accountReceiver, reference
    double confidence,  // 0.0 - 1.0
    String rawResponse,
    List<String> warnings
) {}
```

**4.2** Implementación `ClaudeVisionOcrAdapter`:
- Usa SDK oficial `anthropic-java`.
- Modelo: `claude-haiku-4-5-20251001` (o leer de `integrations.anthropic.default-model`).
- Prompt del sistema:

```
Eres un asistente especializado en leer comprobantes de transferencia SPEI
de bancos mexicanos (BBVA, Santander, Banorte, HSBC, Banamex, Azteca, etc.).

Extrae EXACTAMENTE estos campos del comprobante adjunto:
- claveRastreo: string (también llamado "clave de rastreo", "tracking key", "folio SPEI")
- amount: número decimal (monto total transferido, sin signo de moneda)
- date: fecha en formato ISO YYYY-MM-DD
- bankEmitter: string (banco que ORIGINÓ la transferencia)
- bankReceiver: string (banco DESTINO de la transferencia)
- accountReceiver: string (cuenta o CLABE destino; si son 18 dígitos es CLABE)
- reference: string (referencia o concepto opcional)

Reglas:
1. Responde SOLO JSON estricto, sin markdown, sin explicación.
2. Si un campo no es legible o no está: omítelo del JSON (no inventes).
3. Si la imagen NO es un comprobante SPEI: responde {"error": "not_spei_proof"}.
4. La moneda siempre es MXN. No conviertas.
5. Incluye un campo "confidence" (0.0-1.0) con tu seguridad general.

Ejemplo de respuesta válida:
{"claveRastreo":"BBVA20260415123456","amount":12500.00,"date":"2026-04-15","bankEmitter":"BBVA México","bankReceiver":"Santander","accountReceiver":"012180012345678901","reference":"Renta abril","confidence":0.95}
```

- Envía la imagen como `image/jpeg` o `image/png`. Para PDFs, pre-convierte primera página a PNG con PDFBox.
- Parsea respuesta JSON. Si falla, devuelve `OcrResult(success=false, warnings=["json_parse_error"])`.

**4.3** Integrar al flujo SPEI en `LedgerService.submitTransferProof`:
- Si el tenant subió un archivo Y NO proporcionó algunos campos → llamar a `ProofOcrAdapter.extractSpeiFields(file)`.
- Cruzar los campos extraídos con los proporcionados: el tenant siempre gana si los dio explícitos; la IA completa los faltantes.
- Si la IA baja confidence < 0.7 en algún campo → marcar como `PENDING_DATA` y pedir al tenant que confirme.
- Loguear llamada OCR en nueva tabla `ocr_extraction_log`:
  ```sql
  CREATE TABLE ocr_extraction_log (
    id UUID PRIMARY KEY,
    proof_id VARCHAR(50),
    model VARCHAR(40),
    extracted_fields JSONB,
    confidence NUMERIC(3,2),
    tokens_used INT,
    cost_usd NUMERIC(10,6),
    raw_response TEXT,
    created_at TIMESTAMP
  );
  ```

**4.4** Endpoint de reintento manual por SUPER_ADMIN: `POST /admin/proofs/{id}/reextract`.

**4.5** Tests con imágenes reales cacheadas (tener 5-10 comprobantes de bancos distintos en `src/test/resources/proof-samples/`).

**Criterio de aceptación Fase 4:**
- [ ] Tenant sube foto, sistema extrae los 5 campos principales con >90% accuracy en pruebas.
- [ ] Flujo CEP real se dispara tras OCR exitoso (end-to-end).
- [ ] Dueño recibe notificación completa tras validación Banxico.

---

### FASE 5 — Twilio WhatsApp outbound + plantillas (1.5 días)

**5.1** Crear `com.admindi.backend.notifications.WhatsAppOutboundPort` (interfaz):
```java
public interface WhatsAppOutboundPort {
    WhatsAppSendResult sendTemplate(
        String toPhoneE164,
        String templateSid,
        Map<String, String> variables,
        String correlationId  // para idempotencia y trazabilidad
    );

    WhatsAppSendResult sendFreeformText(
        String toPhoneE164,
        String text,
        String correlationId
    );  // solo dentro de ventana 24h activa
}
```

**5.2** Implementación `TwilioWhatsAppAdapter implements WhatsAppOutboundPort`:
- `@ConditionalOnProperty(name="integrations.whatsapp.provider", havingValue="TWILIO")`
- Inicializa Twilio con `Twilio.init(accountSid, authToken)` en `@PostConstruct`.
- Usa `Message.creator()` con Content API para plantillas.
- Redacta los variables con la misma lista `REDACTED_FIELDS` del `N8nNotificationAdapter`.
- Audita cada envío (reutilizar tabla `audit_events`, eventType `WHATSAPP_SENT | WHATSAPP_FAILED`).

**5.3** Crear `N8nWhatsAppAdapter implements WhatsAppOutboundPort` como wrapper del `N8nNotificationAdapter` existente. Así puedes switchear providers via config sin perder nada:
- `@ConditionalOnProperty(name="integrations.whatsapp.provider", havingValue="N8N")`

**5.4** Crear `DisabledWhatsAppAdapter` default (no-op que audita como SKIPPED):
- `@ConditionalOnProperty(name="integrations.whatsapp.provider", havingValue="DISABLED", matchIfMissing=true)`

**5.5** Registrar plantillas WhatsApp en Twilio Console (esto es trabajo manual, Opus documenta pero NO puede ejecutar solo):

Crear documento `docs/whatsapp-templates.md` con el texto EXACTO de cada plantilla que el usuario debe someter a Meta via Twilio Console. Ejemplos:

```
Nombre: admindi_payment_received_owner
Categoría: UTILITY
Idioma: es_MX
Texto:
Hola {{1}}, tu arrendatario {{2}} (inmueble {{3}}) depositó {{4}}
correspondiente a la renta de {{5}}. Banxico validó la transferencia.
Clave CEP: {{6}}. Ver comprobante: {{7}}

Nombre: admindi_payment_incomplete_tenant
Categoría: UTILITY
Idioma: es_MX
Texto:
Hola {{1}}, recibimos tu comprobante SPEI pero nos faltan datos: {{2}}.
Responde con esos datos para validar tu pago, o entra al portal: {{3}}

Nombre: admindi_approval_request_owner
Categoría: UTILITY
Idioma: es_MX
Texto:
Hola {{1}}, el administrador {{2}} solicita {{3}} sobre {{4}}.
Aprobar o rechazar: {{5}}

Nombre: admindi_monthly_summary
Categoría: UTILITY
Idioma: es_MX
Texto:
Resumen {{1}}: cobraste {{2}} de {{3}} esperado ({{4}}%).
{{5}} inquilinos al corriente, {{6}} morosos.
Descarga reporte completo: {{7}}

Nombre: admindi_welcome_tenant
Categoría: UTILITY
Idioma: es_MX
Texto:
Hola {{1}}, bienvenido a ADMINDI. Ya puedes pagar tu renta y reportar
problemas de mantenimiento por aquí mismo o en el portal: {{2}}
Tu renta mensual es {{3}} con día de pago {{4}}.
```

(Opus documenta ~15 plantillas — una por cada eventType externo.)

**5.6** Crear tabla `whatsapp_templates` para mapear `eventType → templateSid`:
```sql
CREATE TABLE whatsapp_templates (
  id UUID PRIMARY KEY,
  event_type VARCHAR(60) NOT NULL,
  provider VARCHAR(20) NOT NULL,  -- TWILIO
  template_sid VARCHAR(100) NOT NULL,
  template_name VARCHAR(100) NOT NULL,
  language VARCHAR(10) NOT NULL DEFAULT 'es_MX',
  active BOOLEAN NOT NULL DEFAULT TRUE,
  variable_count INT NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  UNIQUE(event_type, provider, language, active) DEFERRABLE
);
```

Endpoint SUPER_ADMIN: `PUT /admin/whatsapp-templates/{eventType}` para actualizar el SID sin redeploy.

**5.7** Crear `com.admindi.backend.service.WhatsAppDispatchService`:
- Método `dispatchForEvent(eventType, ownerId, recipientUserIds, Map<String,Object> eventContext)`.
- Para cada recipiente: verifica preferencia WHATSAPP activa + que tiene `contactPhone` en E.164.
- Resuelve `templateSid` desde `whatsapp_templates`.
- Construye variables desde `eventContext` (un `VariableBuilderRegistry` por eventType).
- Invoca `WhatsAppOutboundPort.sendTemplate(...)`.
- Redacta campos sensibles antes de enviar.

**5.8** Sustituir el `Runnable whatsappCallback` del dispatcher por llamada a `WhatsAppDispatchService.dispatchForEvent(...)` para los eventos ya implementados.

**5.9** Tests:
- Mock de `WhatsAppOutboundPort` que captura las llamadas.
- Tests por evento: verificar que se invoca con el templateSid correcto y las variables bien formadas.

**Criterio de aceptación Fase 5:**
- [ ] `integrations.whatsapp.provider=TWILIO` + credenciales reales + número activo → `PAYMENT_AUTO_VALIDATED` manda WhatsApp real al owner usando `admindi_payment_received_owner`.
- [ ] `integrations.whatsapp.provider=DISABLED` → no se envía nada, pero el resto del sistema funciona.
- [ ] Todas las plantillas están en `docs/whatsapp-templates.md` listas para que el usuario las someta a Meta.

---

### FASE 6 — Resumen contable mensual (0.5 días)

**6.1** Crear `com.admindi.backend.service.MonthlyAccountingReportService`:
- Método `generateForOwner(ownerId, YearMonth period) → MonthlyReportResult`
- Reutiliza `OwnerAccountingSummaryService` para cálculos.
- Genera PDF con PDFBox:
  - Header ADMINDI + datos del owner
  - Tabla: inmueble | esperado | recibido | pendiente | estado
  - Sección: inquilinos al corriente vs. morosos
  - Sección: tickets de mantenimiento + costos
  - Footer con fecha de generación y firma "ADMINDI"
- Guarda PDF en `owners/{ownerId}/monthly-reports/{yyyy-MM}/summary.pdf`.

**6.2** `@Scheduled(cron = "0 0 7 1 * ?", zone = "America/Mexico_City")` — día 1 de cada mes a las 7am.
- Para cada `UserEntity` con rol OWNER y activo:
  - Generar reporte del mes anterior.
  - Disparar evento `MONTHLY_SUMMARY_READY` con ctx:
    ```java
    Map.of(
      "period", "Marzo 2026",
      "totalExpected", ...,
      "totalReceived", ...,
      "occupancyRate", ...,
      "tenantsOnTime", ...,
      "tenantsLate", ...,
      "reportUrl", signedUrl
    )
    ```

**6.3** Agregar plantilla email `monthly_summary.html` y plantilla WhatsApp `admindi_monthly_summary` (ya listada en 5.5).

**6.4** Registrar `MONTHLY_SUMMARY_READY` en `NotificationEventCatalog` (grupo "Pagos" o nuevo grupo "Reportes").

**6.5** Endpoint manual `POST /admin/monthly-reports/generate?ownerId=...&period=2026-03` para regenerar bajo demanda.

**Criterio de aceptación Fase 6:**
- [ ] Cron genera PDF y dispara evento el día 1 a las 7am.
- [ ] Owner recibe email con resumen tabular y link al PDF.
- [ ] PDF es legible y contiene todos los datos.

---

### FASE 7 — Chatbot IA por WhatsApp (inbound con tool-calling) (3-4 días)

**7.1** Migration Flyway `V{next}__whatsapp_conversations.sql`:

```sql
CREATE TABLE whatsapp_conversations (
  id UUID PRIMARY KEY,
  phone_e164 VARCHAR(20) NOT NULL,
  tenant_user_id VARCHAR(50),
  owner_id VARCHAR(50),
  state VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | ESCALATED | CLOSED
  last_message_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  closed_at TIMESTAMP,
  metadata JSONB
);

CREATE INDEX idx_wa_conv_phone_active ON whatsapp_conversations(phone_e164)
  WHERE state = 'ACTIVE';

CREATE TABLE conversation_turns (
  id UUID PRIMARY KEY,
  conversation_id UUID NOT NULL REFERENCES whatsapp_conversations(id) ON DELETE CASCADE,
  turn_index INT NOT NULL,
  role VARCHAR(20) NOT NULL,  -- USER | ASSISTANT | TOOL_CALL | TOOL_RESULT | SYSTEM
  content TEXT,
  tool_name VARCHAR(80),
  tool_input JSONB,
  tool_output JSONB,
  media_urls TEXT[],
  model_used VARCHAR(40),
  tokens_input INT,
  tokens_output INT,
  created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_conv_turns_conv ON conversation_turns(conversation_id, turn_index);

CREATE TABLE agent_actions_audit (
  id UUID PRIMARY KEY,
  conversation_id UUID,
  tool_name VARCHAR(80),
  resource_type VARCHAR(40),
  resource_id VARCHAR(50),
  status VARCHAR(20),
  details JSONB,
  owner_id VARCHAR(50),
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE blocked_phones (
  phone_e164 VARCHAR(20) PRIMARY KEY,
  reason VARCHAR(100),
  blocked_at TIMESTAMP NOT NULL,
  blocked_by VARCHAR(50)
);
```

**7.2** Webhook inbound controller `com.admindi.backend.controller.TwilioWebhookController`:
```
POST /webhooks/whatsapp/twilio
```
- Valida header `X-Twilio-Signature` usando `com.twilio.security.RequestValidator`.
- Idempotencia: Redis `SETNX whatsapp:msg:{MessageSid}` TTL 24h; si ya existe → return 200 sin reprocesar.
- Replay protection: rechaza si timestamp del mensaje > 5 min.
- Rate limit via `RateLimitInterceptor` (config: 20/min, 500/día por `From`).
- Resuelve identidad: `From` (formato `whatsapp:+52...`) → normaliza a E.164 → query `UserEntity WHERE contactPhone = ?`.
  - Si ≥2 matches → `MANUAL_REVIEW`, responde con mensaje explicativo, crea `ActionTask`.
  - Si 0 matches → responde "No encontramos tu cuenta. Tu arrendador te contactará." + crea `ActionTask`.
  - Si 1 match → continúa.
- Crea/reanuda `WhatsappConversation` ACTIVE.
- Delega a `AssistantAgentService.handleIncomingMessage(conv, body, mediaUrls)`.
- Responde 200 inmediatamente (procesamiento async con `@Async` o queue).

**7.3** `com.admindi.backend.agent.AssistantAgentService`:
- Método principal: `handleIncomingMessage(WhatsappConversation conv, String body, List<String> mediaUrls)`.
- Construye contexto:
  - System prompt (ver plantilla abajo).
  - Historial últimos 20 turnos.
  - Tools registrados en `AgentToolRegistry`.
  - Contexto del tenant: inmueble, renta, saldo, facturas pendientes.
- Invoca `AnthropicClient.messages.create(...)` con `tools` parameter.
- Loop:
  - Si el modelo responde texto plano → guarda turno ASSISTANT → envía por Twilio → termina.
  - Si el modelo responde `tool_use` → ejecuta el tool → guarda turno TOOL_CALL y TOOL_RESULT → re-invoca al modelo con el output → repite (máximo 5 iteraciones de seguridad).
- Todos los turnos se persisten en `conversation_turns` con `turn_index` incremental.

**7.4** System prompt (guardar como constante en `AgentPrompts.java`):

```
Eres el asistente IA de ADMINDI, una plataforma mexicana de administración
de inmuebles en renta. Tu trabajo es ayudar al arrendatario por WhatsApp.

Identificación del usuario actual (datos reales del sistema):
- Nombre: {tenantName}
- Inmueble: {propertyAddress}
- Renta mensual: ${monthlyRent} MXN
- Día de pago: {paymentDay}
- Facturas pendientes: {pendingInvoicesList}

Reglas estrictas que JAMÁS debes romper:
1. Tu primer mensaje siempre identifica que eres una IA.
2. JAMÁS inventes datos. Si no tienes la info, usa un tool para obtenerla.
3. JAMÁS aceptes cambiar contraseña, código MFA, tokens, o información bancaria
   sensible. Si el usuario lo pide, llama a `escalate_to_human`.
4. JAMÁS hables sobre otros arrendatarios o inmuebles que no sean del usuario actual.
5. Antes de ejecutar una acción (crear ticket, registrar pago), SIEMPRE confirma
   con el usuario: "¿Confirmas que...?"
6. Si el usuario escribe "humano", "agente", "persona", "operador", o pide
   hablar con alguien real, llama a `escalate_to_human` inmediatamente.
7. Si una acción requiere aprobación del dueño (ej. pago en efectivo), díselo
   claramente: "Registré tu pago en efectivo, pero necesita que tu arrendador
   lo confirme. Te avisaré cuando lo apruebe."
8. Responde en español neutro mexicano, amable pero profesional. Evita
   diminutivos excesivos. Usa emojis moderadamente (máximo 1-2 por mensaje).
9. Máximo 3 opciones por menú. Usa lenguaje natural, no listas numéricas
   rígidas salvo cuando el usuario pida explícitamente un menú.

Acciones que PUEDES hacer (via tools):
- Registrar comprobante SPEI: pide foto o datos, usa `submit_spei_proof`.
- Generar link de pago con tarjeta: usa `get_card_payment_link`.
- Registrar pago en efectivo: usa `register_cash_payment` (queda pendiente
  de aprobación del dueño).
- Crear ticket de mantenimiento: usa `create_maintenance_ticket`.
- Registrar queja: usa `create_complaint`.
- Ver saldo o facturas: usa `get_pending_invoices`.
- Escalar a humano: usa `escalate_to_human`.
```

**7.5** `com.admindi.backend.agent.AgentToolRegistry` — define los tools al estilo Anthropic tool-use:

```java
@Component
public class AgentToolRegistry {

    @AgentTool(name="get_tenant_context",
               description="Obtiene datos del arrendatario actual, su inmueble y facturas pendientes")
    public TenantContext getTenantContext(String tenantUserId);

    @AgentTool(name="get_pending_invoices",
               description="Lista facturas pendientes del arrendatario con monto y mes")
    public List<InvoiceSummary> getPendingInvoices(String tenantUserId);

    @AgentTool(name="submit_spei_proof",
               description="Registra comprobante SPEI. Requiere invoiceId y al menos una imagen O los 5 campos")
    public SubmitProofResult submitSpeiProof(
        String invoiceId, String claveRastreo, BigDecimal amount,
        String transferDate, String bankEmitter, String accountReceiver,
        String mediaUrl);

    @AgentTool(name="register_cash_payment",
               description="Registra pago en efectivo PENDIENTE DE APROBACIÓN del dueño")
    public CashPaymentResult registerCashPayment(
        String invoiceId, BigDecimal amount, String notes, String evidenceUrl);

    @AgentTool(name="get_card_payment_link",
               description="Genera link de Mercado Pago para pagar con tarjeta")
    public String getCardPaymentLink(String invoiceId);

    @AgentTool(name="create_maintenance_ticket",
               description="Crea ticket de mantenimiento con categoría, descripción y prioridad")
    public MaintenanceTicketResult createMaintenanceTicket(
        String category, String description, String priority, List<String> mediaUrls);

    @AgentTool(name="create_complaint",
               description="Registra una queja formal del arrendatario")
    public ComplaintResult createComplaint(
        String category, String description, List<String> mediaUrls);

    @AgentTool(name="escalate_to_human",
               description="Transfiere la conversación a un humano y marca ESCALATED")
    public void escalateToHuman(String reason);
}
```

Cada tool:
- Tiene una anotación que genera la definición JSON para Anthropic automáticamente (crear annotation processor simple o registrar manualmente via un builder).
- Valida el `tenantUserId` del contexto de conversación NO el que venga en parámetros (seguridad: el modelo no puede cambiarlo).
- Delega a los services existentes del backend (`LedgerService`, `MaintenanceService`, etc.).
- Escribe fila en `agent_actions_audit` antes y después de ejecutar.

**7.6** Tool `submit_spei_proof` con media: si el modelo pasa `mediaUrl`, Opus:
- Descarga con credenciales Twilio.
- Guarda en `media-cache/whatsapp/{date}/{sid}.{ext}`.
- Invoca `ProofOcrAdapter` (Fase 4).
- Completa campos faltantes desde OCR.
- Llama a `LedgerService.submitTransferProof`.

**7.7** Crear `ComplaintService` + `ComplaintEntity` si no existe (tabla `complaints` con `tenantUserId`, `ownerId`, `category`, `description`, `status`, `createdAt`, `mediaUrls`, `resolvedAt`, `resolution`).

**7.8** Crear `ClaudeClient` wrapper thin alrededor del SDK oficial:
- Factory selecciona modelo (Haiku default, Sonnet si `complexity_hint=high`).
- Inyecta API key desde `integrations.anthropic.api-key`.
- Maneja errores y rate limits con backoff exponencial.
- Loguea tokens usados por llamada.

**7.9** Anti-abuso:
- `BlockedPhoneFilter` — antes de procesar, check en `blocked_phones`.
- Detección de spam: si 3 mensajes idénticos en 10s → bloquea automáticamente con reason "SPAM_REPEAT".
- Endpoint `POST /admin/blocked-phones` y `DELETE /admin/blocked-phones/{phone}`.

**7.10** Escalamiento a humano:
- `escalate_to_human` marca `conversation.state='ESCALATED'`.
- Dispara evento `CONVERSATION_ESCALATED` → notifica al owner/admin por email+WhatsApp.
- El bot manda al usuario: "Te estoy pasando con un humano, te contactarán pronto."
- Mientras `state=ESCALATED`, el bot **no responde automáticamente** a ese número. Solo humanos via panel admin.

**7.11** Panel admin frontend (React) para ver conversaciones activas, historial, escalaciones. Ruta `/admin/conversations`. (Opus genera componente base, diseño simple tabla + detalle.)

**7.12** Tests:
- Test de tool-calling con mock de Anthropic que devuelve una secuencia predefinida.
- Test end-to-end: mensaje entra → bot identifica → usuario pide registrar SPEI con foto → bot ejecuta → confirma.
- Test de escalamiento: usuario escribe "humano" → bot escala correctamente.
- Test de seguridad: usuario no autorizado (phone sin match) → no puede ejecutar ningún tool.

**Criterio de aceptación Fase 7:**
- [ ] Flujo completo SPEI por WhatsApp funciona end-to-end.
- [ ] Ticket de mantenimiento se puede crear conversando.
- [ ] Escalamiento a humano funciona.
- [ ] Alucinaciones bloqueadas: el bot nunca inventa montos, inmuebles o tenants.
- [ ] Rate limiting y anti-abuso funcionando.

---

### FASE 8 — Integración final y QA (1 día)

**8.1** Actualizar `NotificationEventCatalog` con todos los eventos nuevos.

**8.2** UI de preferencias: verificar que frontend React (`frontend/src/...`) muestra correctamente EMAIL + WHATSAPP para cada evento nuevo. Si faltan, generar los checkbox.

**8.3** Matriz de QA — crear `qa-evidence/phase-notifications-matrix.md`:

| Evento | Trigger | IN_APP | EMAIL | WHATSAPP | Verificado |
|--------|---------|--------|-------|----------|------------|
| OWNER_CREATED | POST /admin/owners | ✅ | ✅ plantilla | ✅ plantilla | [ ] |
| TENANT_CREATED | POST /api/tenants | ✅ | ✅ plantilla | ✅ plantilla | [ ] |
| PAYMENT_AUTO_VALIDATED | SPEI validado CEP | ✅ | ✅ con link CEP | ✅ con link CEP | [ ] |
| ... | ... | ... | ... | ... | ... |

**8.4** End-to-end manual test con 1 owner, 1 propiedad, 1 tenant real y números de WhatsApp reales (sandbox de Twilio).

**8.5** Performance: validar que el dispatcher procesa eventos async sin bloquear request HTTP del usuario (usar `@Async` donde aplique, configurar `TaskExecutor`).

**8.6** Security review:
- Revisar que ningún email/WA contiene secretos (grep en logs).
- Validar que SUPER_ADMIN puede apagar cualquier canal sin redeploy.
- Confirmar que conversaciones IA están protegidas por RLS.

**8.7** Documentación final:
- `docs/notifications-architecture.md` — diagrama + decisiones
- `docs/whatsapp-templates.md` — plantillas completas
- `docs/setup-guide.md` — cómo conseguir Twilio + Anthropic API keys (ver Apéndice A)
- README del backend actualizado.

**Criterio de aceptación Fase 8:**
- [ ] Matriz QA 100% verde.
- [ ] No hay credenciales hardcoded (grep `sk-ant`, `AC` account sid, etc.).
- [ ] Tests de CI pasan (`mvn test`).
- [ ] `mvn spring-boot:run` arranca limpio con `integrations.whatsapp.provider=DISABLED`.

---

## 4. RESUMEN DE ARCHIVOS NUEVOS A CREAR

Opus debe crear estos archivos (rutas absolutas desde `backend/src/main/java/com/admindi/backend/`):

### Notificaciones y templates
- `notifications/templates/EmailTemplateModel.java`
- `notifications/templates/EmailTemplateRegistry.java`
- `notifications/templates/VariableBuilderRegistry.java`
- `service/EmailTemplateService.java`
- `service/RecipientResolverService.java`
- `service/WhatsAppDispatchService.java`

### Storage
- `storage/FileStorageProvider.java` (interface)
- `storage/LocalFileStorageProvider.java`
- `storage/StoredFile.java` (record)

### OCR
- `ocr/ProofOcrAdapter.java` (interface)
- `ocr/ClaudeVisionOcrAdapter.java`
- `ocr/OcrResult.java` (record)
- `service/OcrExtractionLogService.java`

### Banxico
- `service/CepArtifactService.java`
- `service/AiCepExtractor.java`
- `service/AiSelectorDiscoveryService.java`
- `service/BanxicoScraperHealthService.java`
- `repository/BanxicoScraperConfigRepository.java`
- `repository/BanxicoScraperHealthLogRepository.java`
- `repository/CepArtifactRepository.java`
- `model/BanxicoScraperConfig.java`
- `model/BanxicoScraperHealthLog.java`
- `model/CepArtifact.java`

### Twilio WhatsApp
- `notifications/WhatsAppOutboundPort.java` (interface)
- `notifications/WhatsAppSendResult.java` (record)
- `notifications/TwilioWhatsAppAdapter.java`
- `notifications/N8nWhatsAppAdapter.java` (wrapper del existente)
- `notifications/DisabledWhatsAppAdapter.java`
- `model/WhatsappTemplate.java`
- `repository/WhatsappTemplateRepository.java`
- `controller/WhatsappTemplateController.java`

### Chatbot IA
- `agent/AssistantAgentService.java`
- `agent/AgentToolRegistry.java`
- `agent/AgentPrompts.java`
- `agent/AgentTool.java` (annotation)
- `agent/ClaudeClient.java`
- `agent/AgentResponseLoop.java`
- `controller/TwilioWebhookController.java`
- `model/WhatsappConversation.java`
- `model/ConversationTurn.java`
- `model/AgentActionAudit.java`
- `model/BlockedPhone.java`
- `repository/WhatsappConversationRepository.java`
- `repository/ConversationTurnRepository.java`
- `repository/AgentActionAuditRepository.java`
- `repository/BlockedPhoneRepository.java`
- `service/ComplaintService.java` (si no existe)
- `model/Complaint.java`
- `repository/ComplaintRepository.java`

### Reportes mensuales
- `service/MonthlyAccountingReportService.java`
- `service/MonthlyReportScheduler.java`

### Migraciones Flyway
- `resources/db/migration/V{N}__banxico_scraper.sql`
- `resources/db/migration/V{N+1}__cep_artifacts.sql`
- `resources/db/migration/V{N+2}__ocr_log.sql`
- `resources/db/migration/V{N+3}__whatsapp_templates.sql`
- `resources/db/migration/V{N+4}__whatsapp_conversations.sql`
- `resources/db/migration/V{N+5}__complaints.sql`
- `resources/db/migration/V{N+6}__blocked_phones.sql`

### Plantillas Thymeleaf
- `resources/templates/email/layout/base.html`
- `resources/templates/email/events/*.html` (15+ plantillas)

### Documentación
- `docs/notifications-architecture.md`
- `docs/whatsapp-templates.md`
- `docs/setup-guide.md`

---

## 5. REGLAS OPERATIVAS PARA OPUS

### 5.1 Convenciones de código
- Seguir el estilo Java 21 ya presente en el proyecto: records donde aplique, `var` para locales obvios, streams cuando mejora legibilidad.
- Javadoc en clases públicas de `service/` y `controller/` explicando propósito, seguridad y side effects.
- Nombres en inglés para código, mensajes de log y Javadoc; nombres en español para user-facing strings (subjects, bodies, plantillas).
- Inyección por constructor (ya es el patrón del proyecto).
- Nunca commit con tests rotos. `mvn test` debe pasar al final de cada fase.

### 5.2 Manejo de secretos
- **JAMÁS** hardcodear API keys, tokens, contraseñas. Siempre via `@Value` + variables de entorno.
- Logs nunca contienen secretos. Reusar los helpers de redacción de `N8nNotificationAdapter` y `EmailService`.
- Commits NO deben incluir `.env` o archivos con credenciales. `.gitignore` ya debería cubrirlo pero Opus verifica.

### 5.3 Tests
- Cada fase incluye tests unitarios y al menos un test de integración.
- Para tests que requieren API externa (Anthropic, Twilio, Banxico), usar mocks o WireMock. **NO** llamar APIs reales desde tests.
- Para emails, usar GreenMail o similar in-memory SMTP.

### 5.4 Git workflow
- Una fase = una rama = un PR.
- Commits descriptivos en español, formato: `feat(notifications): descripción corta` o `fix`, `refactor`, `test`, `docs`.
- PR description en español, incluye: qué se hizo, por qué, cómo testear, checklist criterios de aceptación de la fase.

### 5.5 Cuándo pedir confirmación al usuario
Opus pide al usuario cuando:
- Necesita credenciales o API keys que no tiene.
- Encuentra ambigüedad técnica real no cubierta en este plan.
- Va a ejecutar una operación destructiva (borrar datos, reset migrations).
- Detecta que el plan contradice algo en el código existente no identificado aquí.

Opus NO pide confirmación para:
- Decisiones ya documentadas en sección 1 (Twilio, Claude, SMTP, etc.).
- Detalles de implementación dentro del alcance de cada fase.
- Estilos, nombres, tests.

---

## 6. CRITERIO DE ÉXITO GLOBAL

Al completar las 8 fases, el proyecto debe cumplir:

1. **Cualquier evento de dominio relevante** dispara automáticamente IN_APP + EMAIL + WhatsApp (según preferencias del usuario).
2. **SPEI end-to-end automatizado:** arrendatario sube foto por WhatsApp o portal → OCR extrae datos → Banxico valida → CEP se guarda → dueño recibe notificación completa.
3. **Resumen mensual** llega automáticamente el día 1 de cada mes a las 7am.
4. **Aprobaciones admin → dueño** funcionan por email con botones + requieren MFA al aprobar.
5. **Chatbot IA** permite al arrendatario realizar pagos, reportar mantenimiento y presentar quejas conversando por WhatsApp.
6. **Self-healing Banxico** mantiene el scraper funcionando sin intervención humana mientras la estructura del HTML cambie en rangos razonables.
7. **Cero secretos en código.** Todo via variables de entorno.
8. **Multi-tenant respetado** en todos los nuevos componentes.
9. **Auditoría completa:** toda notificación, tool-call de agente, y acción sensible queda registrada con actor y timestamp.
10. **Sistema operable sin ninguna de las integraciones externas activas** (todas detrás de feature flags).

---

## APÉNDICE A — Cómo conseguir las API keys

### A.1 Anthropic (Claude API)

1. Ir a **https://console.anthropic.com/**.
2. Crear cuenta con el email de administración del proyecto (recomendado: un email corporativo, no personal).
3. Verificar email y completar onboarding.
4. **Agregar método de pago:**
   - En el menú izquierdo: **Plans & Billing** → **Settings**.
   - Seleccionar **Build** plan (prepagado, sin compromiso mensual). Es el plan correcto para proyectos en producción.
   - Agregar tarjeta.
   - **Cargar saldo inicial:** recomendado comenzar con **$20-50 USD**. Con Haiku, esto da miles de conversaciones y extracciones OCR.
   - Activar **auto-reload** si quieres que se recargue automáticamente cuando baje de cierto umbral (ej. $5 → recarga $20).
5. **Crear API key:**
   - Menú izquierdo: **API Keys** → **Create Key**.
   - Nombre: `admindi-production` (o `admindi-dev` para staging).
   - Copiar la key (empieza con `sk-ant-...`). **Solo se muestra una vez.**
   - Pegarla en variable de entorno `ANTHROPIC_API_KEY` del backend (`.env` o el sistema de secrets que uses).
6. **Configurar límites de gasto** (recomendado):
   - En **Usage** → **Spend limits**: poner un tope mensual (ej. $100 USD) como seguro.
7. **Modelos disponibles y costos (precios por millón de tokens):**
   - `claude-haiku-4-5-20251001` — **recomendado para OCR y chatbot**: $1 input / $5 output por millón.
   - `claude-sonnet-4-6` — para casos complejos o fallback: $3 input / $15 output.
   - `claude-opus-4-6` — para tareas muy complejas, caro: $15 input / $75 output. No usar en hot path.

**Costo estimado del proyecto:** con volumen razonable (200 arrendatarios, 5 interacciones/mes cada uno + OCRs + CEP self-heal), estás en ~$15-30 USD/mes. Si explota 10x, ~$150/mes.

### A.2 Twilio

> **ESTADO ACTUAL (actualizado 17-abril-2026):** la cuenta Twilio ya está creada, el WhatsApp sender ya está **Online** y aprobado por Meta. Los datos del sender son:
>
> | Campo | Valor |
> |---|---|
> | WhatsApp number | `+12183957952` |
> | Business display name | `Arfaxad Mindware` |
> | Sender status | **Online** ✓ |
> | WhatsApp Business Account ID (WABA) | `943251495241835` |
> | Meta Business Manager ID | `1363887795298910` |
> | Throughput | 80 MPS |
>
> Opus 4.7 debe usar `TWILIO_FROM=whatsapp:+12183957952` directamente. NO hay que pasar por el sandbox. Lo único pendiente es: (a) generar `TWILIO_ACCOUNT_SID` y `TWILIO_AUTH_TOKEN` desde el dashboard de Twilio, (b) registrar las 17 plantillas en Content Template Builder, (c) configurar el webhook inbound apuntando al backend productivo.

1. Ir a **https://www.twilio.com/try-twilio**.
2. Crear cuenta (pide email, teléfono, tarjeta). Te dan **$15 USD de crédito gratis** para pruebas.
3. Completar el onboarding eligiendo:
   - Producto: **Messaging → WhatsApp**.
   - Caso de uso: **Notifications / Alerts** y **Conversational Assistant**.
4. **Sandbox inicial (gratis, solo para testing):**
   - Consola → **Messaging** → **Try it out** → **Send a WhatsApp message**.
   - Sigue las instrucciones: escribes "join {code}" al número sandbox de Twilio desde tu WhatsApp personal.
   - Ya puedes mandar/recibir mensajes con tu número. Límite: solo tu número y números que hagan "join".
   - Para desarrollo y pruebas es suficiente.
5. **Para producción** (cuando tengas todo probado):
   - **Messaging** → **Senders** → **WhatsApp** → **Request a Sender**.
   - Verificar el negocio con Meta (Facebook Business Manager).
   - Comprar un número Twilio con capacidad WhatsApp (~$1 USD/mes).
   - Enviar a Meta para aprobar el número para WhatsApp Business.
   - Tiempo: 1-3 días hábiles.
6. **Crear plantillas de mensajes:**
   - **Messaging** → **Content Template Builder**.
   - Crear una plantilla por evento (ver lista en `docs/whatsapp-templates.md`).
   - Someter a Meta para aprobación. Categoría: **Utility** (para transaccionales como pagos, recordatorios).
   - Tiempo de aprobación: 24-48 horas.
7. **Credenciales necesarias:**
   - **Account SID** (empieza con `AC...`): dashboard principal.
   - **Auth Token**: dashboard principal (hay que hacer click en "show").
   - **From number**: el número sandbox (`whatsapp:+14155238886` en fase de pruebas) o tu número de producción.
   - Ponerlos en variables de entorno: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`.
8. **Configurar webhook inbound:**
   - Dashboard → **Messaging** → **Senders** → tu número → **A Message Comes In**.
   - URL: `https://tu-dominio.com/webhooks/whatsapp/twilio` (debe ser HTTPS público).
   - Método: POST.
   - Para desarrollo local: usa **ngrok** (`ngrok http 8080`) y pon la URL de ngrok.
9. **Costos WhatsApp en México (aprox abril 2026):**
   - Conversación Utility (pagos, recordatorios): ~$0.03 MXN por conversación de 24h.
   - Conversación Marketing: ~$0.12 MXN.
   - Conversación Authentication: ~$0.02 MXN.
   - Más ~$0.005 USD por mensaje de servicio de Twilio encima del costo Meta.
   - **Estimado para 1,000 conversaciones/mes:** ~$30-50 MXN + $5 USD Twilio = ~**$40 USD/mes total**.

### A.3 Entornos recomendados

```bash
# backend/.env (no committear)
MAIL_ENABLED=true
MAIL_PROVIDER=gmail
MAIL_FROM=notificaciones@admindi.app
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=tu-correo@gmail.com
SPRING_MAIL_PASSWORD=<app-password-gmail>

STORAGE_PATH=/var/admindi/storage
APP_URL=https://app.admindi.mx

N8N_ENABLED=false
WHATSAPP_PROVIDER=TWILIO

TWILIO_ACCOUNT_SID=AC...                       # sacar del Twilio dashboard
TWILIO_AUTH_TOKEN=...                          # sacar del Twilio dashboard (click "show")
TWILIO_FROM=whatsapp:+12183957952              # sender real ya aprobado por Meta
TWILIO_WEBHOOK_SECRET=<se configura en twilio console>
TWILIO_WABA_ID=943251495241835                 # WhatsApp Business Account ID
TWILIO_META_BM_ID=1363887795298910             # Meta Business Manager ID
TWILIO_BUSINESS_DISPLAY_NAME=Arfaxad Mindware  # usar en "Sources:" y plantillas

ANTHROPIC_API_KEY=sk-ant-...

BANXICO_CEP_ENABLED=true
AGENT_ENABLED=true
```

---

## APÉNDICE B — Lista de plantillas WhatsApp a someter

(Ver `docs/whatsapp-templates.md` que Opus generará. Lista resumen aquí.)

1. `admindi_welcome_owner`
2. `admindi_welcome_tenant`
3. `admindi_welcome_staff`
4. `admindi_welcome_provider`
5. `admindi_payment_received_owner`
6. `admindi_payment_received_admin`
7. `admindi_payment_incomplete_tenant`
8. `admindi_payment_rejected_tenant`
9. `admindi_payment_manual_override`
10. `admindi_approval_request_owner`
11. `admindi_approval_decision`
12. `admindi_monthly_summary_owner`
13. `admindi_maintenance_ticket_owner`
14. `admindi_maintenance_ticket_provider`
15. `admindi_complaint_registered_owner`
16. `admindi_lease_created_tenant`
17. `admindi_conversation_escalated_owner`

---

## APÉNDICE C — Orden de ejecución recomendado

```
Día 1:  Fase 0 + Fase 1 (setup + emails HTML)
Día 2:  Fase 2 + Fase 3 parte 1 (storage + CEP real)
Día 3:  Fase 3 parte 2 + Fase 4 (self-heal + OCR)
Día 4:  Fase 5 (Twilio WhatsApp)
Día 5:  Fase 6 (resumen mensual) + Fase 7 parte 1 (webhook + identidad)
Día 6:  Fase 7 parte 2 (agente + tools)
Día 7:  Fase 7 parte 3 (pulido + escalamiento)
Día 8:  Fase 8 (QA + docs)
```

**Total: ~8 días de trabajo enfocado.**

---

## FIN DEL PLAN

Opus 4.7: por favor confirma que leíste este plan completo antes de codear. Si encuentras contradicción con el estado actual del código, reporta antes de modificar. Ejecuta fase por fase, no saltes el orden. Al final de cada fase, haz commit + PR, corre tests, reporta estado al usuario.
