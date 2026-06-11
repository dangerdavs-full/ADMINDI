#!/usr/bin/env bash
# Comprueba que tienes todo listo antes de probar Mercado Pago.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OK=0
FAIL=0

check() {
  if "$@"; then
    echo "  ✓ $1"
    OK=$((OK + 1))
  else
    echo "  ✗ $1"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== Verificación ADMINDI + Mercado Pago ==="
echo ""

echo "Herramientas:"
check command -v ngrok
check command -v mvn
check command -v node
check command -v curl

echo ""
echo "Archivos:"
test -f "${ROOT}/.env" && grep -q 'NGROK_AUTHTOKEN=' "${ROOT}/.env" 2>/dev/null && grep -vq 'PEGA_AQUI' "${ROOT}/.env" 2>/dev/null
check test -f "${ROOT}/.env"

SECRETS="${ROOT}/backend/src/main/resources/application-secrets.yml"
if test -f "${SECRETS}"; then
  grep -q 'client-secret:' "${SECRETS}" && ! grep -E 'client-secret:\s*$' "${SECRETS}" >/dev/null 2>&1
  check test -n "$(grep 'client-secret:' "${SECRETS}" | grep -v '^[[:space:]]*#' | sed 's/.*client-secret:[[:space:]]*//' | tr -d ' \"')"
else
  echo "  ✗ application-secrets.yml existe"
  FAIL=$((FAIL + 1))
fi

test -f "${ROOT}/.env.ngrok"
check test -f "${ROOT}/.env.ngrok" 2>/dev/null || true
if test -f "${ROOT}/.env.ngrok"; then
  echo "     (túnel ngrok ya generado — bien)"
else
  echo "     (falta — ejecuta ./scripts/start-ngrok-tunnel.sh)"
fi

echo ""
echo "Resultado: ${OK} OK, ${FAIL} pendiente(s)"
if [[ ${FAIL} -gt 0 ]]; then
  echo "Lee: COMO_CONFIGURAR_MERCADOPAGO.md"
  exit 1
fi
echo "Listo para arrancar backend y frontend."
exit 0
