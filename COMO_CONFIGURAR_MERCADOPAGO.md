# Mercado Pago + ADMINDI — guía paso a paso (desde cero)

Esta guía es para **probar en tu Mac** que un **dueño** conecte su cuenta de Mercado Pago (como “entrar con Google”) y un **inquilino** pague la renta.

No necesitas saber programación avanzada. Solo copiar/pegar en terminal y en la web de Mercado Pago.

---

## ¿Qué es cada cosa? (en 30 segundos)

| Nombre | Qué es |
|--------|--------|
| **ADMINDI** | Tu programa (dueño + inquilino en el navegador) |
| **Mercado Pago Developers** | Página donde configuras la “app” que conecta con MP |
| **ngrok** | Programa gratis que crea un link público `https://...` hacia tu computadora (MP no acepta `localhost`) |
| **Client Secret** | Una clave de tu app en MP (NO es la contraseña del dueño) |
| **Access Token** | Otra clave de MP (la plataforma ya la tiene en secrets; el dueño NO la pega) |

---

## PARTE 1 — Instalar ngrok (solo una vez)

### 1.1 Crear cuenta ngrok

1. Abre https://ngrok.com  
2. Regístrate (gratis).  
3. Entra al panel: https://dashboard.ngrok.com/get-started/your-authtoken  
4. **Copia** el “Authtoken” (texto largo). Lo usarás en el paso 1.3.

### 1.2 Instalar ngrok en la Mac

Abre la app **Terminal** y pega:

```bash
brew install ngrok/ngrok/ngrok
```

Si dice que no tienes `brew`, instala Homebrew primero:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Luego repite `brew install ngrok/ngrok/ngrok`.

Comprueba:

```bash
ngrok version
```

Debe mostrar una versión (no “command not found”).

### 1.3 Guardar el token de ngrok en tu proyecto

En la carpeta del proyecto (`plataforma inmubles`), crea un archivo llamado **`.env`** (con el punto al inicio).

Puedes hacerlo en Terminal:

```bash
cd "/Users/dangerdavs/Desktop/plataforma inmuebles/plataforma inmubles"
nano .env
```

Pega **una sola línea** (reemplaza con tu token real):

```
NGROK_AUTHTOKEN=PEGA_AQUI_TU_AUTHTOKEN_DE_NGROK
```

Guarda: `Ctrl+O`, Enter, `Ctrl+X`.

> Ese archivo **no se sube a Git** (está ignorado). Solo vive en tu Mac.

---

## PARTE 2 — Mercado Pago: obtener el Client Secret

> **Importante:** En **Credenciales de prueba** (menú PRUEBAS) solo verás **Public Key** y **Access Token**.
> El **Client Secret** no está ahí — es normal. Mercado Pago lo muestra en **Credenciales de producción**.

Ya tienes el **Client ID** = **N.º de la aplicación** (`7662041248754134`). Falta el **Client Secret**.

1. Abre https://www.mercadopago.com.mx/developers/panel/app  
2. Entra con tu cuenta y abre la app **ADMINDI**.  
3. En el menú izquierdo, baja a la sección **PRODUCCIÓN** (no PRUEBAS).  
4. Entra en **Credenciales de producción**.  
5. Si te pide activarlas, completa:
   - **Rubro / Industry**
   - **Sitio web** (puede ser una URL de prueba tuya o la de ADMINDI)
   - Acepta términos y pulsa **Activar credenciales de producción**
6. Cuando estén activas, verás **dos bloques**:
   - Public Key + Access Token (producción — no los uses en local por ahora)
   - **Client ID** + **Client Secret** ← copia el **Client Secret** (ojo: no es el Access Token)
7. El **Client ID** suele ser el mismo número de aplicación: `7662041248754134`.

8. Abre en tu Mac el archivo (no lo subas a internet):

   `backend/src/main/resources/application-secrets.yml`

8. Busca la sección `mercadopago:` y deja el **client-secret** así (con tu valor real):

```yaml
mercadopago:
  access-token: APP_USR-TU_ACCESS_TOKEN_AQUI
  public-key: APP_USR-TU_PUBLIC_KEY_AQUI
  client-id: "TU_CLIENT_ID_AQUI"
  client-secret: TU_CLIENT_SECRET_AQUI
```

Guarda el archivo.

---

## PARTE 3 — Arrancar el túnel ngrok (cada vez que pruebes)

**Terminal 1** — deja esta ventana abierta:

```bash
cd "/Users/dangerdavs/Desktop/plataforma inmuebles/plataforma inmubles"
chmod +x scripts/*.sh
./scripts/start-ngrok-tunnel.sh
```

Al final verás algo como:

```
Registra en Mercado Pago Developers → URLs de redirección:
  https://abc123.ngrok-free.app/api/integrations/mercadopago/owner/oauth/callback
```

**Copia esa URL completa** (la tuya será distinta).

### 3.1 Pegar la URL en Mercado Pago

1. Vuelve a https://www.mercadopago.com.mx/developers/panel/app  
2. Tu app → **Detalles** / **Configuración** → **URLs de redirección** (o “Redirect URLs”).  
3. **Agrega** la URL que copiaste (exacta, sin espacio, sin `/` al final).  
4. Guarda.

> Si reinicias ngrok otro día, la URL **cambia** y debes repetir este paso.

---

## PARTE 4 — Arrancar backend y frontend

**Terminal 2** — backend:

```bash
cd "/Users/dangerdavs/Desktop/plataforma inmuebles/plataforma inmubles"
./scripts/run-backend-with-ngrok.sh
```

Espera hasta ver algo como `Started BackendApplication` (puede tardar 1–2 min la primera vez).

**Terminal 3** — frontend:

```bash
cd "/Users/dangerdavs/Desktop/plataforma inmuebles/plataforma inmubles/frontend"
npm run dev
```

Abre el navegador en: **http://localhost:3000**

---

## PARTE 5 — Probar: dueño conecta Mercado Pago

1. Inicia sesión en ADMINDI como **dueño** (OWNER), no como inquilino.  
2. Menú lateral → **Mi perfil**.  
3. Sección azul **“Cobrar renta con Mercado Pago”**.  
4. Clic en **“Continuar con Mercado Pago”**.  
5. Te lleva a la web de Mercado Pago → inicia sesión (tu cuenta de prueba o real).  
6. Acepta que ADMINDI pueda cobrar.  
7. Vuelves a ADMINDI con mensaje **“Cuenta conectada”**.

Si usas **cuenta de prueba** de MP para pagar después:

- Usuario prueba: `TESTUSER965331574739254992`  
- Contraseña: la que te dio MP en Credenciales de prueba  

---

## PARTE 6 — Probar: inquilino paga renta

1. Cierra sesión o usa otro navegador / ventana incógnito.  
2. Entra como **inquilino** (TENANT).  
3. Abre una factura de renta pendiente → **Pagar**.  
4. Elige **Mercado Pago** (solo aparece si el dueño ya conectó en el paso 5).  
5. Paga en el checkout de MP.  
6. Al volver, la factura debe quedar pagada.

---

## Si algo falla

| Problema | Qué hacer |
|----------|-----------|
| `ngrok: command not found` | Repite PARTE 1.2 |
| `Falta NGROK_AUTHTOKEN` | Crea `.env` con el token (PARTE 1.3) |
| Botón MP deshabilitado para inquilino | El dueño no conectó MP — repite PARTE 5 |
| “Configuración pendiente” en Mi perfil | Falta `client-secret` en secrets (PARTE 2) |
| OAuth falla después de MP | La URL en el panel MP no coincide con la del script — copia de nuevo |
| Log `invalid client_id or client_secret` | El **Client Secret** en `application-secrets.yml` no es el de **Producción → Credenciales** (o está viejo). Ejecuta `./scripts/verificar-mp-oauth-credenciales.sh` hasta ver **OK** |
| Usuarios raros al iniciar backend | Semilla QA ya está apagada; son datos viejos en la base |
| Safari: “demasiados redireccionamientos” en checkout MP | Reinicia backend con `./scripts/run-backend-with-ngrok.sh`; prueba **Chrome incógnito**; borra cookies de `mercadopago.com`; paga con **usuario de prueba comprador**; ver `scripts/NGROK_LOCAL.md` |

---

## Detener todo

```bash
# En Terminal 1 (o nueva):
cd "/Users/dangerdavs/Desktop/plataforma inmuebles/plataforma inmubles"
./scripts/stop-ngrok-tunnel.sh
```

En Terminal 2 y 3: `Ctrl+C` para parar backend y frontend.

---

## Resumen en 6 líneas

1. Instala ngrok + pon `NGROK_AUTHTOKEN` en `.env`  
2. Pon `client-secret` en `application-secrets.yml`  
3. `./scripts/start-ngrok-tunnel.sh` → copia URL a Mercado Pago  
4. `./scripts/run-backend-with-ngrok.sh`  
5. `npm run dev` en frontend  
6. Dueño → Mi perfil → Continuar con Mercado Pago → Inquilino paga  

¿Dudas? Guarda capturas del error exacto y del panel de MP.
