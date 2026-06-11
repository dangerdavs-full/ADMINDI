# Deployment V55/V56 — Chatbot WhatsApp + IA + Banxico adaptativo

Guía operativa paso a paso para poner el nuevo chatbot en producción. Sigue
en ORDEN: cada paso valida el anterior.

> ⚠️ **No pegues secretos en commits, issues, chats ni screenshots.**
> Si expones una API key por accidente, revócala INMEDIATAMENTE y genera una nueva.

---

## 0. Prerrequisitos ya desplegados

- [ ] Backend anterior (V54) corriendo estable con Twilio salida + 34 templates
- [ ] Base de datos PostgreSQL accesible con `DB_USERNAME` / `DB_PASSWORD`
- [ ] Redis levantado (usado por rate limiter y blacklist JWT)
- [ ] `ENCRYPTION_KEY` configurada en el entorno (columnas cifradas funcionan)
- [ ] `SUPER_ADMIN` con acceso y MFA configurado

---

## 1. Rotar / crear la API key de Anthropic

**Si pegaste la key en cualquier lugar que no sea tu gestor de secretos:**

1. Entra a <https://console.anthropic.com/settings/keys>
2. Click sobre la key comprometida → **Revoke**
3. **Create new key** con nombre `admindi-prod-2026-XX`
4. Copiarla **SOLO** al servidor de producción — nunca al chat, al repo, ni a un screenshot

**Configurar spend limits antes de abrir tráfico:**

1. Settings → Billing → **Usage limits**
2. Soft limit: `$20 USD / month` (avisa por email al llegar)
3. Hard limit: `$50 USD / month` (corta las llamadas)
4. Puedes subirlo después con evidencia de uso real

---

## 2. Aplicar migraciones de DB

```bash
cd backend
SPRING_PROFILES_ACTIVE=prod \
  DB_USERNAME=postgres \
  DB_PASSWORD=<tu-password> \
  ENCRYPTION_KEY=<tu-key-base64> \
  mvn flyway:migrate
```

Debe aplicar:

- `V55__ai_whatsapp_pin_and_banxico_schema.sql`
- `V56__ai_accounting_fields.sql`

Verifica:

```sql
SELECT table_name FROM information_schema.tables
WHERE table_name IN (
  'tenant_whatsapp_pin',
  'whatsapp_conversation_state',
  'ai_usage_log',
  'banxico_scrape_schema',
  'banxico_scrape_failure'
);
-- Debe devolver 5 filas.

SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
-- Debe incluir 56, 55 arriba.

SELECT version, active, source FROM banxico_scrape_schema;
-- Debe haber una fila seed v1 con active=TRUE.
```

---

## 3. Configurar variables de entorno en el servidor

**NO poner los valores en el repo.** Usa systemd, Docker secrets, o tu gestor
de nube (AWS SSM, GCP Secret Manager, Vault, etc).

Archivo `/etc/admindi/admindi.env` con permisos `0600` (solo root lee):

```bash
# ── Generales (ya debías tener esto en V54) ──
SPRING_PROFILES_ACTIVE=prod
DB_USERNAME=postgres
DB_PASSWORD=...
ENCRYPTION_KEY=...
APP_URL=https://app.admindi.mx
ADMIN_USERNAME=...
ADMIN_PASSWORD=...

# ── Twilio (ya debías tener esto; verifica que el webhook público apunte a prod) ──
TWILIO_ENABLED=true
TWILIO_ACCOUNT_SID=AC...
TWILIO_AUTH_TOKEN=...
TWILIO_WHATSAPP_FROM=whatsapp:+12183957952
TWILIO_WEBHOOK_PUBLIC_URL=https://api.admindi.mx/api/webhooks/twilio/whatsapp

# ── Anthropic (NUEVO) ──
ANTHROPIC_ENABLED=true
ANTHROPIC_API_KEY=sk-ant-...   # la key NUEVA, no la comprometida
ANTHROPIC_MODEL=claude-sonnet-4-5
ANTHROPIC_DAILY_BUDGET=1.00    # empezar bajo y subir con evidencia

# ── Banxico CEP (NUEVO) ──
BANXICO_CEP_ENABLED=true
BANXICO_ADAPTIVE_AI=true

# ── Chatbot WhatsApp (NUEVO) ──
# Déjalo en false hasta que hagas el smoke test del paso 6.
WHATSAPP_BOT_ENABLED=false
```

Valida que systemd cargue el archivo:

```ini
# /etc/systemd/system/admindi-backend.service (extracto)
[Service]
EnvironmentFile=/etc/admindi/admindi.env
ExecStart=/usr/bin/java -jar /opt/admindi/admindi-backend.jar
```

```bash
sudo systemctl daemon-reload
sudo systemctl restart admindi-backend
sudo systemctl status admindi-backend
```

---

## 4. Validar arranque del backend

Revisa los logs tras el restart:

```bash
sudo journalctl -u admindi-backend -f --since "1 min ago"
```

**Qué esperar:**

- `[TWILIO] WhatsApp client initialized (provider=twilio, from=+121***952)`
- `[STARTUP-VALIDATOR] configuración OK (profiles=[prod], twilio=true, anthropic=true, bot=false, banxico=true)`

**Señales de alerta (revisa ANTES de habilitar el bot):**

- `[STARTUP-VALIDATOR] ERROR: ANTHROPIC_API_KEY no parece una key válida` → key mal pegada.
- `[STARTUP-VALIDATOR] ERROR: ENCRYPTION_KEY está vacía` → **crítico, no abrir tráfico hasta arreglar.**
- `[STARTUP-VALIDATOR] WARN: ANTHROPIC_DAILY_BUDGET ≤ 0` → presupuesto deshabilitado, un user puede quemar la cuenta.
- `Flyway: migration failed` → revisa el SQL, rollback con `flyway:undo` si está configurado.

---

## 5. Smoke test sin bot (solo validar OCR desde portal web)

Con `WHATSAPP_BOT_ENABLED=false` todavía, valida que Claude responde:

```bash
# Reemplaza TENANT_JWT con un token válido del inquilino de prueba
curl -X POST https://api.admindi.mx/api/payments/proofs/ocr \
  -H "Authorization: Bearer $TENANT_JWT" \
  -F "file=@test-receipt.jpg"
```

**Respuesta esperada** (200 OK):
```json
{
  "ok": true,
  "claveRastreo": "2026041500001234",
  "amount": "15000.00",
  "transferDate": "2026-04-15",
  "bankEmitter": "BBVA",
  "accountReceiver": "012180015012345678",
  "confidence": 0.92,
  "errorMessage": null
}
```

**Si da `ok=false, errorMessage="anthropic_disabled"`**: la key no está cargada. Revisa env vars.

**Si da `ok=false, errorMessage="daily_budget_exceeded"`**: el usuario ya excedió $1 USD hoy. Normal si hiciste muchas pruebas; espera 24h o sube el budget.

---

## 6. Smoke test del bot (prueba real con 1 inquilino)

1. Crea una cuenta de inquilino de prueba con un número WhatsApp REAL que tú controles (el tuyo).
2. Verifica que el teléfono esté cifrado bien en `users.phone`:
   ```sql
   SELECT id, name, role FROM users WHERE role='TENANT' AND active=true;
   ```
3. Activa el bot:
   ```bash
   sudo systemctl stop admindi-backend
   # Edita /etc/admindi/admindi.env: WHATSAPP_BOT_ENABLED=true
   sudo systemctl start admindi-backend
   ```
4. Desde tu WhatsApp personal, manda "hola" al número `whatsapp:+12183957952`.
5. Resultado esperado: el bot responde pidiéndote que elijas un NIP 4-6 dígitos (primera interacción).
6. Elige un NIP (ej. `1234`), luego mándale "1" para flujo de comprobante.
7. Sube una foto de comprobante SPEI real.
8. Valida en los logs:
   ```bash
   sudo journalctl -u admindi-backend -f | grep -E "BOT|OCR|CEP"
   ```

**Lo que debes ver:**
```
[TWILIO-WEBHOOK] inbound signature validated
[BOT] state=ASKING_PIN_SETUP
[BOT] state=MENU
[BOT] state=PROOF_WAITING_IMAGE
[OCR_RECEIPT] cost_usd=0.003 confidence=0.91
[CEP] proof ... validated OK
[BOT] state=MENU
```

---

## 7. Activar monitoreo en el panel SUPER_ADMIN

1. Entra como SUPER_ADMIN a `https://app.admindi.mx/dashboard`
2. Ve a la nueva pestaña **"Monitor Banxico"**
3. Verifica:
   - Schema v1 activo con origen `SEED`
   - Sin failures (tabla vacía — buena señal)
4. Abre la pestaña **"Búsqueda / Recovery"**:
   - Busca un inquilino TENANT → debe aparecer el botón `MessageCircle` (NIP)
   - El botón solo aparece para role=TENANT (no para OWNER/ADMIN)

---

## 8. Validar que categorización IA corre async

Hacer un pago de prueba (comprobante del paso 6 con CEP válido). Después de
~5-10 segundos, revisa en DB:

```sql
SELECT id, ai_category, ai_cfdi_use, ai_tax_deductible, ai_confidence, ai_last_run_at
FROM payments
WHERE ai_last_run_at IS NOT NULL
ORDER BY ai_last_run_at DESC
LIMIT 5;
```

Debes ver el pago más reciente con `ai_category='RENTA_BASE'` o similar.

**Si `ai_last_run_at` queda NULL indefinidamente**:

- Revisa logs por `[AI-ACCOUNTING]` — probablemente budget o key issue.
- Verifica que `@EnableAsync` esté activo (ya está en `BackendApplication.java`).

---

## 9. Chequeo mensual: reporte enriquecido

El primer día 1 del mes siguiente a la activación, el scheduler
`MonthlyReportScheduler` dispara el reporte con narrativa IA. Para no esperar,
puedes dispararlo manualmente en QA:

```sql
-- Simular escenario con pagos parciales
SELECT owner_id, COUNT(*) as pagos_parciales
FROM payments p
JOIN invoices i ON i.id = p.invoice_id
WHERE i.settlement_status = 'PARTIALLY_PAID'
  AND i.month_year = '2026-04'
GROUP BY owner_id;
```

En los logs del scheduler busca:
```
[MONTHLY-REPORT] monthly tick done: owners=N dispatched=M
[REPORT_NARRATIVE] cost_usd=0.01
```

---

## 10. Rollback seguro

Si algo sale mal después de activar, puedes revertir SIN tocar DB:

```bash
# Desactivar todo lo nuevo sin rollback de migraciones
sudo systemctl stop admindi-backend
# Edita /etc/admindi/admindi.env:
# WHATSAPP_BOT_ENABLED=false
# ANTHROPIC_ENABLED=false
# BANXICO_CEP_ENABLED=false   (si quieres volver al MOCK)
sudo systemctl start admindi-backend
```

Las tablas nuevas (V55/V56) quedan en DB pero vacías — no estorban. Los
campos `ai_*` en payments/expenses son nullables → el flujo legacy sigue
funcionando sin modificación.

---

## 11. Checklist de ciberseguridad pre-producción

- [ ] API key de Anthropic anterior **revocada** en la consola
- [ ] Nueva key solo existe en `/etc/admindi/admindi.env` con permisos `0600`
- [ ] Spend limit configurado en Anthropic ($50 USD hard max)
- [ ] `ENCRYPTION_KEY` respaldada en gestor de secretos (si se pierde, los datos cifrados son irrecuperables)
- [ ] `TWILIO_AUTH_TOKEN` rotado si alguna vez se filtró en chats
- [ ] Firewall permite salida solo a: `api.anthropic.com`, `api.twilio.com`, `www.banxico.org.mx`, y tu DB
- [ ] Backup de DB automático **antes** de correr las migraciones V55/V56
- [ ] Monitoring: alerta si `ai_usage_log` supera 100 USD/día agregado (sugerencia: consulta cada hora)
- [ ] Rate limit nginx/cloudflare por IP: máx 60 req/min hacia `/api/payments/proofs/ocr`
- [ ] WAF bloquea payloads `<script>` o `SELECT.*FROM` en `/api/webhooks/twilio` (defensa en profundidad aunque la firma HMAC ya protege)
- [ ] Log retention: mínimo 90 días para `audit_events` y `ai_usage_log`

---

## 12. Métricas clave para los primeros 7 días

Revisa cada 24h:

```sql
-- Uso de IA
SELECT DATE(created_at) as dia,
       purpose,
       COUNT(*) as calls,
       SUM(cost_usd) as costo_total,
       AVG(cost_usd) as costo_promedio
FROM ai_usage_log
WHERE created_at >= NOW() - INTERVAL '7 days'
GROUP BY dia, purpose
ORDER BY dia DESC, costo_total DESC;

-- Inquilinos activos en el bot
SELECT COUNT(DISTINCT user_id) FROM whatsapp_conversation_state
WHERE created_at >= NOW() - INTERVAL '7 days';

-- PIN bloqueos (posible abuso)
SELECT COUNT(*) FROM tenant_whatsapp_pin
WHERE locked_until > NOW();

-- Fallos de Banxico sin resolver
SELECT COUNT(*) FROM banxico_scrape_failure
WHERE resolved_by_ai_at IS NULL;
```

Si algún valor se dispara fuera de lo normal, investiga antes de que
afecte a más usuarios.

---

## Contactos de emergencia

Si un fallo crítico afecta a inquilinos reales:

1. **Desactiva el bot**: `WHATSAPP_BOT_ENABLED=false` + restart. El portal web sigue funcionando.
2. **Desactiva CEP**: `BANXICO_CEP_ENABLED=false` — vuelve al modo permisivo donde el dueño aprueba manual.
3. **Anthropic**: revoca key si sospechas compromiso.
4. **Twilio**: revoca auth token si sospechas compromiso (regenera en Twilio Console → reinicia backend).

Ninguno de estos pasos toca datos en DB; son reversibles en minutos.
