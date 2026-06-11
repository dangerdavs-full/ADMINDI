ADMINDI — Evidencia smoke Etapa 1 (inmutable por corrida)
===========================================================

Cada ejecución de `scripts/etapa1-smoke.ps1` crea un subdirectorio nuevo:

  runs/<run-id>/

donde `<run-id>` por defecto es `yyyy-MM-ddTHH-mm-ss` (hora local del equipo),
o el valor de `SMOKE_ETAPA1_RUN_ID` si se define explícitamente.

Dentro de cada carpeta de corrida (y solo ahí) se escriben:

  ETAPA1_SMOKE_HTTP.log   — trazas HTTP del script (sin secretos en claro)
  ETAPA1_SMOKE_RESULT.txt — una línea `ETAPA1_SMOKE_RESULT=...`
  RUN_METADATA.txt        — run_id, UTC inicio/fin, rutas, exit_code, máquina/usuario

Reglas:
  - Nunca se sobrescribe una carpeta de corrida existente.
  - Si el run-id colisiona, el script aborta.
  - La entrega (ENTREGA_ETAPA_1_*.txt) debe referenciar el run-id exacto.
  - Logs de arranque JVM o diagnóstico no van aquí; solo artefactos de smoke.

Contratos backend (JUnit / MockMvc) pueden registrarse en la misma estructura `runs/<run-id>/`
usando los mismos tres nombres de archivo, con salida de `mvn test` en `ETAPA1_SMOKE_HTTP.log`,
siempre en carpeta nueva por corrida (misma regla de no colisión).
