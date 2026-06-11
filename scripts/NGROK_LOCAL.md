# Desarrollo local con ngrok (OAuth Mercado Pago)

Mercado Pago **no acepta** `localhost` en URLs de redirección OAuth. Usamos ngrok para exponer solo el backend con HTTPS, **sin subir contraseñas ni API keys** al repositorio.

## Qué se expone y qué no

| Se expone (público) | No se expone |
|---------------------|--------------|
| Puerto 8080 del backend vía HTTPS ngrok | PostgreSQL, Redis |
| Rutas `/api/integrations/mercadopago/...` | `application-secrets.yml` |
| | Tokens en git |

El frontend sigue en `http://localhost:3000`. Solo Mercado Pago llama a la URL ngrok del backend.

## Configuración única

1. Cuenta en [ngrok.com](https://ngrok.com) (gratis).
2. Copia tu **Authtoken** (solo tuyo): [dashboard.ngrok.com/get-started/your-authtoken](https://dashboard.ngrok.com/get-started/your-authtoken)
3. Crea `.env` en la raíz del proyecto (ya está en `.gitignore`):

```bash
NGROK_AUTHTOKEN=tu_authtoken_de_ngrok
```

4. En `backend/src/main/resources/application-secrets.yml` (gitignored), configura OAuth de MP:

```yaml
mercadopago:
  client-id: "7662041248754134"
  client-secret: <Client Secret del panel MP — NO es el Access Token>
```

## Uso diario

Terminal 1 — túnel:

```bash
chmod +x scripts/start-ngrok-tunnel.sh scripts/run-backend-with-ngrok.sh scripts/stop-ngrok-tunnel.sh
./scripts/start-ngrok-tunnel.sh
```

Copia la URL de redirección que imprime el script y pégala en **Mercado Pago Developers → tu app → URLs de redirección** (debe ser idéntica, sin `/` final).

Terminal 2 — backend:

```bash
./scripts/run-backend-with-ngrok.sh
```

Terminal 3 — frontend:

```bash
cd frontend && npm run dev
```

## Probar OAuth del dueño

1. Login como **OWNER** en `http://localhost:3000`
2. **Mi perfil** → **Continuar con Mercado Pago**
3. Inicias sesión en MP → autorizas → vuelves al dashboard

## Archivos generados (gitignored)

- `.env.ngrok` — solo URLs públicas (sin tokens de MP)
- `ngrok.log` — log del túnel
- `.ngrok.pid` — PID del proceso ngrok

## Detener

```bash
./scripts/stop-ngrok-tunnel.sh
```

Si reinicias ngrok en plan gratuito, **cambia la URL**: vuelve a ejecutar `start-ngrok-tunnel.sh` y actualiza la URL en el panel de Mercado Pago.

## Safari: "demasiados redireccionamientos" en checkout

Si al pagar ves un bucle en `sandbox.mercadopago.com.mx`:

1. **Reinicia el backend** con `./scripts/run-backend-with-ngrok.sh` (fija `APP_URL=http://localhost:3000` para las URLs de retorno del checkout).
2. Prueba en **Chrome ventana de incógnito** (Safari suele mezclar cookies de MP producción y sandbox).
3. Borra datos del sitio para `mercadopago.com` y `sandbox.mercadopago.com.mx` (Safari → Ajustes → Privacidad → Gestionar datos).
4. En sandbox, paga con un **usuario de prueba comprador** de MP (Credenciales de prueba), no con tu cuenta real de vendedor.
5. El dueño debe tener token **TEST-** si usas checkout sandbox (`oauth-use-test-token: true` en local).

Ngrok **no** debe usarse en `back_urls` del checkout; solo para OAuth y webhook.

## Producción

Usa dominio propio con HTTPS (ej. `https://api.tudominio.com/...`). No uses ngrok en producción.
