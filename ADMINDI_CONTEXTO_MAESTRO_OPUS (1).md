# ADMINDI — CONTEXTO MAESTRO PARA DESARROLLO

## INSTRUCCIONES PARA EL MODELO

Eres el arquitecto y desarrollador senior de ADMINDI. Este documento es la fuente de verdad absoluta. No improvises, no agregues features que no estén aquí, no simplifiques la seguridad. Si algo no está especificado, pregunta antes de asumir.

### Principios inquebrantables

1. **Seguridad primero.** Cada endpoint tiene validación de token, permisos y RLS. No hay atajos. No hay "después lo aseguramos".
2. **Automatización máxima.** El usuario no debería hacer nada que el sistema pueda hacer solo. Cobros automáticos, notificaciones automáticas, recargos automáticos, cálculos automáticos.
3. **Facilidad para el usuario.** Formularios cortos, defaults inteligentes, ayudas contextuales, pasos guiados. Diseño anti-error. Si el usuario puede equivocarse, el sistema debe prevenirlo.
4. **Código limpio y profesional.** Sin atajos, sin TODO, sin código muerto. Cada clase tiene su responsabilidad. Tests para seguridad y flujos críticos.

---

## 1. QUÉ ES ADMINDI

Plataforma de **administración financiera y operativa** para dueños de inmuebles en renta. Entra en acción cuando el inquilino ya vive ahí. NO es portal de búsqueda, NO es evaluación crediticia, NO es despacho legal, NO genera contratos.

**Analogía:** El "SAT + contador + WhatsApp Business + mesa de ayuda" del dueño de inmuebles.

---

## 2. STACK TECNOLÓGICO

```
Backend:    Java 21 + Spring Boot 3 + Spring Security 6
Frontend:   React 18+ + TypeScript + Vite
Base datos: PostgreSQL 16 con Row Level Security
Cache:      Redis 7+ (JWT blacklist, rate limiting, cache de sesión)
Storage:    Cloudflare R2 (Cloudflare R2-compatible, 10GB gratis, cero egress) para fotos y PDFs
Mensajería: WhatsApp Cloud API
Orquestador: n8n (flujos de WhatsApp, recordatorios, escalamiento)
Pagos:      Mercado Pago Checkout Pro
CI/CD:      GitHub Actions
```

---

## 3. ROLES Y ACCESO

### 3.1 Roles del sistema

| Rol | Descripción | MFA | Multi-contexto |
|-----|-------------|-----|----------------|
| SUPER_ADMIN | Administra la plataforma, crea dueños, gestiona proveedores de plataforma | Obligatorio | No (acceso global) |
| OWNER | Dueño de inmuebles, ve todo lo suyo | Obligatorio | No (solo su contexto) |
| PROPERTY_ADMIN | Administrador contratado por el dueño | Obligatorio | Sí (puede trabajar para varios dueños) |
| ACCOUNTANT | Contador del dueño, solo lectura financiera | Obligatorio | Sí (puede llevar varios dueños) |
| TENANT | Inquilino, solo ve su contrato y pagos | Recomendado | Sí (puede rentar en varios lugares) |
| REAL_ESTATE_AGENT | Agente inmobiliario, gestiona vacancias | Recomendado | Sí (puede atender varios dueños) |
| MAINTENANCE_PROVIDER | Proveedor de mantenimiento, gestiona tickets | Recomendado | Sí (puede atender varios dueños) |

### 3.2 Autenticación en dos pasos

```
Paso 1: POST /api/auth/login
  → Verificar email + contraseña (Argon2) + MFA (TOTP)
  → Consultar user_context_assignments
  → Si 0 contextos → error "Sin acceso"
  → Si 1 contexto → emitir token FULL directo (sin paso 2)
  → Si 2+ contextos → emitir token BASE + lista de contextos

Paso 2: POST /api/auth/select-context (solo si hay múltiples)
  → Requiere token BASE válido
  → Emitir token FULL con ownerId + role + permissions

Cambio: POST /api/auth/switch-context
  → Requiere token FULL válido
  → Emite nuevo par de tokens para otro contexto sin re-login
```

### 3.3 Estructura del JWT

```json
{
  "sub": "user_uuid",
  "type": "BASE | FULL",
  "ownerId": "owner_uuid | null",
  "role": "OWNER | PROPERTY_ADMIN | ...",
  "permissions": ["PAYMENT_VIEW", "PAYMENT_APPLY", ...],
  "providerType": "MAINTENANCE | REAL_ESTATE_AGENT | null",
  "iss": "admindi",
  "aud": "admindi-api",
  "jti": "unique-id",
  "iat": 1710000060,
  "exp": 1710000960
}
```

**Tokens:**
- Access token: 15 min, RS256, en memoria del navegador (NUNCA localStorage).
- Refresh token: 7 días, cookie HttpOnly + Secure + SameSite=Strict.
- Rotación: al usar refresh, se revoca el anterior y emite nuevo par.
- Detección de reutilización: si un refresh revocado se presenta → revocar toda la familia → forzar re-login.
- Token BASE solo permite: GET /api/auth/contexts y POST /api/auth/select-context.

---

## 4. SEGURIDAD — IMPLEMENTAR TODO DESDE EL DÍA 1

### 4.1 SecurityFilterChain

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/webhooks/**"))
        .cors(cors -> cors.configurationSource(corsConfig()))
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .headers(h -> h
            .httpStrictTransportSecurity(hsts -> hsts
                .maxAgeInSeconds(31536000).includeSubDomains(true).preload(true))
            .frameOptions(f -> f.deny())
            .contentTypeOptions(Customizer.withDefaults())
            .referrerPolicy(r -> r.policy(STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .permissionsPolicy(p -> p.policy("camera=(), microphone=(), geolocation=()"))
            .contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: https:; connect-src 'self' https://api.mercadopago.com")))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            .requestMatchers("/api/webhooks/**").permitAll()
            .requestMatchers("/api/actions/preview/**").permitAll()
            .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
            .anyRequest().authenticated())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
}
```

### 4.2 JwtAuthenticationFilter — en CADA request

```
1. Extraer token del header Authorization: Bearer ...
2. Verificar firma RS256
3. Verificar expiración
4. Verificar que jti NO está en blacklist de Redis (revoked:{jti})
5. Si type=BASE y el endpoint NO es /auth/contexts o /auth/select-context → 403 CONTEXT_REQUIRED
6. Extraer ownerId del token y establecer TenantContext.setCurrentOwner(ownerId)
7. Establecer SecurityContext con roles y permisos
```

### 4.3 Row Level Security en PostgreSQL

```sql
-- Aplicar en CADA tabla que tenga datos de un dueño
ALTER TABLE properties ENABLE ROW LEVEL SECURITY;
ALTER TABLE properties FORCE ROW LEVEL SECURITY;
CREATE POLICY owner_isolation ON properties
    USING (owner_id = current_setting('app.current_owner')::UUID);

-- El usuario de BD de la app NO es owner de las tablas
-- El usuario de BD de la app NO tiene BYPASSRLS
-- Antes de cada query: SET app.current_owner = '{ownerId del JWT}'
-- SUPER_ADMIN usa usuario de BD separado CON BYPASSRLS
```

### 4.4 Rate limiting (Bucket4j + Redis)

```
POST /api/auth/login:            5 req / 15 min por IP
POST /api/auth/select-context:  10 req / min por usuario
POST /api/auth/refresh:         10 req / min por usuario
POST /api/payments/**:          30 req / min por usuario
POST /api/webhooks/**:         100 req / min por IP
GET  /api/**:                  200 req / min por usuario
POST /api/**:                   60 req / min por usuario
DELETE /api/**:                 10 req / min por usuario
```

Después de 5 intentos fallidos de login por cuenta → bloqueo 30 min.
CAPTCHA (hCaptcha) después del 3er intento fallido.

### 4.5 Validación de entrada — TODOS los DTOs

```java
// Usar Jakarta Bean Validation en CADA DTO, sin excepción
public record CreateLeaseRequest(
    @NotNull UUID unitId,
    @NotNull UUID tenantId,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @Positive @DecimalMax("999999.99") BigDecimal monthlyRent,
    @Min(1) @Max(28) Integer paymentDueDay,
    @Positive @DecimalMax("999999.99") BigDecimal deposit,
    @Min(0) @Max(365) Integer noticePeriodDays,
    @Size(max = 1000) String specialNotes
) {}
```

### 4.6 Verificación HMAC de webhooks

```
WhatsApp: X-Hub-Signature-256 → HMAC-SHA256 con App Secret → MessageDigest.isEqual (constant-time)
Mercado Pago: x-signature → ts=TIMESTAMP,v1=HASH → rechazar si timestamp > 5 min (replay protection)
n8n: X-API-Key header → comparación constant-time
TODOS: idempotencia con Redis SETNX + TTL 24h sobre request ID
```

### 4.7 Audit log — TODA acción de escritura

```java
// Registrar en CADA POST/PUT/DELETE:
// - quién (userId, role)
// - qué (endpoint, recurso, valores anteriores/nuevos)
// - desde dónde (IP, User-Agent)
// - cuándo (timestamp)
// - contexto (ownerId)
```

### 4.8 Operaciones destructivas

```
Eliminar inmueble, eliminar expediente, eliminar inquilino, cambiar permisos sensibles:
→ Requiere reautenticación (contraseña + MFA)
→ Advertencia clara de impacto
→ Audit log detallado
→ Soft delete cuando sea posible
```

---

## 5. MODELO DE DATOS PRINCIPAL

```
User
├── id (UUID, PK)
├── email (unique)
├── password_hash (Argon2)
├── name
├── phone
├── mfa_secret (encrypted)
├── mfa_enabled (boolean)
├── is_active
├── created_at, updated_at

UserContextAssignment
├── id (UUID, PK)
├── user_id (FK User)
├── owner_id (FK Owner, NULL para SUPER_ADMIN)
├── role (ENUM: SUPER_ADMIN, OWNER, PROPERTY_ADMIN, ACCOUNTANT, TENANT, REAL_ESTATE_AGENT, MAINTENANCE_PROVIDER)
├── permission_template_id (FK)
├── custom_permissions (JSONB, override de plantilla)
├── is_active
├── assigned_at, assigned_by, revoked_at, revoked_by

Owner
├── id (UUID, PK)
├── user_id (FK User)
├── business_name / name
├── rfc
├── phone, email
├── tax_regime (ENUM: GENERAL_606, RESICO)
├── is_active
├── created_at

OwnerServiceConfig
├── owner_id (FK Owner, PK)
├── maintenance_flow_mode (ENUM: PLATFORM, OWNER_TEAM, MIXED)
├── vacancy_flow_mode (ENUM: PLATFORM_AGENT, OWNER_AGENT, MIXED, NONE)
├── default_maintenance_provider_id (FK ServiceProvider)
├── default_vacancy_agent_id (FK ServiceProvider)

OwnerOnboarding
├── owner_id (FK Owner, PK)
├── services_configured, first_property_created, first_unit_created
├── first_lease_created, first_tenant_invited, onboarding_completed
├── completed_at

Property
├── id (UUID, PK)
├── owner_id (FK Owner) — RLS column
├── name, address, type (ENUM: BUILDING, HOUSE, COMMERCIAL, WAREHOUSE)
├── predial_account (obligatorio para CFDI)
├── description
├── created_at

PropertyServiceConfig (override por inmueble)
├── property_id (FK Property, PK)
├── maintenance_flow_mode, vacancy_flow_mode (overrides, nullable)
├── maintenance_provider_id, vacancy_agent_id (overrides)

Unit
├── id (UUID, PK)
├── property_id (FK Property)
├── owner_id (FK Owner) — RLS column
├── name / number
├── type (ENUM: APARTMENT, COMMERCIAL, OFFICE, WAREHOUSE, HOUSE)
├── square_meters, bedrooms, bathrooms, floor
├── features (JSONB: parking, storage, terrace, etc.)
├── status (ENUM: VACANT, VACANCY_LISTED, OCCUPIED, VACATING)
├── created_at

PropertyPhoto
├── id (UUID, PK)
├── property_id / unit_id (FK)
├── owner_id (FK Owner) — RLS column
├── category (ENUM: FACADE, LIVING_ROOM, KITCHEN, BATHROOM, BEDROOM, COMMON_AREA, DETAIL, DAMAGE)
├── purpose (ENUM: BASELINE, CURRENT, MOVE_OUT_INSPECTION, MAINTENANCE)
├── description, storage_key, uploaded_by, captured_at, created_at

Tenant
├── id (UUID, PK)
├── user_id (FK User)
├── owner_id (FK Owner) — RLS column
├── name, phone, email
├── rfc (para facturación, opcional)
├── curp (opcional, encrypted AES-256-GCM)
├── emergency_contact_name, emergency_contact_phone
├── is_active, created_at

Lease
├── id (UUID, PK)
├── unit_id (FK Unit)
├── tenant_id (FK Tenant)
├── owner_id (FK Owner) — RLS column
├── start_date, end_date
├── monthly_rent (BigDecimal)
├── payment_due_day (1-28)
├── deposit (BigDecimal)
├── notice_period_days
├── annual_increase_type (ENUM: FIXED, PERCENTAGE, INPC, NONE)
├── annual_increase_value (BigDecimal, nullable)
├── early_termination_penalty (BigDecimal, nullable)
├── late_fee_type (ENUM: FIXED, PERCENTAGE)
├── late_fee_value (BigDecimal)
├── late_fee_starts_day (int)
├── included_services (JSONB: water, electricity, gas, internet)
├── contract_pdf_storage_key
├── special_notes
├── status (ENUM: ACTIVE, TERMINATED, TERMINATED_EARLY, DELETED)
├── deleted_at, deleted_by
├── created_at

MonthlyLedger
├── id (UUID, PK)
├── lease_id (FK Lease)
├── owner_id (FK Owner) — RLS column
├── year, month
├── base_rent, extra_charges, late_fee, discounts
├── total_expected (calculado)
├── total_paid
├── balance (total_expected - total_paid)
├── status (ENUM: OPEN, PARTIALLY_PAID, PAID, SETTLED_BY_AGREEMENT, OVERDUE)
├── agreement_id (FK PaymentAgreement, nullable)
├── created_at, updated_at

Payment
├── id (UUID, PK)
├── monthly_ledger_id (FK MonthlyLedger)
├── owner_id (FK Owner) — RLS column
├── amount (BigDecimal)
├── payment_method (ENUM: SPEI, MERCADO_PAGO_CARD, MERCADO_PAGO_BALANCE, MANUAL)
├── payment_form_sat (ENUM: 01_CASH, 03_TRANSFER, 04_CARD, 28_DEBIT)
├── reference
├── payment_date
├── spei_proof_storage_key (foto del comprobante)
├── mercadopago_payment_id
├── status (ENUM: RECEIVED, PENDING_DATA, PENDING_VALIDATION, VALIDATED, MANUAL_REVIEW, REJECTED, APPLIED)
├── validated_by, validated_at
├── created_at

PaymentAgreement
├── id (UUID, PK)
├── lease_id (FK Lease)
├── owner_id (FK Owner) — RLS column
├── type (ENUM: MOVE_DUE_DATE, INSTALLMENTS, DISCOUNT)
├── original_amount, agreed_amount
├── installment_count (nullable)
├── new_due_date (nullable)
├── reason
├── status (ENUM: REQUESTED, PENDING_OWNER_APPROVAL, APPROVED, REJECTED, ACTIVE, FULFILLED, BREACHED, CANCELLED)
├── requested_at, approved_at, approved_by
├── created_at

VacateRequest
├── id (UUID, PK)
├── lease_id (FK Lease)
├── owner_id (FK Owner) — RLS column
├── requested_by_tenant_id
├── tentative_move_out_date
├── is_early_termination
├── penalty_amount (calculado)
├── checklist (JSONB: items con completed flag y timestamp)
├── deposit_original, deposit_deductions_breakdown (JSONB), deposit_refund_amount
├── status (ENUM: PENDING_REVIEW, IN_PROGRESS, INSPECTION_PENDING, LIQUIDATION_PENDING, COMPLETED, CANCELLED)
├── completed_at
├── created_at

ServiceProvider (unificado: mantenimiento + agentes inmobiliarios)
├── id (UUID, PK)
├── user_id (FK User, nullable — si ya tiene cuenta)
├── owner_id (FK Owner, NULL si es de plataforma) — RLS column
├── provider_type (ENUM: MAINTENANCE, REAL_ESTATE_AGENT)
├── name, phone, email, company_name
├── compensation_model (ENUM: MONTHLY_SALARY, MONTHLY_CONTRACT, PER_SERVICE, COMMISSION)
├── commission_value (BigDecimal, nullable)
├── is_platform_provider (boolean)
├── is_active
├── created_by, created_at

MaintenanceTicket
├── id (UUID, PK)
├── unit_id (FK Unit)
├── lease_id (FK Lease)
├── owner_id (FK Owner) — RLS column
├── reported_by_tenant_id
├── assigned_provider_id (FK ServiceProvider)
├── category (ENUM: PLUMBING, ELECTRICAL, PAINTING, LOCKSMITH, GENERAL, OTHER)
├── description, urgency (ENUM: LOW, MEDIUM, HIGH)
├── status (ENUM: NEW, ASSIGNED, ACCEPTED, BUDGET_SENT, BUDGET_APPROVED, BUDGET_REJECTED, IN_PROGRESS, COMPLETED, CANCELLED)
├── created_at, updated_at

MaintenanceBudget
├── id (UUID, PK)
├── ticket_id (FK MaintenanceTicket)
├── owner_id (FK Owner) — RLS column
├── provider_id (FK ServiceProvider)
├── amount, description, materials_cost, labor_cost
├── estimated_days
├── status (ENUM: PENDING, APPROVED, REJECTED)
├── approved_by, approved_at
├── created_at

ExpenseCommitment
├── id (UUID, PK)
├── owner_id (FK Owner) — RLS column
├── property_id (FK Property)
├── budget_id (FK MaintenanceBudget, nullable)
├── concept, provider_name, provider_rfc
├── amount
├── status (ENUM: COMMITTED, PAID, CANCELLED)
├── payment_reference, payment_date
├── has_cfdi (boolean)
├── confirmed_by_owner, confirmed_by_provider
├── evidence_storage_key
├── created_at, paid_at

VacancyListing
├── id (UUID, PK)
├── unit_id (FK Unit)
├── owner_id (FK Owner) — RLS column
├── agent_id (FK ServiceProvider)
├── suggested_rent
├── status (ENUM: NOTIFIED, ACCEPTED, ACTIVE, RENTED, CANCELLED)
├── agent_notified_at, agent_accepted_at, rented_at
├── new_lease_id (FK Lease, nullable)
├── created_at

MonthlySummary (resumen por inmueble/mes)
├── id (UUID, PK)
├── property_id (FK Property)
├── owner_id (FK Owner) — RLS column
├── year, month
├── total_income_collected, total_income_pending
├── total_expenses_paid, total_expenses_committed
├── active_agreements_count
├── net_result (income_collected - expenses_paid)
├── vacant_units, occupied_units
├── created_at, recalculated_at

ActionToken (links seguros de WhatsApp)
├── id (UUID, PK)
├── token (unique, tkn_ + 32 chars crypto-secure)
├── action_type (ENUM: TICKET_ASSIGNED, BUDGET_SUBMITTED, VACANCY_NOTIFICATION, etc.)
├── target_entity_id, recipient_user_id, owner_id
├── expires_at (72 horas default)
├── viewed_at, responded_at
├── status (ENUM: PENDING, VIEWED, ACCEPTED, REJECTED, EXPIRED)
├── response_notes, ip_address, user_agent
├── created_at

ProviderActivityLog
├── id (UUID, PK)
├── provider_id, owner_id, entity_id
├── type (ENUM: NOTIFIED, VIEWED, ACCEPTED, REJECTED, BUDGET_SUBMITTED, WORK_STARTED, WORK_COMPLETED, PAYMENT_CONFIRMED, VACANCY_ACCEPTED, VACANCY_UPDATE, VACANCY_RENTED, EVIDENCE_UPLOADED)
├── notes, ip_address, user_agent
├── action_token_id (nullable)
├── occurred_at

ProviderMetrics (calculado mensualmente)
├── id, provider_id, owner_id, period_year, period_month
├── tickets_assigned, tickets_accepted, tickets_completed
├── avg_response_time_minutes, avg_completion_time_hours
├── total_budgeted, total_paid
├── vacancies_notified, vacancies_accepted, vacancies_rented
├── avg_days_to_rent, total_commissions
├── calculated_at

AuditLog
├── id (UUID, PK)
├── actor_user_id, actor_role, owner_id
├── event_type, resource_type, resource_id
├── details (JSONB: old_values, new_values)
├── ip_address, user_agent, request_id
├── created_at

WhatsAppMessage (historial)
├── id (UUID, PK)
├── owner_id — RLS column
├── direction (ENUM: INBOUND, OUTBOUND)
├── from_phone, to_phone
├── message_type (ENUM: TEXT, IMAGE, DOCUMENT, INTERACTIVE)
├── content, media_storage_key
├── related_entity_type, related_entity_id
├── whatsapp_message_id
├── created_at
```

---

## 6. LOS 4 PILARES (en orden de prioridad)

### Pilar 1 — Cobranza automatizada

```
Día 1 del mes: @Scheduled genera MonthlyLedger por cada Lease activo
  → Calcula: renta base + cargos extra
  → Envía WhatsApp al inquilino con monto y link de Mercado Pago
  
Pago por Mercado Pago:
  → Webhook payment.updated → verificar HMAC → crear Payment VALIDATED → actualizar MonthlyLedger
  
Pago por SPEI:
  → Inquilino envía foto por WhatsApp → n8n recibe → Spring Boot extrae datos con IA
  → Crear Payment PENDING_VALIDATION → admin/dueño revisa → VALIDATED → actualizar MonthlyLedger

Escalamiento automático (configurable por dueño/inmueble):
  Día 1: recordatorio amigable WhatsApp
  Día 5: segundo aviso tono firme
  Día 8: recargo automático aplicado (fijo o %)
  Día 15: aviso formal PDF por WhatsApp
  Día 30+: notificación al dueño con recomendación
```

### Pilar 2 — Expediente financiero mensual

```
MonthlyLedger por contrato: renta, cargos, descuentos, pagos, saldo, estado
MonthlySummary por inmueble: ingresos cobrados/pendientes, egresos pagados/comprometidos, neto
Vista agregada por dueño: todos sus inmuebles
Exportable a Excel/PDF para el contador
Reporte de facturación con datos para CFDI (RFC, método PUE/PPD, forma de pago, cuenta predial)
Cálculo orientativo de deducción ciega (35%) vs comprobable para la declaración
```

### Pilar 3 — Comunicación WhatsApp inteligente

```
Automáticos: aviso de renta, recordatorios, confirmación de pago, recargo, mantenimiento, convenios
Del inquilino: comprobante SPEI, reporte de falla, solicitar convenio, avisar desocupación
Chatbot IA (GPT-4 vía n8n): consultar saldo, crear ticket, preguntas frecuentes, 24/7
Desocupación: detectar intención → confirmar → VacateRequest → checklist → notificar dueño+admin
Todos los mensajes se loggean en WhatsAppMessage
```

### Pilar 4 — Gestión de mantenimiento

```
Inquilino reporta por WhatsApp → MaintenanceTicket automático
Según configuración → asignar proveedor (plataforma o propio)
Notificar proveedor por WhatsApp con ActionToken (link seguro, 72h, requiere auth)
Proveedor acepta → envía presupuesto → dueño aprueba/rechaza desde portal
Al aprobar → ExpenseCommitment (egreso comprometido)
Al completar → proveedor sube evidencia → dueño confirma pago → egreso pagado
Todo queda en ProviderActivityLog con timestamps, IP, dispositivo
```

---

## 7. SERVICIOS DE PLATAFORMA vs PROPIOS

El SUPER_ADMIN agrega proveedores de plataforma (mantenimiento y agentes inmobiliarios).
El dueño agrega sus propios proveedores.
Al crear un dueño, los de plataforma se ofrecen en el onboarding (no se imponen).

**Configuración por dueño:**
```
MaintenanceFlowMode: PLATFORM | OWNER_TEAM | MIXED
VacancyFlowMode: PLATFORM_AGENT | OWNER_AGENT | MIXED | NONE
```

**Override por inmueble:** PropertyServiceConfig sobreescribe la config del dueño.
**Cambio en cualquier momento** desde Configuración > Servicios.

---

## 8. CICLO DE VIDA DE UNA UNIDAD

```
1. VACANTE → 2. Agente notificado (WhatsApp + ActionToken)
→ 3. Buscando inquilino (agente reporta avances)
→ 4. Cliente encontrado (agente avisa)
→ 5. Dueño registra nuevo inquilino (formulario + PDF del contrato)
→ 6. OCUPADO (cobros automáticos, mantenimiento, convenios)
→ 7. Desocupación (inquilino avisa por WhatsApp, checklist, liquidación)
→ 8. Expediente archivado (dueño puede eliminar cuando quiera)
→ Vuelve a 1.
```

**Al registrar nuevo inquilino (paso 5):**
- Unidad: VACANT → OCCUPIED
- VacancyListing: → RENTED
- Se genera primer MonthlyLedger (prorrateado si es a mitad de mes)
- WhatsApp de bienvenida al inquilino
- Notificación al dueño y al agente

**Eliminar expediente anterior:**
- Reautenticación + MFA obligatorio
- Advertencia clara de qué se elimina
- Se conserva: contrato PDF (5 años), RFC, audit log, monto total (fiscal)
- Se elimina: MonthlyLedgers, pagos, convenios, tickets, fotos inspección, mensajes
- Se anonimizan: datos personales del inquilino (LFPDPPP)

---

## 9. CONTRATOS — SIN FIRMAS DIGITALES

ADMINDI NO genera, firma ni valida contratos. El flujo es:
1. El contrato se firma FUERA de ADMINDI (abogado, notario, el dueño).
2. El dueño sube el PDF firmado al crear el Lease.
3. ADMINDI usa los datos operativos capturados en el formulario (monto, fechas, recargos, etc.) como reglas de negocio para automatizar todo.

---

## 10. FACTURACIÓN CFDI — ENFOQUE POR FASES

**Fase 1 (v1): ADMINDI NO factura, prepara datos para el contador.**
- Genera reporte de facturación con: fecha, inquilino, RFC, monto, PUE/PPD, forma de pago, cuenta predial, clave SAT 80131500.
- Incluye: si el arrendatario es persona moral (retención ISR 10% + 2/3 IVA).
- Cálculo orientativo: deducción ciega (35%) vs comprobable.
- Exportable Excel/PDF.

**Fase 2+ (futuro): integración con API de facturación (Facturapi/Facturama). Feature premium.**

---

## 11. COMUNICACIÓN n8n ↔ SPRING BOOT

```
Evento ocurre en Spring Boot (pago recibido, ticket creado, etc.)
  ↓
Spring Boot envía webhook interno a n8n con ID del evento
  ↓
n8n llama a: GET /api/internal/notifications/{eventId}
  (autenticado con X-API-Key, restringido por IP)
  ↓
Spring Boot retorna: nombre, teléfono, email del destinatario + datos del mensaje
  ↓
n8n envía WhatsApp y/o email
  ↓
n8n NO almacena datos de contacto (los pide cada vez)
```

---

## 12. PERMISOS GRANULARES PARA PROPERTY_ADMIN

```
Plantillas:
- Administrador Operativo (gestión diaria, sin financiero sensible)
- Administrador Cobranza (pagos, convenios, reportes)
- Administrador Completo (todo excepto eliminar inmuebles/expedientes)
- Personalizado

Permisos individuales (cada uno con explicación de riesgo en UI):
PROPERTY_CREATE/UPDATE/DELETE, UNIT_CREATE/UPDATE/DELETE
TENANT_CREATE/UPDATE/DELETE, LEASE_CREATE/UPDATE/TERMINATE
PAYMENT_VIEW/REVIEW/APPLY, AGREEMENT_VIEW/APPROVE/REJECT
EXPENSE_VIEW/APPROVE/MARK_PAID, REPORT_VIEW/EXPORT
PROVIDER_MANAGE, VACANCY_MANAGE, EXPEDIENT_DELETE

Para permisos de riesgo ALTO (DELETE, TERMINATE, EXPEDIENT_DELETE):
→ Reautenticación del dueño + MFA + advertencia explícita de impacto
```

---

## 13. ENDPOINTS PRINCIPALES Y ACCESO POR ROL

```
SA=SUPER_ADMIN, OW=OWNER, PA=PROPERTY_ADMIN(con permiso), AC=ACCOUNTANT, TE=TENANT, RE=REAL_ESTATE_AGENT, MA=MAINTENANCE_PROVIDER

POST /auth/login, /auth/select-context, /auth/refresh → TODOS (rate limited)
GET/POST/DELETE /admin/** → solo SA
GET/POST/PUT /properties/** → SA, OW, PA
GET/POST /leases/** → SA, OW, PA, AC(solo GET)
GET /leases/my → TE (solo su contrato)
DELETE /leases/{id}/expedient → OW, PA (reauth+MFA)
GET/POST /payments/** → SA, OW, PA, AC(solo GET)
GET /payments/my, POST /payments → TE (solo sus pagos)
POST /payments/{id}/apply → SA, OW, PA
GET/POST /agreements/** → SA, OW, PA
GET /agreements/my, POST /agreements → TE
GET/POST /maintenance/tickets/** → SA, OW, PA
GET /maintenance/tickets/my → TE
GET /maintenance/tickets/assigned → MA (solo asignados)
POST /maintenance/budgets → MA
POST /maintenance/budgets/{id}/approve → SA, OW, PA
GET /vacancies/** → SA, OW, PA
GET /vacancies/assigned → RE (solo asignadas)
POST /vacancies/{id}/accept,update,client-found → RE
POST /vacate-requests → TE (solo su contrato)
GET /reports/** → SA, OW, PA, AC
GET /reports/export → SA, OW, PA, AC (audit log)
GET /providers/my-profile → RE, MA (solo su expediente)
GET /actions/preview/{token} → PÚBLICO (info mínima, sin auth)
POST /actions/respond/{token} → AUTH requerido (verificar recipient)
POST /webhooks/whatsapp → PÚBLICO (validar HMAC)
POST /webhooks/mercadopago → PÚBLICO (validar HMAC + replay)
POST /webhooks/n8n → PÚBLICO (validar X-API-Key)
```

---

## 14. FRONTEND — PANTALLAS POR ROL

### SUPER_ADMIN: Dashboard global, Gestión de dueños (CRUD), Proveedores de plataforma (CRUD), Auditoría global, Gestión de usuarios, Configuración del sistema.

### OWNER: Dashboard financiero, Mis inmuebles, Unidades (con galería de fotos), Contratos, Cobranza (pagos pendientes, validar SPEI), Convenios, Mantenimiento (tickets, presupuestos, expediente proveedor), Vacancias, Reportes (MonthlySummary, reporte facturación para contador), Configuración (perfil, servicios, admins con permisos, contadores, cobranza, overrides por inmueble), Onboarding (primera vez).

### PROPERTY_ADMIN: Mismas pantallas que OWNER filtradas por permisos. Selector de contexto en navbar si trabaja para varios dueños.

### ACCOUNTANT: Dashboard financiero (solo lectura), Reporte de facturación para CFDI, Resumen mensual por inmueble, Historial de pagos. Todo exportable Excel/PDF. Selector de contexto si lleva varios dueños.

### TENANT: Dashboard (estado de renta, historial pagos), Mis pagos (pagar, enviar comprobante), Mantenimiento (crear ticket, ver estado), Convenios (solicitar, ver estado), Desocupación (solicitar, ver checklist), Mi perfil (editar datos, exportar datos ARCO, solicitar eliminación).

### REAL_ESTATE_AGENT: Dashboard (vacancias activas, métricas), Mis vacancias (aceptar, reportar avances, reportar cliente), Mi expediente (métricas, historial).

### MAINTENANCE_PROVIDER: Dashboard (tickets nuevos, en progreso), Mis tickets (aceptar, enviar presupuesto, marcar progreso, completar con evidencia), Mi expediente (métricas, historial).

---

## 15. REPORTE MENSUAL CONTABLE

El reporte tiene 4 secciones y es descargable en Excel y PDF:

**Sección A — Ingresos cobrados:** Cada pago con fecha, inquilino, RFC, unidad, concepto, método PUE/PPD/complemento, forma de pago SAT (01 efectivo, 03 transferencia, 04 tarjeta), monto, si requiere retención ISR+IVA (persona moral). Clave SAT: 80131500. Cuenta predial en encabezado.

**Sección B — Pendiente de cobro:** Inquilinos que no han pagado completo, con monto esperado, cobrado, pendiente y estado.

**Sección C — Egresos:** Cada gasto con fecha, concepto, proveedor, RFC proveedor, estado (pagado/comprometido), si tiene CFDI, monto. Incluye predial.

**Sección D — Cálculo orientativo:** Ingresos cobrados, deducción ciega (35%), deducción comprobable (suma de egresos con CFDI + predial), base gravable por cada opción, ISR retenido por personas morales (acreditable).

---

## 16. ROADMAP DE DESARROLLO

### Fase 0: Fundación (4-6 semanas)
- Spring Boot 3 + PostgreSQL + Redis + React + Vite
- Entidades base: User, Owner, Property, Unit, ServiceProvider, UserContextAssignment
- SecurityFilterChain completo con JWT 2 pasos + MFA
- RLS en PostgreSQL en TODAS las tablas
- Subida de fotos a Cloudflare R2, galería de inmuebles
- n8n + WhatsApp Cloud API (sandbox)
- CI/CD con GitHub Actions
- Onboarding del dueño

### Fase 1: Cobranza + Expediente (6-8 semanas)
- Lease, Tenant, MonthlyLedger, Payment
- Generación automática de cargos mensuales
- Mercado Pago Checkout Pro + webhook
- Flujo SPEI por WhatsApp (foto → IA → validación)
- Recargos automáticos configurables
- MonthlySummary
- Dashboard financiero del dueño
- Reporte de facturación para contador (Excel/PDF)
- Recordatorios de pago por WhatsApp

### Fase 2: Comunicación + Mantenimiento + Agentes (6-8 semanas)
- Chatbot IA por WhatsApp
- Escalamiento automático de cobro
- MaintenanceTicket, MaintenanceBudget, ExpenseCommitment
- Presupuestos: recibir → aprobar → pagar con evidencia
- PaymentAgreement (convenios)
- VacateRequest (desocupación por WhatsApp + checklist)
- VacancyListing (notificación a agentes + tracking)
- ActionToken (links seguros por WhatsApp)
- ProviderActivityLog y ProviderMetrics
- Archivo y eliminación de expedientes

### Fase 3: Inteligencia + Escala (8-12 semanas)
- Renovación automática con sugerencia INPC
- OCR para recibos de servicios
- Exportación avanzada de reportes
- Dashboard comparativo mes a mes con gráficas
- Seguridad fase 2: Vault, Docker distroless, Cloudflare WAF, Hibernate Envers
- ARCO endpoints (LFPDPPP)
