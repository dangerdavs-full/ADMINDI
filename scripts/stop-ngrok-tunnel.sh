#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="${ROOT}/.ngrok.pid"

if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}")"
  if kill -0 "${PID}" 2>/dev/null; then
    kill "${PID}" 2>/dev/null || true
    echo "[ngrok] Túnel detenido (PID ${PID})."
  fi
  rm -f "${PID_FILE}"
else
  echo "[ngrok] No hay túnel registrado."
fi
