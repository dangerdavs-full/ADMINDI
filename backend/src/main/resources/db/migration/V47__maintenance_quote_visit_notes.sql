-- =====================================================================
-- V47 — Notas de visita del proveedor en la cotización de mantenimiento
-- ---------------------------------------------------------------------
-- Decisión de producto: el proveedor NO necesita un flujo separado de
-- "fotos de diagnóstico" antes de cotizar. Si durante la visita descubre
-- algo (conceptos adicionales, hallazgos peores que el reporte original,
-- aclaraciones técnicas), lo comunica como texto libre dentro del mismo
-- submit de la cotización — evita duplicar plantillas Meta y hace el
-- flujo más simple para el dueño.
--
-- `description` (ya existe) queda para los conceptos técnicos del trabajo;
-- `visit_notes` (nuevo, NULL-able) es el espacio libre para observaciones
-- del proveedor tras la visita. La UI del dueño los muestra como dos
-- secciones separadas para que el proveedor no tenga que mezclarlos.
-- =====================================================================

ALTER TABLE maintenance_quotes
    ADD COLUMN IF NOT EXISTS visit_notes TEXT;
