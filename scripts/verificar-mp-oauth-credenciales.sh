#!/usr/bin/env bash
# Comprueba que client-id y client-secret en application-secrets.yml son aceptados por MP.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS_YML="${ROOT}/backend/src/main/resources/application-secrets.yml"

if [[ ! -f "${SECRETS_YML}" ]]; then
  echo "No existe: ${SECRETS_YML}"
  exit 1
fi

CLIENT_ID="$(awk '/^[[:space:]]+client-id:/ {gsub(/.*client-id:[[:space:]]*/,""); gsub(/"/,""); print; exit}' "${SECRETS_YML}")"
CLIENT_SECRET="$(awk '/^[[:space:]]+client-secret:/ {sub(/^[[:space:]]*client-secret:[[:space:]]*/,""); print; exit}' "${SECRETS_YML}")"

if [[ -z "${CLIENT_ID}" || -z "${CLIENT_SECRET}" ]]; then
  echo "ERROR: client-id o client-secret vacíos en application-secrets.yml"
  exit 1
fi

echo "Client ID: ${CLIENT_ID}"
echo "Client Secret: ${#CLIENT_SECRET} caracteres"

RESP="$(curl -sS -X POST "https://api.mercadopago.com/oauth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=${CLIENT_ID}&client_secret=${CLIENT_SECRET}")"

if echo "${RESP}" | grep -q '"access_token"'; then
  echo "OK: Mercado Pago aceptó el par Client ID + Client Secret."
  exit 0
fi

echo "FALLO: Mercado Pago rechazó las credenciales OAuth:"
echo "${RESP}"
echo ""
echo "Qué hacer:"
echo "  1) https://www.mercadopago.com.mx/developers/panel/app → app ADMINDI"
echo "  2) Menú PRODUCCIÓN → Credenciales de producción (deben estar ACTIVADAS)"
echo "  3) Copia Client ID y Client Secret del MISMO bloque (no el Access Token de PRUEBAS)"
echo "  4) Pega en backend/src/main/resources/application-secrets.yml y guarda (Cmd+S)"
echo "  5) Vuelve a ejecutar este script hasta ver OK"
exit 1
