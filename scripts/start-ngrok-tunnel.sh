#!/usr/bin/env bash
# Expone el backend (8080) con HTTPS público vía ngrok para OAuth Mercado Pago.
# NO escribe secretos de la app — solo URLs públicas en .env.ngrok (gitignored).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_NGROK="${ROOT}/.env.ngrok"
NGROK_LOG="${ROOT}/ngrok.log"
PID_FILE="${ROOT}/.ngrok.pid"

if ! command -v ngrok >/dev/null 2>&1; then
  echo "Instala ngrok: https://ngrok.com/download"
  echo "  macOS: brew install ngrok/ngrok/ngrok"
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "Se requiere curl."
  exit 1
fi

# Token ngrok solo desde .env local (gitignored) — nunca del repo
if [[ -f "${ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT}/.env"
  set +a
fi

if [[ -z "${NGROK_AUTHTOKEN:-}" ]]; then
  echo "Falta NGROK_AUTHTOKEN."
  echo "1) Crea cuenta en https://ngrok.com"
  echo "2) Copia tu authtoken: https://dashboard.ngrok.com/get-started/your-authtoken"
  echo "3) En ${ROOT}/.env agrega: NGROK_AUTHTOKEN=tu_token"
  exit 1
fi

ngrok config add-authtoken "${NGROK_AUTHTOKEN}" >/dev/null 2>&1 || true

# Detener túnel previo del mismo proyecto
if [[ -f "${PID_FILE}" ]]; then
  OLD_PID="$(cat "${PID_FILE}")"
  if kill -0 "${OLD_PID}" 2>/dev/null; then
    kill "${OLD_PID}" 2>/dev/null || true
    sleep 1
  fi
  rm -f "${PID_FILE}"
fi

echo "[ngrok] Iniciando túnel HTTPS → localhost:8080 …"
ngrok http 8080 --log="${NGROK_LOG}" --log-format=logfmt >/dev/null 2>&1 &
NGROK_PID=$!
echo "${NGROK_PID}" > "${PID_FILE}"

PUBLIC_URL=""
for _ in $(seq 1 45); do
  TUNNELS_JSON="$(curl -sS http://127.0.0.1:4040/api/tunnels 2>/dev/null || true)"
  if command -v jq >/dev/null 2>&1; then
    PUBLIC_URL="$(echo "${TUNNELS_JSON}" | jq -r '.tunnels[] | select(.public_url | startswith("https://")) | .public_url' | head -n1)"
  else
    PUBLIC_URL="$(echo "${TUNNELS_JSON}" | grep -o 'https://[a-zA-Z0-9.-]*\.ngrok[^"]*' | head -n1)"
  fi
  if [[ -n "${PUBLIC_URL}" && "${PUBLIC_URL}" != "null" ]]; then
    break
  fi
  sleep 1
done

if [[ -z "${PUBLIC_URL}" ]]; then
  echo "[ngrok] No se obtuvo URL pública. Revisa ${NGROK_LOG}"
  kill "${NGROK_PID}" 2>/dev/null || true
  rm -f "${PID_FILE}"
  exit 1
fi

# Sin barra final — MP exige coincidencia exacta
PUBLIC_URL="${PUBLIC_URL%/}"

OAUTH_URI="${PUBLIC_URL}/api/integrations/mercadopago/owner/oauth/callback"
WEBHOOK_URI="${PUBLIC_URL}/api/integrations/mercadopago/webhook"
TWILIO_WEBHOOK_URI="${PUBLIC_URL}/api/webhooks/twilio/whatsapp"

cat > "${ENV_NGROK}" <<EOF
# AUTO-GENERADO por scripts/start-ngrok-tunnel.sh — NO COMMITEAR
# Regenera si reinicias ngrok (la URL cambia en plan gratuito).
MP_PUBLIC_BASE_URL=${PUBLIC_URL}
MP_OAUTH_REDIRECT_URI=${OAUTH_URI}
MP_NOTIFICATION_URL=${WEBHOOK_URI}
# Twilio firma el POST inbound sobre esta URL EXACTA (validación X-Twilio-Signature).
# Debe coincidir con la URL configurada en Twilio Console > WhatsApp Sender > Webhook.
TWILIO_WEBHOOK_PUBLIC_URL=${TWILIO_WEBHOOK_URI}
EOF
chmod 600 "${ENV_NGROK}" 2>/dev/null || true

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  Túnel ngrok activo (PID ${NGROK_PID})"
echo "  Base HTTPS: ${PUBLIC_URL}"
echo ""
echo "  Registra en Mercado Pago Developers → URLs de redirección:"
echo "    ${OAUTH_URI}"
echo ""
echo "  Webhook MP (opcional):"
echo "    ${WEBHOOK_URI}"
echo ""
echo "  Webhook WhatsApp — pégala en Twilio Console:"
echo "    Messaging → Senders → WhatsApp senders → tu número →"
echo "    'Webhook URL for incoming messages' (método POST):"
echo "    ${TWILIO_WEBHOOK_URI}"
echo ""
echo "  Variables guardadas en: .env.ngrok (gitignored)"
echo "  Siguiente paso:"
echo "    ./scripts/run-backend-with-ngrok.sh"
echo "════════════════════════════════════════════════════════════"
