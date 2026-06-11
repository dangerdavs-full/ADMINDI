package com.admindi.backend.controller;

import com.admindi.backend.dto.ManualReminderRequestDTO;
import com.admindi.backend.dto.ManualReminderResultDTO;
import com.admindi.backend.dto.NotificationHistoryPageDTO;
import com.admindi.backend.service.NotificationHistoryService;
import com.admindi.backend.service.NotificationRetryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints REST para historial y reintento de notificaciones (Bloque C).
 *
 * <h2>Mapeo funcional</h2>
 * <ul>
 *   <li>{@code GET /api/notifications/history/tenant/{tenantProfileId}} → C3 (expediente del inquilino).</li>
 *   <li>{@code GET /api/notifications/history/owner} → C4 (panel global del due\u00f1o/admin).</li>
 *   <li>{@code GET /api/notifications/history/me}    → C6 (portal del inquilino).</li>
 *   <li>{@code POST /api/notifications/retry/{auditEventId}} → C5 (reintento con reauth).</li>
 * </ul>
 *
 * <h2>Seguridad</h2>
 * <ul>
 *   <li>OWNER y PROPERTY_ADMIN con {@code TENANT_REMIND_MANUAL} pueden ver y reintentar.</li>
 *   <li>SUPER_ADMIN <b>está explícitamente excluido</b> — consistente con Bloque B. El
 *       SUPER_ADMIN tiene su propio panel (C8) con otras responsabilidades.</li>
 *   <li>TENANT (ROLE_TENANT) solo accede a su propio endpoint {@code /me} — no tiene
 *       visibilidad de otros portafolios ni outcomes fallidos.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationHistoryController {

    private final NotificationHistoryService historyService;
    private final NotificationRetryService retryService;

    public NotificationHistoryController(NotificationHistoryService historyService,
                                         NotificationRetryService retryService) {
        this.historyService = historyService;
        this.retryService = retryService;
    }

    /**
     * Historial de notificaciones dirigidas a un inquilino específico.
     * Usado en el expediente (TenantManager, perfil abierto).
     */
    @GetMapping("/history/tenant/{tenantProfileId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_REMIND_MANUAL')")
    public NotificationHistoryPageDTO listForTenant(@PathVariable String tenantProfileId,
                                                    @RequestParam String month) {
        return historyService.listForTenant(tenantProfileId, month);
    }

    /**
     * Historial global del portafolio activo. Filtros opcionales.
     * channel: EMAIL | WHATSAPP
     * outcome: SENT | FAILED | SKIPPED
     * tenantUserId: filtrar por un inquilino específico (user id, no profile id).
     */
    @GetMapping("/history/owner")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_REMIND_MANUAL')")
    public NotificationHistoryPageDTO listForOwner(@RequestParam String month,
                                                   @RequestParam(required = false) String channel,
                                                   @RequestParam(required = false) String outcome,
                                                   @RequestParam(required = false) String tenantUserId) {
        return historyService.listForOwner(month, channel, outcome, tenantUserId);
    }

    /**
     * Historial propio del usuario autenticado. Solo outcomes exitosos.
     *
     * <p>Disponible para cualquier usuario autenticado (tenant, agente,
     * proveedor, staff, dueño) porque el service ya filtra por el {@code
     * resourceId} del usuario autenticado ({@code recipientIs(myUserId)}) —
     * no existe forma de ver notificaciones de otro user aunque se pase un
     * mes distinto o se manipule el query. La restricción previa a TENANT
     * rechazaba con 403 a agentes y proveedores que también necesitan ver
     * su propio historial desde sus dashboards.</p>
     */
    @GetMapping("/history/me")
    @PreAuthorize("isAuthenticated()")
    public NotificationHistoryPageDTO listForMe(@RequestParam String month) {
        return historyService.listForMe(month);
    }

    /**
     * Reintenta un envío fallido. Reusa ManualPaymentReminderService (por eso reusa
     * DTOs {@code ManualReminderRequestDTO} y {@code ManualReminderResultDTO}) —
     * el contrato con el frontend es idéntico al del botón manual del perfil.
     *
     * <p>Solo soporta eventos MANUAL_PAYMENT_REMINDER con outcome FAILED — el service
     * devuelve 409 claro para otros tipos.
     */
    @PostMapping("/retry/{auditEventId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_REMIND_MANUAL')")
    public ManualReminderResultDTO retry(@PathVariable String auditEventId,
                                         @RequestBody ManualReminderRequestDTO request) {
        return retryService.retry(auditEventId,
                request.getPassword(),
                request.getMfaCode());
    }
}
