package com.admindi.backend.service;

import com.admindi.backend.dto.ReportingPeriodBoundsDTO;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.stream.Collectors;

@Service
public class ReportingPeriodService {

    private static final Logger log = LoggerFactory.getLogger(ReportingPeriodService.class);
    // Regla de negocio: toda la contabilidad, bitácora y validación de periodos opera en CDMX.
    // No dependemos de ZoneId.systemDefault() porque:
    //   · En producción el contenedor suele correr en UTC y abriría la ventana del mes un día antes.
    //   · En desarrollo Windows/Mac los operadores a veces alteran la hora del SO (test de bordes)
    //     y eso adelantaría reportes mensuales, cerrando el mes antes de tiempo y dejando pasar
    //     invoices que todavía pertenecen al mes vigente.
    // Con ZoneId.of("America/Mexico_City") el "max" de YearMonth.now() es estable e independiente
    // del servidor.
    private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");

    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;

    @Autowired
    public ReportingPeriodService(UserRepository userRepository, PropertyRepository propertyRepository) {
        this.userRepository = userRepository;
        this.propertyRepository = propertyRepository;
    }

    private void logReportingAccess(String endpointLabel) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            log.warn("[reporting-bounds] {} auth=null", endpointLabel);
            return;
        }
        String authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        log.info("[reporting-bounds] endpoint={} principal={} authorities=[{}]",
                endpointLabel, auth.getName(), authorities);
    }

    public String resolveOrganizationOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    public YearMonth getMaxYearMonth() {
        return YearMonth.now(ZONE);
    }

    public YearMonth getOwnerMinYearMonth() {
        String ownerRootId = resolveOrganizationOwnerId();
        UserEntity owner = userRepository.findById(ownerRootId).orElseThrow(() -> new RuntimeException("Organizacion no encontrada."));
        LocalDateTime ca = owner.getCreatedAt();
        if (ca == null) {
            return YearMonth.of(1970, 1);
        }
        return YearMonth.from(ca);
    }

    public YearMonth getPropertyMinYearMonth(String propertyId) {
        PropertyEntity p = loadPropertyForCurrentOrg(propertyId);
        YearMonth ownerMin = getOwnerMinYearMonth();
        LocalDateTime pca = p.getCreatedAt();
        YearMonth propMin = pca == null ? ownerMin : YearMonth.from(pca);
        return ownerMin.isAfter(propMin) ? ownerMin : propMin;
    }

    private PropertyEntity loadPropertyForCurrentOrg(String propertyId) {
        PropertyEntity p = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new RuntimeException("Property not found."));
        String orgId = resolveOrganizationOwnerId();
        if (!orgId.equals(p.getOwnerId())) {
            throw new RuntimeException("Acceso denegado.");
        }
        return p;
    }

    public void validateOwnerMonthYear(String monthYear) {
        YearMonth ym = YearMonth.parse(monthYear);
        YearMonth min = getOwnerMinYearMonth();
        YearMonth max = getMaxYearMonth();
        if (ym.isBefore(min) || ym.isAfter(max)) {
            throw new RuntimeException(periodErrorMessage(min, max));
        }
    }

    public void validatePropertyMonthYear(String propertyId, String monthYear) {
        loadPropertyForCurrentOrg(propertyId);
        YearMonth ym = YearMonth.parse(monthYear);
        YearMonth min = getPropertyMinYearMonth(propertyId);
        YearMonth max = getMaxYearMonth();
        if (ym.isBefore(min) || ym.isAfter(max)) {
            throw new RuntimeException(periodErrorMessage(min, max));
        }
    }

    public void validatePropertyYear(String propertyId, int year) {
        loadPropertyForCurrentOrg(propertyId);
        YearMonth min = getPropertyMinYearMonth(propertyId);
        YearMonth max = getMaxYearMonth();
        int minYear = min.getYear();
        int maxYear = max.getYear();
        if (year < minYear || year > maxYear) {
            throw new RuntimeException(String.format(
                    "Anio fuera de rango: use entre %d y %d.", minYear, maxYear));
        }
    }

    private static String periodErrorMessage(YearMonth min, YearMonth max) {
        return String.format("Periodo fuera de rango: use entre %s y %s (inclusive).", min, max);
    }

    public ReportingPeriodBoundsDTO getOwnerBounds() {
        logReportingAccess("GET /api/owner/reporting-period-bounds");
        YearMonth min = getOwnerMinYearMonth();
        YearMonth max = getMaxYearMonth();
        return new ReportingPeriodBoundsDTO(min.toString(), max.toString(), min.getYear(), max.getYear());
    }

    public ReportingPeriodBoundsDTO getPropertyBounds(String propertyId) {
        logReportingAccess("GET /api/properties/{id}/reporting-period-bounds");
        YearMonth min = getPropertyMinYearMonth(propertyId);
        YearMonth max = getMaxYearMonth();
        return new ReportingPeriodBoundsDTO(min.toString(), max.toString(), min.getYear(), max.getYear());
    }
}