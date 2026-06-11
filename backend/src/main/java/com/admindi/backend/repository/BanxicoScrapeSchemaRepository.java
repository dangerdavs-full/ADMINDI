package com.admindi.backend.repository;

import com.admindi.backend.model.BanxicoScrapeSchemaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BanxicoScrapeSchemaRepository extends JpaRepository<BanxicoScrapeSchemaEntity, String> {

    Optional<BanxicoScrapeSchemaEntity> findFirstByActiveTrue();

    /**
     * Ordenado por versión DESC — el más reciente primero. Útil para el panel
     * superadmin y para evitar colisiones al generar una nueva versión.
     */
    List<BanxicoScrapeSchemaEntity> findAllByOrderByVersionDesc();

    Optional<BanxicoScrapeSchemaEntity> findTopByOrderByVersionDesc();
}
