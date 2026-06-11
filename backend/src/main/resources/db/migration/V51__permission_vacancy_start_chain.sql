-- V51: Add VACANCY_START_CHAIN authority to tpl-full-access.
--
-- Contexto
-- --------
-- La feature "Poner en renta" (V52) expone un botón en el expediente del
-- inmueble que dispara la cadena de agentes inmobiliarios.
--
--   * OWNER  → ejecuta directo (POST /api/owner/workflow/vacancies/start-agent-chain).
--   * PROPERTY_ADMIN con authority VACANCY_START_CHAIN → crea un ApprovalRequest
--     que el dueño debe autorizar desde su bandeja de pendientes.
--
-- El permiso es granular y vive únicamente en tpl-full-access (Acceso Total) por
-- diseño: solo staff con capacidad plena puede iniciar trámites comerciales.
-- Contador y Solo Lectura NO lo reciben (su alcance es operativo/contable, no
-- comercial).
--
-- Idempotente: usa jsonb_build_array + operadores de conjuntos para evitar
-- duplicar el authority si ya estuviera presente (p.ej. re-corrida manual).

DO $$
DECLARE
  existing_perms JSONB;
BEGIN
  SELECT permissions INTO existing_perms
  FROM permission_templates
  WHERE id = 'tpl-full-access';

  IF existing_perms IS NULL THEN
    RAISE WARNING '[V51] tpl-full-access no existe; se omite la actualización.';
    RETURN;
  END IF;

  -- Solo agrega si aún no está.
  IF NOT (existing_perms @> '["VACANCY_START_CHAIN"]'::jsonb) THEN
    UPDATE permission_templates
    SET permissions = existing_perms || '["VACANCY_START_CHAIN"]'::jsonb
    WHERE id = 'tpl-full-access';
    RAISE NOTICE '[V51] VACANCY_START_CHAIN agregado a tpl-full-access.';
  ELSE
    RAISE NOTICE '[V51] VACANCY_START_CHAIN ya estaba presente en tpl-full-access.';
  END IF;
END $$;
