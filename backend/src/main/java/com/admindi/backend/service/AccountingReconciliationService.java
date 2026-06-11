package com.admindi.backend.service;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.LeaseStatus;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyStatus;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Herramienta de reconciliación: recomputa el estado derivado (ocupación de inmuebles) y anula
 * facturas "fantasma" — invoices abiertas ligadas a expedientes archivados — que pudieron haber
 * quedado en la DB por versiones previas del cron o por operaciones parciales fallidas.
 *
 * <p>Es idempotente: ejecutarlo N veces converge al mismo estado. Solo toca estado derivado;
 * nunca modifica facturas PAID (historial financiero inmutable) ni elimina expedientes.
 */
@Service
public class AccountingReconciliationService {

    private final PropertyRepository propertyRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final LeaseRepository leaseRepository;
    private final InvoiceRepository invoiceRepository;

    public AccountingReconciliationService(PropertyRepository propertyRepository,
                                           TenantProfileRepository tenantProfileRepository,
                                           LeaseRepository leaseRepository,
                                           InvoiceRepository invoiceRepository) {
        this.propertyRepository = propertyRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.leaseRepository = leaseRepository;
        this.invoiceRepository = invoiceRepository;
    }

    public static class ReconciliationReport {
        private int propertiesScanned;
        private int propertiesUpdated;
        private int ghostInvoicesVoided;
        private int orphanLeasesTerminated;

        public int getPropertiesScanned() { return propertiesScanned; }
        public void setPropertiesScanned(int v) { this.propertiesScanned = v; }
        public int getPropertiesUpdated() { return propertiesUpdated; }
        public void setPropertiesUpdated(int v) { this.propertiesUpdated = v; }
        public int getGhostInvoicesVoided() { return ghostInvoicesVoided; }
        public void setGhostInvoicesVoided(int v) { this.ghostInvoicesVoided = v; }
        public int getOrphanLeasesTerminated() { return orphanLeasesTerminated; }
        public void setOrphanLeasesTerminated(int v) { this.orphanLeasesTerminated = v; }
    }

    @Transactional
    public ReconciliationReport reconcileOwner(String ownerId) {
        ReconciliationReport report = new ReconciliationReport();

        // 1) Índice en memoria de expedientes activos (owner + property + tenant userId).
        List<TenantProfileEntity> profiles = tenantProfileRepository.findByOwnerId(ownerId);
        Set<String> activeProfileKeys = new HashSet<>(); // "propertyId|userId"
        Set<String> activePropertyIds = new HashSet<>();
        for (TenantProfileEntity p : profiles) {
            if (p.getArchivedAt() != null) continue;
            if (p.getPropertyId() == null || p.getPropertyId().isBlank()) continue;
            activeProfileKeys.add(p.getPropertyId() + "|" + p.getUserId());
            activePropertyIds.add(p.getPropertyId());
        }

        // 2) Terminar leases huérfanos: ACTIVE sin expediente activo que lo respalde.
        //    Archivar el expediente = cierre operativo del contrato. Si el lease sigue ACTIVE
        //    sin profile activo matching (owner + property + tenant user), es basura/residuo
        //    de una operación anterior y debe liberar al inmueble.
        for (LeaseEntity l : leaseRepository.findByOwnerId(ownerId)) {
            if (l.getStatus() != LeaseStatus.ACTIVE) continue;
            PropertyEntity lp = l.resolvePropertyEntity();
            String pid = lp != null ? lp.getId() : null;
            String uid = l.getTenant() != null ? l.getTenant().getId() : null;
            if (pid == null || uid == null) continue;
            String key = pid + "|" + uid;
            if (!activeProfileKeys.contains(key)) {
                l.setStatus(LeaseStatus.TERMINATED);
                leaseRepository.save(l);
                report.setOrphanLeasesTerminated(report.getOrphanLeasesTerminated() + 1);
            } else {
                activePropertyIds.add(pid);
            }
        }

        // 3) Reconciliar status de cada inmueble (respetando MAINTENANCE/DELETED).
        List<PropertyEntity> ownedProps = propertyRepository.findByOwnerId(ownerId);
        for (PropertyEntity prop : ownedProps) {
            report.setPropertiesScanned(report.getPropertiesScanned() + 1);
            if (prop.getStatus() == PropertyStatus.DELETED || prop.getStatus() == PropertyStatus.MAINTENANCE) {
                continue;
            }
            PropertyStatus target = activePropertyIds.contains(prop.getId())
                    ? PropertyStatus.OCCUPIED
                    : PropertyStatus.AVAILABLE;
            // Fase 2: respetar ciclo comercial — el orquestador de vacancia es dueño de
            // PENDING_RENT / PROSPECT_PROPOSED / AWAITING_CONTRACT. El startup reconciler
            // sólo debería moverlos si aparece un lease ACTIVE (target = OCCUPIED); jamás
            // debe pisar el estado comercial al regresar a AVAILABLE (eso sería cancelar
            // operaciones en curso del agente inmobiliario).
            if (target == PropertyStatus.AVAILABLE && (
                    prop.getStatus() == PropertyStatus.PENDING_RENT
                    || prop.getStatus() == PropertyStatus.PROSPECT_PROPOSED
                    || prop.getStatus() == PropertyStatus.AWAITING_CONTRACT)) {
                continue;
            }
            if (prop.getStatus() != target) {
                prop.setStatus(target);
                propertyRepository.save(prop);
                report.setPropertiesUpdated(report.getPropertiesUpdated() + 1);
            }
        }

        // 4) Anular facturas fantasma: abiertas contra expedientes archivados o huérfanos.
        List<InvoiceEntity> invoices = invoiceRepository.findByOwnerId(ownerId);
        for (InvoiceEntity inv : invoices) {
            String s = inv.getStatus() == null ? "" : inv.getStatus().toUpperCase();
            boolean terminal = "PAID".equals(s) || "VOID".equals(s) || "VOIDED".equals(s)
                    || "CANCELLED".equals(s) || "CANCELED".equals(s);
            if (terminal) continue;

            TenantProfileEntity tp = inv.getTenantProfileId() != null
                    ? tenantProfileRepository.findById(inv.getTenantProfileId()).orElse(null)
                    : null;
            boolean archived = tp != null && tp.getArchivedAt() != null;
            boolean orphan = inv.getTenantProfileId() != null && tp == null;
            if (archived || orphan) {
                inv.setStatus("VOID");
                inv.setSettlementStatus("CANCELLED");
                inv.setOutstandingAmount(BigDecimal.ZERO);
                invoiceRepository.save(inv);
                report.setGhostInvoicesVoided(report.getGhostInvoicesVoided() + 1);
            }
        }

        return report;
    }
}
