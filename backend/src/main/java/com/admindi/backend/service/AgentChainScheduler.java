package com.admindi.backend.service;

import com.admindi.backend.model.AgentNotificationChainEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cron scheduler Fase 2. Corre:
 * <ul>
 *   <li>Revisión de links {@code PENDING} en {@code agent_notification_chain}
 *       cuya {@code expires_at} ya pasó. Para cada uno, se los marca AUTO_REJECTED_TIMEOUT
 *       y se delega al orquestador correspondiente (vacancy o maintenance) para
 *       avanzar la cadena.</li>
 *   <li>Recordatorios 24h para prospectos PENDING sin decisión del dueño.</li>
 * </ul>
 *
 * <p>Los cron strings viven en {@code application.yml} bajo {@code admindi.agents.*}
 * para que el SUPER_ADMIN pueda tunearlos sin cambiar código.
 */
@Component
public class AgentChainScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AgentChainScheduler.class);

    private final AgentChainOrchestrationService chainOrchestrator;
    private final VacancyAgentOrchestrationService vacancyOrchestrator;
    private final MaintenanceWorkflowService maintenanceWorkflow;
    private final ProspectService prospectService;

    public AgentChainScheduler(AgentChainOrchestrationService chainOrchestrator,
                               VacancyAgentOrchestrationService vacancyOrchestrator,
                               MaintenanceWorkflowService maintenanceWorkflow,
                               ProspectService prospectService) {
        this.chainOrchestrator = chainOrchestrator;
        this.vacancyOrchestrator = vacancyOrchestrator;
        this.maintenanceWorkflow = maintenanceWorkflow;
        this.prospectService = prospectService;
    }

    /**
     * Revisa cada X (default 15 minutos) si hay links expirados. Los timeouts ocurren
     * a las 72h por default (ver {@code admindi.agents.chain-timeout-hours}); por eso
     * no hace falta correr esto más seguido.
     */
    @Scheduled(cron = "${admindi.agents.chain-scheduler-cron:0 */15 * * * *}")
    @Transactional
    public void sweepExpiredChainLinks() {
        // Marca todos los links PENDING con expires_at < now como AUTO_REJECTED_TIMEOUT
        // y devuelve las filas afectadas para que avancemos la cadena de cada una.
        List<AgentNotificationChainEntity> timedOut = chainOrchestrator.autoRejectExpired();
        if (timedOut.isEmpty()) return;
        logger.info("[AgentChainScheduler] Procesando {} links expirados", timedOut.size());

        for (AgentNotificationChainEntity link : timedOut) {
            try {
                switch (link.getResourceType()) {
                    case AgentNotificationChainEntity.RESOURCE_VACANCY ->
                            vacancyOrchestrator.handleAutoTimeout(link);
                    case AgentNotificationChainEntity.RESOURCE_MAINTENANCE_TICKET ->
                            maintenanceWorkflow.handleProviderChainTimeout(link);
                    default -> logger.warn("[AgentChainScheduler] resourceType desconocido: {}", link.getResourceType());
                }
            } catch (Exception ex) {
                logger.error("[AgentChainScheduler] Error procesando link id={}: {}", link.getId(), ex.getMessage(), ex);
            }
        }
    }

    /**
     * Recordatorios 24h para prospectos pendientes de decisión del dueño.
     */
    @Scheduled(cron = "${admindi.agents.prospect-reminder-cron:0 0 9 * * *}")
    @Transactional
    public void sweepProspectReminders() {
        try {
            int sent = prospectService.runPendingReminders();
            if (sent > 0) {
                logger.info("[AgentChainScheduler] Recordatorios prospecto enviados: {}", sent);
            }
        } catch (Exception ex) {
            logger.error("[AgentChainScheduler] Error en recordatorios prospecto: {}", ex.getMessage(), ex);
        }
    }
}
