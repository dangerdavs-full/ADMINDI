package com.admindi.backend.service;

import com.admindi.backend.dto.UnitDTO;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.UnitEntity;
import com.admindi.backend.model.UnitOccupancyStatus;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.UnitRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UnitService {

    private final UnitRepository unitRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final DomainEventDispatcher dispatcher;

    @Autowired
    public UnitService(UnitRepository unitRepository, PropertyRepository propertyRepository,
                       UserRepository userRepository, DomainEventDispatcher dispatcher) {
        this.unitRepository = unitRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.dispatcher = dispatcher;
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    private String resolveActorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public UnitDTO createUnit(UnitDTO dto) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        PropertyEntity property = propertyRepository.findById(dto.getPropertyId())
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));

        if (!property.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Aislamiento IDOR: Inmueble pertenece a otro Dueño.");
        }

        UnitEntity unit = new UnitEntity();
        unit.setOwnerId(ownerId);
        unit.setProperty(property);
        unit.setName(dto.getName());
        unit.setType(dto.getType());
        unit.setStatus(UnitOccupancyStatus.VACANT);
        unit.setSquareMeters(dto.getSquareMeters());
        unit.setBedrooms(dto.getBedrooms());
        unit.setBathrooms(dto.getBathrooms());
        unit.setFloorCode(dto.getFloorCode());
        unit.setNotes(dto.getNotes());

        UnitEntity saved = unitRepository.save(unit);

        dispatcher.dispatch("UNIT_CREATED",
                "Unidad creada: " + saved.getName() + " en " + property.getName(),
                null, ownerId, actor, null);

        return mapToDTO(saved);
    }

    public UnitDTO updateUnit(String id, UnitDTO dto) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        UnitEntity unit = unitRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Unidad no encontrada."));

        if (!unit.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Aislamiento IDOR.");
        }

        unit.setName(dto.getName());
        unit.setType(dto.getType());
        unit.setSquareMeters(dto.getSquareMeters());
        unit.setBedrooms(dto.getBedrooms());
        unit.setBathrooms(dto.getBathrooms());
        unit.setFloorCode(dto.getFloorCode());
        unit.setNotes(dto.getNotes());

        UnitEntity updated = unitRepository.save(unit);

        dispatcher.dispatch("UNIT_UPDATED",
                "Unidad actualizada: " + updated.getName(),
                null, ownerId, actor, null);

        return mapToDTO(updated);
    }

    public List<UnitDTO> getUnitsForProperty(String propertyId) {
        String ownerId = resolveOwnerId();
        return unitRepository.findByPropertyIdAndOwnerId(propertyId, ownerId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    private UnitDTO mapToDTO(UnitEntity e) {
        return new UnitDTO(
                e.getId(),
                e.getProperty() != null ? e.getProperty().getId() : null,
                e.getProperty() != null ? e.getProperty().getName() : null,
                e.getName(),
                e.getType(),
                e.getStatus(),
                e.getSquareMeters(),
                e.getBedrooms(),
                e.getBathrooms(),
                e.getFloorCode(),
                e.getNotes()
        );
    }
}
