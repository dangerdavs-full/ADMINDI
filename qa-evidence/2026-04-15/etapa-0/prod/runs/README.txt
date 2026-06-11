ADMINDI — Evidencia smoke prod-like (inmutable por corrida)
===========================================================

Cada ejecución de `scripts/etapa0-prod-smoke.ps1` crea un subdirectorio nuevo:

  runs/<run-id>/

donde `<run-id>` por defecto es `yyyy-MM-ddTHH-mm-ss` (hora local del equipo), o el valor de `SMOKE_PROD_RUN_ID`, o el directorio completo si se define `SMOKE_PROD_OUT`.

Dentro de cada carpeta de corrida (y solo ahí) se escriben:

  PROD_SMOKE_HTTP.log   — trazas HTTP del script (sin secretos en claro deliberados)
  PROD_SMOKE_RESULT.txt — una línea `PROD_SMOKE_RESULT=...`
  RUN_METADATA.txt      — run_id, UTC inicio/fin, rutas, exit_code, máquina/usuario

La raíz `.../prod/` conserva únicamente artefactos de arranque JVM / runbook (`PROD_STARTUP_CHECK.txt`, `boot-prod-*.txt`, etc.), no los tres archivos de smoke reutilizados entre corridas.
