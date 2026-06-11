package com.admindi.backend.model;

/**
 * Estados de un inmueble a lo largo de su ciclo de vida.
 *
 * <p>Histórico (pre-Fase 2): sólo AVAILABLE / OCCUPIED / MAINTENANCE / DELETED. La
 * reconciliación en {@code LeaseService} y {@code AccountingReconciliationService} se
 * movía únicamente entre AVAILABLE y OCCUPIED.
 *
 * <p>Fase 2 agrega tres estados intermedios para representar el ciclo de captación
 * comercial coordinado por el REAL_ESTATE_AGENT:
 * <ul>
 *   <li>{@link #PENDING_RENT} — el agente aceptó la vacancia y ya subió fotos; el
 *       inmueble está en vitrina buscando arrendatario.</li>
 *   <li>{@link #PROSPECT_PROPOSED} — el agente reportó un prospecto y espera la
 *       decisión del dueño.</li>
 *   <li>{@link #AWAITING_CONTRACT} — el dueño aceptó al prospecto; falta que se firme
 *       el contrato y el agente suba la evidencia.</li>
 * </ul>
 *
 * <p>Reglas de transición:
 * <pre>
 *   AVAILABLE ─(agente sube fotos)→ PENDING_RENT
 *   PENDING_RENT ─(agente propone prospecto)→ PROSPECT_PROPOSED
 *   PROSPECT_PROPOSED ─(dueño rechaza)→ PENDING_RENT
 *   PROSPECT_PROPOSED ─(dueño acepta)→ AWAITING_CONTRACT
 *   AWAITING_CONTRACT ─(agente sube contrato firmado)→ OCCUPIED
 *   OCCUPIED ─(lease termina / tenant eliminado)→ AVAILABLE (reconciliación)
 * </pre>
 *
 * <p>Los cuatro estados comerciales (AVAILABLE / PENDING_RENT / PROSPECT_PROPOSED /
 * AWAITING_CONTRACT) se reconcilian todos a AVAILABLE cuando no hay cadena de agente
 * activa, para evitar inmuebles atorados si se cancela la operación a mitad de camino.
 * MAINTENANCE y DELETED siguen siendo terminales / override sobre el ciclo comercial.
 */
public enum PropertyStatus {
    AVAILABLE,
    PENDING_RENT,
    PROSPECT_PROPOSED,
    AWAITING_CONTRACT,
    OCCUPIED,
    MAINTENANCE,
    DELETED
}
