#!/usr/bin/env bash
# Arranca backend con perfil local+secrets y URLs OAuth de .env.ngrok (sin commitear secretos).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_NGROK="${ROOT}/.env.ngrok"

if [[ ! -f "${ENV_NGROK}" ]]; then
  echo "Primero ejecuta: ./scripts/start-ngrok-tunnel.sh"
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "${ENV_NGROK}"
if [[ -f "${ROOT}/.env" ]]; then
  # shellcheck disable=SC1091
  source "${ROOT}/.env"
fi
set +a

# Variables vacías en .env NO deben pisar application-secrets.yml.
for _mp_var in MP_CLIENT_SECRET MERCADOPAGO_CLIENT_SECRET MP_CLIENT_ID MP_ACCESS_TOKEN MP_PUBLIC_KEY; do
  if [[ -z "${!_mp_var:-}" ]]; then
    unset "${_mp_var}" 2>/dev/null || true
  fi
done

# Cargar Client Secret desde application-secrets.yml → Spring lo lee como mercadopago.client-secret
SECRETS_YML="${ROOT}/backend/src/main/resources/application-secrets.yml"
_mp_cs=""
if [[ -f "${SECRETS_YML}" ]]; then
  _mp_cs="$(awk '
    /^mercadopago:/ { in_mp=1; next }
    in_mp && /^[^[:space:]#]/ { in_mp=0 }
    in_mp && /^[[:space:]]+client-secret:/ {
      sub(/^[[:space:]]*client-secret:[[:space:]]*/, "")
      gsub(/\r$/, "")
      if (length($0) > 0) print
      exit
    }
  ' "${SECRETS_YML}")"
fi
if [[ -n "${_mp_cs}" ]]; then
  export MERCADOPAGO_CLIENT_SECRET="${_mp_cs}"
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local,secrets}"
# Checkout back_urls → localhost; ngrok solo webhook/OAuth (evita bucle Safari + interstitial ngrok).
export APP_URL="${APP_URL:-http://localhost:3000}"

if [[ -z "${MERCADOPAGO_CLIENT_SECRET:-}" ]]; then
  echo "[ADMINDI] ERROR: Client Secret vacío en disco."
  echo "  1) Abre: backend/src/main/resources/application-secrets.yml"
  echo "  2) Línea client-secret: pega el Client Secret (MP → Producción → Credenciales)"
  echo "  3) Guarda con Cmd+S (si no guardas, el backend sigue viéndolo vacío)"
  echo "  4) Ejecuta de nuevo: ./scripts/run-backend-with-ngrok.sh"
  echo "  Debe aparecer: [MercadoPago] OAuth al arranque: credentials=true"
else
  echo "[ADMINDI] Client Secret cargado (${#MERCADOPAGO_CLIENT_SECRET} caracteres)"
fi

echo "[ADMINDI] Perfiles: ${SPRING_PROFILES_ACTIVE}"
echo "[ADMINDI] MP OAuth redirect: ${MP_OAUTH_REDIRECT_URI}"
echo "[ADMINDI] Frontend sigue en http://localhost:3000 — solo MP usa la URL pública."

cd "${ROOT}/backend"
exec mvn spring-boot:run
