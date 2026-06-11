package com.admindi.backend.config;

import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.service.AccountingReconciliationService;
import com.admindi.backend.service.AccountingReconciliationService.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Defense-in-depth: al boot de la app, reconciliamos el estado derivado de cada organización
 * (ocupación de inmuebles + invoices fantasma). Flyway V32 ya hace la pasada inicial a nivel DB;
 * este runner garantiza convergencia incluso cuando Flyway ya corrió y posteriormente se escribió
 * estado inconsistente, o cuando la migración no aplica (perfiles distintos / backfill manual).
 *
 * <p>Idempotente: solo corrige estado que no corresponda al dominio vigente. Sin side effects
 * sobre PAID, DELETED ni MAINTENANCE.
 *
 * <p>{@code @Order(1000)} para correr después del seeding del admin y de Flyway, pero antes de
 * servir tráfico.
 */
@Component
@Order(1000)
public class StartupReconciliationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciliationRunner.class);

    private final PropertyRepository propertyRepository;
    private final AccountingReconciliationService reconciliationService;

    public StartupReconciliationRunner(PropertyRepository propertyRepository,
                                       AccountingReconciliationService reconciliationService) {
        this.propertyRepository = propertyRepository;
        this.reconciliationService = reconciliationService;
    }

    @Override
    public void run(String... args) {
        Set<String> ownerIds = new HashSet<>();
        for (PropertyEntity p : propertyRepository.findAll()) {
            if (p.getOwnerId() != null && !p.getOwnerId().isBlank()) {
                ownerIds.add(p.getOwnerId());
            }
        }
        if (ownerIds.isEmpty()) {
            log.info("[ADMINDI] Startup reconciliation skipped: no organizations found.");
            return;
        }

        int totalUpdated = 0;
        int totalVoided = 0;
        int totalLeases = 0;
        for (String ownerId : ownerIds) {
            try {
                ReconciliationReport r = reconciliationService.reconcileOwner(ownerId);
                totalUpdated += r.getPropertiesUpdated();
                totalVoided += r.getGhostInvoicesVoided();
                totalLeases += r.getOrphanLeasesTerminated();
                if (r.getPropertiesUpdated() > 0 || r.getGhostInvoicesVoided() > 0 || r.getOrphanLeasesTerminated() > 0) {
                    log.info("[ADMINDI] Reconciled owner={} propertiesUpdated={} ghostInvoicesVoided={} orphanLeasesTerminated={}",
                            ownerId, r.getPropertiesUpdated(), r.getGhostInvoicesVoided(), r.getOrphanLeasesTerminated());
                }
            } catch (RuntimeException ex) {
                log.warn("[ADMINDI] Reconciliation failed for owner={}: {}", ownerId, ex.getMessage());
            }
        }
        log.info("[ADMINDI] Startup reconciliation done: organizations={} propertiesUpdated={} ghostInvoicesVoided={} orphanLeasesTerminated={}",
                ownerIds.size(), totalUpdated, totalVoided, totalLeases);
    }
}
