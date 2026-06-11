package com.admindi.backend.whatsapp;

import com.admindi.backend.dto.TransferProofDTO;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.WhatsappConversationStateEntity;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flujo del dueño: listar comprobantes pendientes y aprobar/rechazar sin portal.
 */
@Service
public class WhatsAppOwnerFlowHandlers {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppOwnerFlowHandlers.class);
    private static final int MAX_PENDING_SHOWN = 8;

    private final WhatsAppSessionService sessions;
    private final TwilioWhatsAppService twilio;
    private final BotSecurityBridge securityBridge;
    private final BotOwnerLedgerInvoker ledgerInvoker;
    private final WhatsAppOwnerReportFlow reportFlow;

    public WhatsAppOwnerFlowHandlers(WhatsAppSessionService sessions,
                                      TwilioWhatsAppService twilio,
                                      BotSecurityBridge securityBridge,
                                      BotOwnerLedgerInvoker ledgerInvoker,
                                      WhatsAppOwnerReportFlow reportFlow) {
        this.sessions = sessions;
        this.twilio = twilio;
        this.securityBridge = securityBridge;
        this.ledgerInvoker = ledgerInvoker;
        this.reportFlow = reportFlow;
    }

    public void showOwnerMenu(String fromE164, UserEntity owner,
                               WhatsappConversationStateEntity session) {
        sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
        twilio.sendFreeformWhatsApp(fromE164,
                "Hola " + firstName(owner) + ". Panel de pagos ADMINDI:\n\n"
                        + "1) Validar comprobantes pendientes\n"
                        + "2) Informe de pagos del mes\n"
                        + "3) Ayuda\n\n"
                        + "Responde con el número. Escribe MENU para volver aquí.");
    }

    public void handleOwnerMenu(String fromE164, UserEntity owner,
                                 WhatsappConversationStateEntity session, String body) {
        String opt = digitsOnly(body);
        if ("3".equals(opt)) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Los comprobantes en efectivo siempre requieren tu confirmación. "
                            + "Las transferencias SPEI se validan solas con Banxico cuando es posible; "
                            + "si ves uno pendiente, revisa monto, inquilino y periodo antes de aprobar.\n\n"
                            + "El informe de pagos te muestra por mes quién pagó, cuánto y qué falta, "
                            + "general o por arrendatario.\n\n"
                            + "Escribe MENU para volver.");
            return;
        }
        if ("2".equals(opt)) {
            reportFlow.startReportFlow(fromE164, owner, session);
            return;
        }
        if (!"1".equals(opt)) {
            showOwnerMenu(fromE164, owner, session);
            return;
        }
        startPendingProofList(fromE164, owner, session);
    }

    public void startPendingProofList(String fromE164, UserEntity owner,
                                       WhatsappConversationStateEntity session) {
        List<TransferProofDTO> pending = securityBridge.runAsOwner(owner, ledgerInvoker::listPendingProofs);
        if (pending.isEmpty()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No tienes comprobantes pendientes de validar. Escribe MENU.");
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
            return;
        }

        int limit = Math.min(pending.size(), MAX_PENDING_SHOWN);
        List<String> proofIds = new ArrayList<>(limit);
        StringBuilder sb = new StringBuilder();
        sb.append("Comprobantes pendientes (").append(pending.size()).append("):\n\n");
        for (int i = 0; i < limit; i++) {
            TransferProofDTO p = pending.get(i);
            proofIds.add(p.getId());
            sb.append(i + 1).append(") ")
              .append(label(p))
              .append("\n");
        }
        if (pending.size() > limit) {
            sb.append("\n(Mostrando los primeros ").append(limit)
              .append(". Valida estos y vuelve a pedir la lista.)\n");
        }
        sb.append("\nResponde con el número del comprobante o MENU para cancelar.");

        sessions.transition(session, WhatsAppBotState.OWNER_PROOF_PICK, Map.of(
                "pendingProofIds", proofIds));
        twilio.sendFreeformWhatsApp(fromE164, sb.toString());
    }

    public void handleProofPick(String fromE164, UserEntity owner,
                                 WhatsappConversationStateEntity session, String body) {
        Map<String, Object> ctx = sessions.getContext(session);
        Object raw = ctx.get("pendingProofIds");
        List<?> ids = raw instanceof List ? (List<?>) raw : List.of();
        int idx = parseOption(body) - 1;
        if (idx < 0 || idx >= ids.size()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Opción no válida. Responde con el número del comprobante o MENU.");
            return;
        }
        String proofId = String.valueOf(ids.get(idx));
        List<TransferProofDTO> pending = securityBridge.runAsOwner(owner, ledgerInvoker::listPendingProofs);
        TransferProofDTO selected = pending.stream()
                .filter(p -> proofId.equals(p.getId()))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Ese comprobante ya no está pendiente (quizá lo validaste en el portal). Escribe MENU.");
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
            return;
        }

        sessions.transition(session, WhatsAppBotState.OWNER_PROOF_DECIDE, Map.of(
                "selectedProofId", proofId,
                "selectedLabel", label(selected)));

        StringBuilder detail = new StringBuilder();
        detail.append("Detalle del comprobante:\n\n");
        detail.append(label(selected)).append("\n");
        if (selected.getClaveRastreo() != null && !selected.getClaveRastreo().isBlank()) {
            detail.append("Clave: ").append(selected.getClaveRastreo()).append("\n");
        }
        if (selected.getHoursRemaining() != null) {
            detail.append("Tiempo restante: ~").append(selected.getHoursRemaining()).append(" h\n");
        }
        detail.append("\n¿Qué deseas hacer?\n");
        detail.append("1) Aprobar pago\n");
        detail.append("2) Rechazar\n");
        detail.append("3) Volver al menú");
        twilio.sendFreeformWhatsApp(fromE164, detail.toString());
    }

    public void handleProofDecide(String fromE164, UserEntity owner,
                                   WhatsappConversationStateEntity session, String body) {
        String opt = digitsOnly(body);
        Map<String, Object> ctx = sessions.getContext(session);
        String proofId = (String) ctx.get("selectedProofId");
        if (proofId == null || proofId.isBlank()) {
            showOwnerMenu(fromE164, owner, session);
            return;
        }

        if ("3".equals(opt)) {
            showOwnerMenu(fromE164, owner, session);
            return;
        }
        if ("2".equals(opt)) {
            sessions.transition(session, WhatsAppBotState.OWNER_PROOF_REJECT_REASON, Map.of(
                    "selectedProofId", proofId));
            twilio.sendFreeformWhatsApp(fromE164,
                    "Escribe un motivo breve de rechazo (o envía 0 para usar motivo genérico).");
            return;
        }
        if (!"1".equals(opt)) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Responde 1 para aprobar, 2 para rechazar o 3 para volver.");
            return;
        }

        try {
            securityBridge.runAsOwnerVoid(owner, () -> ledgerInvoker.approveProof(proofId, fromE164));
            twilio.sendFreeformWhatsApp(fromE164,
                    "Pago aprobado y aplicado a la factura. El inquilino será notificado.\n\nEscribe MENU.");
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
        } catch (Exception ex) {
            logger.warn("[BOT-OWNER] approve failed proof={}: {}", proofId, ex.getMessage());
            twilio.sendFreeformWhatsApp(fromE164,
                    "No pude aprobar: " + userSafe(ex.getMessage()) + "\n\nEscribe MENU o intenta de nuevo.");
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
        }
    }

    public void handleRejectReason(String fromE164, UserEntity owner,
                                    WhatsappConversationStateEntity session, String body) {
        Map<String, Object> ctx = sessions.getContext(session);
        String proofId = (String) ctx.get("selectedProofId");
        if (proofId == null || proofId.isBlank()) {
            showOwnerMenu(fromE164, owner, session);
            return;
        }
        String reason = body == null ? "" : body.trim();
        if ("0".equals(reason)) {
            reason = "";
        }
        try {
            String finalReason = reason;
            securityBridge.runAsOwnerVoid(owner,
                    () -> ledgerInvoker.rejectProof(proofId, finalReason, fromE164));
            twilio.sendFreeformWhatsApp(fromE164,
                    "Comprobante rechazado. El inquilino será notificado.\n\nEscribe MENU.");
        } catch (Exception ex) {
            logger.warn("[BOT-OWNER] reject failed proof={}: {}", proofId, ex.getMessage());
            twilio.sendFreeformWhatsApp(fromE164,
                    "No pude rechazar: " + userSafe(ex.getMessage()) + "\n\nEscribe MENU.");
        }
        sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
    }

    private String label(TransferProofDTO p) {
        String type = p.getPaymentType() != null ? p.getPaymentType() : "SPEI";
        String tenant = p.getTenantName() != null ? p.getTenantName() : "inquilino";
        String month = p.getMonthYear() != null ? p.getMonthYear() : "?";
        BigDecimal amount = p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO;
        return type + " · " + tenant + " · " + month + " · $" + amount.toPlainString();
    }

    private String firstName(UserEntity user) {
        if (user == null || user.getName() == null) return "";
        String first = user.getName().trim().split("\\s+")[0];
        return first.isEmpty() ? "" : first;
    }

    private String digitsOnly(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D", "");
    }

    private int parseOption(String body) {
        try {
            return Integer.parseInt(digitsOnly(body));
        } catch (Exception ex) {
            return 0;
        }
    }

    private String userSafe(String msg) {
        if (msg == null || msg.isBlank()) return "error de validación";
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }
}
