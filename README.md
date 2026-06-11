# ADMINDI — Plataforma de Administración Inteligente de Inmuebles

Plataforma web multi-tenant para la administración de propiedades en renta: gestión de dueños, inquilinos, contratos, pagos (SPEI / efectivo / Mercado Pago), mantenimiento, agentes inmobiliarios y notificaciones automáticas por correo y WhatsApp.

> **Primera entrega — proyecto académico.** Este repositorio NO contiene tokens, contraseñas ni credenciales reales: todos los secretos se inyectan por variables de entorno o archivos locales ignorados por git (ver [Configuración de credenciales](#3-configuración-de-credenciales)).

---

## Stack tecnológico

| Capa | Tecnología |
|---|---|
| Backend | Java 21 · Spring Boot 3.2 (Web, Security, JPA, Validation) · JWT |
| Base de datos | PostgreSQL 15+ · Flyway (migraciones automáticas) |
| Caché / rate-limit | Redis |
| Frontend | React 18 · TypeScript · Vite · Tailwind CSS |
| Integraciones (opcionales) | Twilio WhatsApp Business · Mercado Pago (Checkout Pro + OAuth) · Anthropic Claude (OCR e IA contable) · Banxico CEP |

## Estructura del repositorio

```
├── backend/            # API REST Spring Boot (puerto 8080)
│   └── src/main/resources/
│       ├── application.yml                    # Config base (sin secretos)
│       ├── application-secrets.yml.example    # Plantilla de secretos → copiar
│       └── db/                                # Migraciones Flyway
├── frontend/           # SPA React + Vite (puerto 3000)
├── scripts/            # Smoke tests y utilidades (ngrok, QA)
├── docs/               # Documentación de arquitectura
├── .env.example        # Plantilla de variables de entorno → copiar
└── *.md                # Documentos de planeación y diseño del proyecto
```

---

## Cómo ejecutar el proyecto

### 1. Requisitos previos

- Java 21 (JDK) y Maven 3.9+
- Node.js 18+ y npm
- PostgreSQL 15 o superior, corriendo en `localhost:5432`
- Redis corriendo en `localhost:6379` (para rate-limiting)

### 2. Crear la base de datos

```bash
createdb -U postgres propgest_db
```

Las tablas se crean solas: Flyway aplica las migraciones al arrancar el backend.

### 3. Configuración de credenciales

Los secretos NO viven en el repositorio. Hay dos plantillas que copiar y rellenar:

```bash
# 1) Variables de entorno generales (opcional en local)
cp .env.example .env

# 2) Secretos del backend (OBLIGATORIO)
cd backend/src/main/resources
cp application-secrets.yml.example application-secrets.yml
```

Editar `application-secrets.yml` y rellenar los **3 valores obligatorios**:

| Clave | Descripción |
|---|---|
| `spring.datasource.password` | Contraseña de tu usuario `postgres` local |
| `app.admin.password` | Contraseña inicial del SUPER_ADMIN (la eliges tú) |
| `app.encryption.key` | Llave de cifrado — generar con `openssl rand -base64 32` |

Las integraciones externas (Twilio, Mercado Pago, Anthropic) quedan **deshabilitadas por defecto** y la aplicación funciona sin ellas: los envíos de WhatsApp/correo se auditan como `SKIPPED` y los flujos de pago externos caen a modo manual.

### 4. Arrancar el backend

```bash
cd backend
SPRING_PROFILES_ACTIVE=local,secrets mvn spring-boot:run
```

La API queda disponible en `http://localhost:8080/api`.

### 5. Arrancar el frontend

```bash
cd frontend
npm install
npm run dev
```

La aplicación queda en `http://localhost:3000` (apunta por defecto al backend en `localhost:8080`).

### 6. Primer acceso

| Campo | Valor |
|---|---|
| Usuario | `DavidSuperAdmin-2026` (configurable con `ADMIN_USERNAME`) |
| Contraseña | La definida en `app.admin.password` |

Desde el panel del SUPER_ADMIN se crean dueños (OWNER); cada dueño gestiona sus propiedades, inquilinos, contratos y equipo.

---

## Funcionalidad incluida en esta entrega

- **Autenticación y seguridad**: login con JWT + refresh token, MFA (TOTP) para acciones críticas, rate-limiting por endpoint, cifrado de columnas sensibles (teléfonos, CLABE, correos de contacto).
- **Gestión multi-tenant**: SUPER_ADMIN → dueños → propiedades/unidades → inquilinos, con aislamiento de datos por dueño.
- **Contratos y pagos**: ciclo de facturación mensual, registro de pagos SPEI/efectivo con comprobante, conciliación manual y automática, convenios de pago.
- **Mantenimiento**: tickets, cotizaciones, autorización del dueño y proveedores.
- **Agentes inmobiliarios**: vacantes, cadena de asignación de agentes, prospectos y comisiones.
- **Notificaciones**: correo SMTP y WhatsApp (34 plantillas aprobadas por Meta vía Twilio); recordatorios de pago programados, digest diario y reporte mensual.
- **Chatbot WhatsApp e IA** (opcional): consulta de saldo, subida de comprobantes con OCR (Claude Vision) y validación CEP de Banxico.

## Verificación rápida (smoke tests)

En `scripts/` hay smoke tests por etapa (`etapa0-smoke.ps1` … `etapa2-smoke.ps1`) y en `qa-evidence/` se incluye la evidencia de las corridas de QA.

## Nota de seguridad sobre credenciales

Este repositorio se preparó para entrega pública:

- `.env`, `application-secrets.yml`, respaldos de BD, llaves (`.jwt-key.bin`) y archivos de contraseñas están excluidos vía `.gitignore`.
- Solo se versionan las plantillas `.env.example`, `.env.template` y `application-secrets.yml.example` con placeholders.
- Los Content SIDs de plantillas WhatsApp (`HX…`) que aparecen en la configuración son identificadores públicos, no secretos.
