package com.admindi.backend.service;

import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.VacancyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V51 — tests del guardrail anti-spam para la cadena de agentes inmobiliarios.
 *
 * <p>Reglas del producto:
 * <ul>
 *   <li>Un inmueble SIN historial de renta (ningún lease o leases &lt; 30 días)
 *       NO debe disparar difusión automática a agentes. El dueño debe contactar
 *       un agente manualmente para la primera colocación.</li>
 *   <li>Un inmueble CON al menos un lease cuyo período efectivo (startDate →
 *       min(endDate, hoy)) sea ≥ 30 días, sí habilita la difusión automática.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class VacancyAgentOrchestrationServiceHistoryGuardTest {

    @Mock VacancyRepository vacancyRepository;
    @Mock PropertyRepository propertyRepository;
    @Mock UserRepository userRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock AgentChainOrchestrationService chainOrchestrator;
    @Mock DomainEventDispatcher dispatcher;

    private VacancyAgentOrchestrationService newService() {
        return new VacancyAgentOrchestrationService(
                vacancyRepository, propertyRepository, userRepository,
                leaseRepository, chainOrchestrator, dispatcher);
    }

    private VacancyEntity vacancyFor(String propertyId, String ownerId) {
        VacancyEntity v = new VacancyEntity();
        v.setId("vac-1");
        v.setOwnerId(ownerId);
        v.setPropertyId(propertyId);
        return v;
    }

    private LeaseEntity leaseWithDates(LocalDate start, LocalDate end) {
        LeaseEntity l = new LeaseEntity();
        l.setStartDate(start);
        l.setEndDate(end);
        return l;
    }

    @Test
    void propertyWithNoLeasesDoesNotQualify() {
        when(leaseRepository.findByOwnerIdAndProperty_Id(anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        assertThat(newService().hasQualifyingRentalHistory("p1", "o1")).isFalse();
    }

    @Test
    void activeLeaseBelow30DaysDoesNotQualify() {
        LocalDate today = LocalDate.now();
        when(leaseRepository.findByOwnerIdAndProperty_Id("o1", "p1"))
                .thenReturn(List.of(leaseWithDates(today.minusDays(10), today.plusMonths(12))));

        assertThat(newService().hasQualifyingRentalHistory("p1", "o1")).isFalse();
    }

    @Test
    void activeLeaseWithOver30DaysQualifies() {
        LocalDate today = LocalDate.now();
        when(leaseRepository.findByOwnerIdAndProperty_Id("o1", "p1"))
                .thenReturn(List.of(leaseWithDates(today.minusDays(45), today.plusMonths(10))));

        assertThat(newService().hasQualifyingRentalHistory("p1", "o1")).isTrue();
    }

    @Test
    void completedLeaseWithOver30DaysQualifies() {
        LocalDate today = LocalDate.now();
        LeaseEntity completed = leaseWithDates(today.minusMonths(6), today.minusMonths(3));
        when(leaseRepository.findByOwnerIdAndProperty_Id("o1", "p1"))
                .thenReturn(List.of(completed));

        assertThat(newService().hasQualifyingRentalHistory("p1", "o1")).isTrue();
    }

    @Test
    void completedLeaseUnder30DaysDoesNotQualify() {
        LocalDate today = LocalDate.now();
        LeaseEntity shortLease = leaseWithDates(today.minusDays(45), today.minusDays(25));
        when(leaseRepository.findByOwnerIdAndProperty_Id("o1", "p1"))
                .thenReturn(List.of(shortLease));

        assertThat(newService().hasQualifyingRentalHistory("p1", "o1")).isFalse();
    }

    @Test
    void nullArgumentsAreSafe() {
        VacancyAgentOrchestrationService svc = newService();
        assertThat(svc.hasQualifyingRentalHistory(null, "o1")).isFalse();
        assertThat(svc.hasQualifyingRentalHistory("p1", null)).isFalse();
    }

    @Test
    void startChainIsBlockedWhenNoRentalHistory() {
        VacancyEntity v = vacancyFor("p1", "o1");
        when(leaseRepository.findByOwnerIdAndProperty_Id("o1", "p1"))
                .thenReturn(Collections.emptyList());
        when(chainOrchestrator.findActiveLink(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> newService().startChainIfApplicable(v))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("NO_RENTAL_HISTORY");

        // La cadena NO debe haberse iniciado.
        verify(chainOrchestrator, never()).startChain(anyString(), anyString(), anyString(), anyString());
        // No vacancy save() should have persisted AWAITING_AGENT.
        verify(vacancyRepository, never()).save(any(VacancyEntity.class));
    }

    @Test
    void alreadyActiveChainIsIdempotent() {
        VacancyEntity v = vacancyFor("p1", "o1");
        when(chainOrchestrator.findActiveLink(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(mock(com.admindi.backend.model.AgentNotificationChainEntity.class)));

        // No debe consultar leases ni arrancar cadena.
        newService().startChainIfApplicable(v);

        verify(leaseRepository, never()).findByOwnerIdAndProperty_Id(anyString(), anyString());
        verify(chainOrchestrator, never()).startChain(anyString(), anyString(), anyString(), anyString());
    }
}
