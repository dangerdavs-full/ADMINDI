package com.admindi.backend.whatsapp;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.WhatsappConversationStateEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.WhatsappConversationStateRepository;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Valida que el informe del dueño solo ofrezca datos existentes:
 *  - meses únicamente con facturas reales (0, 1 o varios meses),
 *  - arrendatarios del mes elegido (1 o muchos),
 *  - autoselección cuando solo hay una opción.
 */
class WhatsAppOwnerReportFlowTest {

    private static final String PHONE = "+5215512345678";
    private static final String OWNER_ID = "owner-1";

    private WhatsAppOwnerReportFlow flow;
    private WhatsAppSessionService sessions;
    private TwilioWhatsAppService twilio;
    private InvoiceRepository invoiceRepo;
    private PaymentRepository paymentRepo;
    private TenantProfileRepository tenantProfileRepo;
    private PropertyRepository propertyRepo;
    private UserRepository userRepo;

    private UserEntity owner;
    private WhatsappConversationStateEntity session;

    @BeforeEach
    void setUp() {
        WhatsappConversationStateRepository stateRepo =
                mock(WhatsappConversationStateRepository.class);
        when(stateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        sessions = new WhatsAppSessionService(stateRepo);
        ReflectionTestUtils.setField(sessions, "ttlMinutes", 15);

        twilio = mock(TwilioWhatsAppService.class);
        invoiceRepo = mock(InvoiceRepository.class);
        paymentRepo = mock(PaymentRepository.class);
        tenantProfileRepo = mock(TenantProfileRepository.class);
        propertyRepo = mock(PropertyRepository.class);
        userRepo = mock(UserRepository.class);
        when(paymentRepo.findByInvoiceId(anyString())).thenReturn(List.of());

        flow = new WhatsAppOwnerReportFlow(sessions, twilio, invoiceRepo, paymentRepo,
                tenantProfileRepo, propertyRepo, userRepo);

        owner = new UserEntity();
        owner.setId(OWNER_ID);
        owner.setName("Dueño Prueba");

        session = new WhatsappConversationStateEntity();
        session.setPhoneE164(PHONE);
        session.setUserId(OWNER_ID);
        session.setCurrentState(WhatsAppBotState.OWNER_MENU.name());
        session.setContextJson("{}");
    }

    // ─── Meses: solo los que existen ─────────────────────────────────────

    @Test
    void startReportFlow_sinFacturas_avisaYRegresaAlMenu() {
        when(invoiceRepo.findByOwnerId(OWNER_ID)).thenReturn(List.of());

        flow.startReportFlow(PHONE, owner, session);

        assertEquals(WhatsAppBotState.OWNER_MENU, sessions.currentState(session));
        assertTrue(lastMessage().contains("no tienes facturas")
                        || lastMessage().contains("Aún no tienes facturas"),
                "Debe avisar que no hay facturas: " + lastMessage());
    }

    @Test
    void startReportFlow_unSoloMes_autoseleccionaYPreguntaAlcance() {
        when(invoiceRepo.findByOwnerId(OWNER_ID)).thenReturn(List.of(
                invoice("2026-05", "tp-1", "8500", "8500", "PAID", "PAID")));

        flow.startReportFlow(PHONE, owner, session);

        assertEquals(WhatsAppBotState.OWNER_REPORT_SCOPE, sessions.currentState(session));
        assertEquals("2026-05", sessions.getContext(session).get("reportMonth"));
        String all = allMessages();
        assertTrue(all.contains("Mayo 2026"), "Debe nombrar el único mes: " + all);
        assertFalse(all.contains("Junio"), "No debe inventar meses: " + all);
    }

    @Test
    void startReportFlow_listaSoloMesesConFacturas() {
        // Mayo y marzo existen; abril NO. La VOID de enero tampoco cuenta.
        InvoiceEntity voided = invoice("2026-01", "tp-1", "8500", "0", "UNPAID", "VOID");
        when(invoiceRepo.findByOwnerId(OWNER_ID)).thenReturn(List.of(
                invoice("2026-05", "tp-1", "8500", "0", "UNPAID", "PENDING"),
                invoice("2026-03", "tp-1", "8500", "8500", "PAID", "PAID"),
                voided));

        flow.startReportFlow(PHONE, owner, session);

        assertEquals(WhatsAppBotState.OWNER_REPORT_MONTH, sessions.currentState(session));
        String msg = lastMessage();
        assertTrue(msg.contains("Mayo 2026"), msg);
        assertTrue(msg.contains("Marzo 2026"), msg);
        assertFalse(msg.contains("Abril"), "Abril no tiene facturas: " + msg);
        assertFalse(msg.contains("Enero"), "Enero solo tiene factura VOID: " + msg);

        Object options = sessions.getContext(session).get("reportMonthOptions");
        assertEquals(List.of("2026-05", "2026-03"), options);
    }

    @Test
    void handleMonthChoice_mesEscritoSinFacturas_seRechaza() {
        when(invoiceRepo.findByOwnerId(OWNER_ID)).thenReturn(List.of(
                invoice("2026-05", "tp-1", "8500", "0", "UNPAID", "PENDING"),
                invoice("2026-04", "tp-1", "8500", "8500", "PAID", "PAID")));
        flow.startReportFlow(PHONE, owner, session);
        assertEquals(WhatsAppBotState.OWNER_REPORT_MONTH, sessions.currentState(session));

        flow.handleMonthChoice(PHONE, owner, session, "01-2026");

        assertEquals(WhatsAppBotState.OWNER_REPORT_MONTH, sessions.currentState(session));
        String msg = lastMessage();
        assertTrue(msg.contains("No tienes facturas en Enero 2026"), msg);
        assertTrue(msg.contains("Mayo 2026"), "Debe listar los meses reales: " + msg);
    }

    @Test
    void handleMonthChoice_numeroDeListaValido_pasaAAlcance() {
        when(invoiceRepo.findByOwnerId(OWNER_ID)).thenReturn(List.of(
                invoice("2026-05", "tp-1", "8500", "0", "UNPAID", "PENDING"),
                invoice("2026-04", "tp-1", "8500", "8500", "PAID", "PAID")));
        flow.startReportFlow(PHONE, owner, session);

        flow.handleMonthChoice(PHONE, owner, session, "2");

        assertEquals(WhatsAppBotState.OWNER_REPORT_SCOPE, sessions.currentState(session));
        assertEquals("2026-04", sessions.getContext(session).get("reportMonth"));
    }

    // ─── Arrendatarios: 1 vs 10 ──────────────────────────────────────────

    @Test
    void detalle_unSoloInquilino_mandaDetalleSinPreguntar() {
        setupTenants(1, "2026-05");
        goToScope("2026-05");

        flow.handleScopeChoice(PHONE, owner, session, "2");

        assertEquals(WhatsAppBotState.OWNER_MENU, sessions.currentState(session));
        String all = allMessages();
        assertTrue(all.contains("Inquilino 1"), "Debe mandar el detalle directo: " + all);
        assertFalse(all.contains("¿Qué arrendatario?"),
                "Con 1 inquilino no debe mostrar selector: " + all);
    }

    @Test
    void detalle_diezInquilinos_listaLosDiezYResuelveElElegido() {
        setupTenants(10, "2026-05");
        goToScope("2026-05");

        flow.handleScopeChoice(PHONE, owner, session, "2");

        assertEquals(WhatsAppBotState.OWNER_REPORT_TENANT_PICK, sessions.currentState(session));
        String picker = lastMessage();
        assertTrue(picker.contains("10)"), "Debe listar las 10 opciones: " + picker);
        Object ids = sessions.getContext(session).get("reportTenantIds");
        assertEquals(10, ((List<?>) ids).size());

        flow.handleTenantPick(PHONE, owner, session, "10");

        assertEquals(WhatsAppBotState.OWNER_MENU, sessions.currentState(session));
        assertTrue(allMessages().contains("Renta: $8500"),
                "Debe mostrar el detalle del elegido: " + allMessages());
    }

    @Test
    void handleTenantPick_numeroFueraDeRango_noTruena() {
        setupTenants(3, "2026-05");
        goToScope("2026-05");
        flow.handleScopeChoice(PHONE, owner, session, "2");

        flow.handleTenantPick(PHONE, owner, session, "99");

        assertEquals(WhatsAppBotState.OWNER_REPORT_TENANT_PICK, sessions.currentState(session));
        assertTrue(lastMessage().contains("Opción no válida"), lastMessage());
    }

    // ─── Resumen general: 1 vs 10 inmuebles ──────────────────────────────

    @Test
    void resumen_unInmueble_incluyeTotales() {
        setupTenants(1, "2026-05");

        String report = flow.buildMonthlySummary(owner, java.time.YearMonth.of(2026, 5));

        assertTrue(report.contains("Inmueble 1"), report);
        assertTrue(report.contains("Cobrado: $"), report);
        assertTrue(report.contains("Rentas liquidadas: 0 de 1"), report);
    }

    @Test
    void resumen_diezInmuebles_incluyeLosDiez() {
        setupTenants(10, "2026-05");

        String report = flow.buildMonthlySummary(owner, java.time.YearMonth.of(2026, 5));

        for (int i = 1; i <= 10; i++) {
            assertTrue(report.contains("Inmueble " + i), "Falta inmueble " + i + ": " + report);
        }
        assertTrue(report.contains("*Pendiente* (10)"), report);
        assertTrue(report.contains("Rentas liquidadas: 0 de 10"), report);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /**
     * Crea n inquilinos, cada uno con inmueble propio y una factura PENDIENTE
     * de $8500 en {@code monthYear}.
     */
    private void setupTenants(int n, String monthYear) {
        List<InvoiceEntity> invoices = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String tpId = "tp-" + i;
            invoices.add(invoice(monthYear, tpId, "8500", "0", "UNPAID", "PENDING"));

            TenantProfileEntity profile = new TenantProfileEntity();
            profile.setId(tpId);
            profile.setOwnerId(OWNER_ID);
            profile.setUserId("user-" + i);
            profile.setPropertyId("prop-" + i);
            when(tenantProfileRepo.findById(tpId)).thenReturn(Optional.of(profile));

            UserEntity tenant = new UserEntity();
            tenant.setId("user-" + i);
            tenant.setName("Inquilino " + i);
            when(userRepo.findById("user-" + i)).thenReturn(Optional.of(tenant));

            PropertyEntity property = new PropertyEntity();
            property.setId("prop-" + i);
            property.setName("Inmueble " + i);
            when(propertyRepo.findById("prop-" + i)).thenReturn(Optional.of(property));

            when(invoiceRepo.findByTenantProfileIdAndMonthYear(tpId, monthYear))
                    .thenReturn(Optional.of(invoices.get(invoices.size() - 1)));
        }
        when(invoiceRepo.findByOwnerId(OWNER_ID)).thenReturn(invoices);
    }

    private void goToScope(String monthYear) {
        sessions.transition(session, WhatsAppBotState.OWNER_REPORT_SCOPE,
                java.util.Map.of("reportMonth", monthYear));
    }

    private InvoiceEntity invoice(String monthYear, String tenantProfileId,
                                   String total, String paid,
                                   String settlementStatus, String status) {
        InvoiceEntity inv = new InvoiceEntity();
        inv.setTenantProfileId(tenantProfileId);
        inv.setOwnerId(OWNER_ID);
        inv.setMonthYear(monthYear);
        inv.setTotalAmount(new BigDecimal(total));
        inv.setPaidAmount(new BigDecimal(paid));
        inv.setOutstandingAmount(new BigDecimal(total).subtract(new BigDecimal(paid)));
        inv.setSettlementStatus(settlementStatus);
        inv.setStatus(status);
        return inv;
    }

    private String lastMessage() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(twilio, atLeastOnce()).sendFreeformWhatsApp(eq(PHONE), captor.capture());
        List<String> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private String allMessages() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(twilio, atLeastOnce()).sendFreeformWhatsApp(eq(PHONE), captor.capture());
        return String.join("\n---\n", captor.getAllValues());
    }
}
