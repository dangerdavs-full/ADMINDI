# Guía de pruebas — ADMINDI (Primera entrega)

Procedimiento sugerido para evaluar la plataforma de punta a punta. Antes de empezar, sigue la sección **"Cómo ejecutar el proyecto"** del [README](README.md): base de datos creada, `application-secrets.yml` configurado, backend en `http://localhost:8080` y frontend en `http://localhost:3000`.

> La plataforma funciona completa **sin credenciales externas** (Twilio, Mercado Pago, Anthropic). Los envíos de WhatsApp/correo no se pierden en silencio: quedan **auditados** como `WHATSAPP_SKIPPED` / `EMAIL_SKIPPED`, lo que permite verificar el flujo de notificaciones sin cuentas de terceros.

---

## 1. Pruebas funcionales (interfaz web)

### P1 — Autenticación del SUPER_ADMIN

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | Abrir `http://localhost:3000` | Redirige a `/login` |
| 2 | Iniciar sesión: usuario `DavidSuperAdmin-2026` + contraseña definida en `app.admin.password` | Entra al panel global con menú: **Gestión de Dueños, Proveedores, Auditoría, Búsqueda / Recovery, Monitor Banxico, Archivo** |

### P2 — Crear un dueño (OWNER)

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | Ir a **Gestión de Dueños** → crear dueño (nombre, username, email, teléfono) | Aparece un aviso con el **username y la contraseña temporal** del dueño — anotarla, se muestra una sola vez |
| 2 | Revisar **Auditoría** | Queda registrado el evento de creación |

### P3 — Primer login del dueño y onboarding

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | Cerrar sesión e iniciar con las credenciales temporales del dueño | El sistema **obliga a cambiar la contraseña** en el primer acceso |
| 2 | Completar el asistente de bienvenida (datos de perfil) | Entra al panel del dueño: **Resumen, Inmuebles, Arrendatarios, Pendientes, Bandeja de decisiones, Convenios, Presupuestos, Equipo y proveedores, Mi perfil, Alertas y canales** |

### P4 — Alta de inmueble y unidades

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | Como dueño, ir a **Inmuebles** → registrar una propiedad con al menos una unidad rentable | La propiedad aparece en el listado con sus unidades y estado de ocupación |

### P5 — Alta de inquilino y contrato

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | Ir a **Arrendatarios** → dar de alta un inquilino | Se generan credenciales temporales para el inquilino (mismo patrón que P2) |
| 2 | Crear el contrato de renta de una unidad (monto mensual, duración, día de corte) | La unidad queda ocupada y se genera el ciclo de facturación del inquilino |

### P6 — Registro y validación de un pago

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | Iniciar sesión como el inquilino (cambio de contraseña forzado en primer acceso) | Panel del inquilino con su estado de cuenta y pagos |
| 2 | Registrar un pago de renta adjuntando un comprobante (imagen/PDF) | El pago queda **pendiente de validación** |
| 3 | Volver como dueño → **Pendientes / Bandeja de decisiones** | Aparece el comprobante por validar; al aprobarlo, la factura pasa a **pagada** y el movimiento queda en el historial del inquilino |

### P7 — Ticket de mantenimiento

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | Como inquilino, crear un ticket de mantenimiento describiendo un desperfecto | Ticket creado en estado inicial |
| 2 | Como dueño, revisarlo en su bandeja | El dueño puede autorizar/rechazar y asignar proveedor; cada cambio de estado queda auditado |

### P8 — Historial de notificaciones (degradación limpia)

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | Como dueño, abrir **Alertas y canales** después de ejecutar P2–P6 | Los eventos de notificación de los flujos anteriores aparecen registrados; sin credenciales Twilio/SMTP figuran como **SKIPPED** (no hay errores ni caídas) |

---

## 2. Pruebas de seguridad

| # | Prueba | Cómo ejecutarla | Resultado esperado |
|---|---|---|---|
| S1 | Rutas protegidas | Sin sesión, abrir `http://localhost:3000/dashboard` | Redirección inmediata a `/login` |
| S2 | Rate-limit de login | Intentar iniciar sesión con contraseña incorrecta más de 10 veces seguidas | El backend responde `429 Too Many Requests` y bloquea temporalmente los intentos |
| S3 | API sin token | `curl -i http://localhost:8080/api/admin/owners` | `401/403` — ningún dato sin autenticación |
| S4 | Acciones críticas con reautenticación | Como dueño/admin, intentar eliminar una cuenta o aprobar operaciones sensibles | El sistema exige **contraseña (y MFA si está activado)** antes de ejecutar |

---

## 3. Pruebas por API (opcional, con `curl`)

```bash
# 1) Login — obtener token JWT
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"DavidSuperAdmin-2026","password":"TU_PASSWORD"}'
# → responde { "token": "...", "refreshToken": "...", "role": "SUPER_ADMIN", ... }

# 2) Endpoint protegido CON token (sustituir $TOKEN)
curl -s http://localhost:8080/api/admin/owners \
  -H "Authorization: Bearer $TOKEN"
# → 200 con la lista de dueños

# 3) El mismo endpoint SIN token
curl -i http://localhost:8080/api/admin/owners
# → 401/403
```

En `scripts/` también hay smoke tests automatizados por etapa (`etapa0-smoke.ps1` a `etapa2-smoke.ps1`, requieren PowerShell).

---

## 4. Problemas comunes

| Síntoma | Causa probable | Solución |
|---|---|---|
| El backend no arranca: error de conexión a BD | PostgreSQL apagado o contraseña incorrecta | Verificar `application-secrets.yml` y que exista la BD `propgest_db` |
| El backend aborta en el arranque pidiendo admin password | `app.admin.password` vacío | Definirlo en `application-secrets.yml` |
| Login devuelve 500 / datos cifrados ilegibles | `app.encryption.key` vacía o cambiada | Generar una con `openssl rand -base64 32` y no cambiarla después del primer arranque |
| Frontend en blanco o errores CORS | Backend no está en `localhost:8080` | Arrancar backend primero; el frontend apunta ahí por defecto |
| Puerto 3000 ocupado | Otra app usando el puerto | Liberarlo (el frontend usa `strictPort`) |
