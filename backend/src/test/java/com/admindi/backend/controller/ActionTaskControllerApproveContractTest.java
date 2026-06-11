package com.admindi.backend.controller;

import com.admindi.backend.approval.ApprovalTaskHandler;
import com.admindi.backend.approval.ApprovalTaskRegistry;
import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.exception.GlobalExceptionHandler;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AuditService;
import com.admindi.backend.service.ReauthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for {@link ActionTaskController} post approval-framework refactor.
 *
 * <p>The controller no longer knows about any specific eventType — it delegates to
 * {@link ApprovalTaskRegistry} which resolves a {@link ApprovalTaskHandler}. These tests
 * verify the generic invariants (OPEN status, caller-assignment, registry lookup) and that
 * the resolved handler's {@code onApprove} / {@code onReject} are invoked with the right
 * arguments.
 */
@ExtendWith(MockitoExtension.class)
class ActionTaskControllerApproveContractTest {

    // V50 — Spring Security auth principal ahora transporta el username canónico
    // (no el email). Mantenemos el nombre del constante como OWNER_EMAIL sólo por
    // continuidad del diff — su valor ya es un username sintético.
    private static final String OWNER_EMAIL = "owner-contract-test";

    @Mock
    private ActionTaskRepository taskRepo;

    @Mock
    private UserRepository userRepo;

    @Mock
    private ApprovalTaskRegistry approvalRegistry;

    @Mock
    private ApprovalTaskHandler propertyDeleteHandler;

    @Mock
    private ReauthService reauthService;

    @Mock
    private AuditService auditService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        OWNER_EMAIL,
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));

        ActionTaskController controller = new ActionTaskController(
                taskRepo, userRepo, approvalRegistry, reauthService, auditService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UserEntity user = new UserEntity();
        user.setId("user-1");
        // V50 — el controller resuelve al actor vía findByLoginIdentifier (username
        // primero, fallback a email). Mockito no ejecuta el default method del
        // repositorio, por lo que stubbeamos directamente aquí para que la cadena
        // resuelva al UserEntity de prueba.
        when(userRepo.findByLoginIdentifier(OWNER_EMAIL)).thenReturn(Optional.of(user));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approve_openTaskUnsupportedEventType_returns422_andDoesNotInvokeAnyHandler() throws Exception {
        ActionTaskEntity task = new ActionTaskEntity();
        task.setId("task-wrong-type");
        task.setUserId("user-1");
        task.setOwnerId("org-1");
        task.setStatus("OPEN");
        task.setEventType("VACANCY_AGENT_NEEDED");
        when(taskRepo.findById("task-wrong-type")).thenReturn(Optional.of(task));
        when(approvalRegistry.resolveHandler("VACANCY_AGENT_NEEDED"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "El tipo de tarea 'VACANCY_AGENT_NEEDED' no admite approve/reject."));

        mockMvc.perform(post("/api/tasks/task-wrong-type/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret\",\"mfaCode\":null}"))
                // V50 — MockMvc standalone resuelve ResponseStatusException con
                // ResponseStatusExceptionResolver ANTES del @ExceptionHandler global,
                // por lo que el body queda vacío. Nos quedamos con el contrato HTTP
                // (status code) que es lo que el frontend consume.
                .andExpect(status().isUnprocessableEntity());

        verify(propertyDeleteHandler, never()).onApprove(any(), any(), any());
    }

    @Test
    void approve_resolvedTask_returns409_beforeHittingRegistry() throws Exception {
        ActionTaskEntity task = new ActionTaskEntity();
        task.setId("task-closed");
        task.setUserId("user-1");
        task.setOwnerId("org-1");
        task.setStatus("RESOLVED");
        task.setEventType(ActionTaskEventType.PROPERTY_DELETE_REQUESTED);
        when(taskRepo.findById("task-closed")).thenReturn(Optional.of(task));

        mockMvc.perform(post("/api/tasks/task-closed/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret\",\"mfaCode\":null}"))
                .andExpect(status().isConflict());

        verify(approvalRegistry, never()).resolveHandler(any());
        verify(propertyDeleteHandler, never()).onApprove(any(), any(), any());
    }

    @Test
    void reject_openTaskUnsupportedEventType_returns422() throws Exception {
        ActionTaskEntity task = new ActionTaskEntity();
        task.setId("task-rej-wrong");
        task.setUserId("user-1");
        task.setOwnerId("org-1");
        task.setStatus("OPEN");
        task.setEventType("MAINTENANCE_TICKET_ASSIGNED");
        when(taskRepo.findById("task-rej-wrong")).thenReturn(Optional.of(task));
        when(approvalRegistry.resolveHandler("MAINTENANCE_TICKET_ASSIGNED"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "El tipo de tarea 'MAINTENANCE_TICKET_ASSIGNED' no admite approve/reject."));

        mockMvc.perform(post("/api/tasks/task-rej-wrong/reject"))
                .andExpect(status().isUnprocessableEntity());

        verify(propertyDeleteHandler, never()).onReject(any(), any());
    }

    @Test
    void approve_validTask_delegatesToResolvedHandler() throws Exception {
        ActionTaskEntity task = new ActionTaskEntity();
        task.setId("task-del");
        task.setUserId("user-1");
        task.setOwnerId("org-1");
        task.setStatus("OPEN");
        task.setEventType(ActionTaskEventType.PROPERTY_DELETE_REQUESTED);
        when(taskRepo.findById("task-del")).thenReturn(Optional.of(task));
        when(approvalRegistry.resolveHandler(ActionTaskEventType.PROPERTY_DELETE_REQUESTED))
                .thenReturn(propertyDeleteHandler);

        mockMvc.perform(post("/api/tasks/task-del/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw\",\"mfaCode\":\"123456\"}"))
                .andExpect(status().isOk());

        verify(propertyDeleteHandler).onApprove(eq(task), eq("pw"), eq("123456"));
    }

    @Test
    void reject_validTask_delegatesToHandlerWithOptionalReason() throws Exception {
        ActionTaskEntity task = new ActionTaskEntity();
        task.setId("task-rej");
        task.setUserId("user-1");
        task.setOwnerId("org-1");
        task.setStatus("OPEN");
        task.setEventType(ActionTaskEventType.PROPERTY_DELETE_REQUESTED);
        when(taskRepo.findById("task-rej")).thenReturn(Optional.of(task));
        when(approvalRegistry.resolveHandler(ActionTaskEventType.PROPERTY_DELETE_REQUESTED))
                .thenReturn(propertyDeleteHandler);

        mockMvc.perform(post("/api/tasks/task-rej/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"cambio de planes\"}"))
                .andExpect(status().isOk());

        verify(propertyDeleteHandler).onReject(eq(task), eq("cambio de planes"));
    }
}
