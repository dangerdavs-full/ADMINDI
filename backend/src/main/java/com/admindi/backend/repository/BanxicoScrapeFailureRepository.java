package com.admindi.backend.repository;

import com.admindi.backend.model.BanxicoScrapeFailureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BanxicoScrapeFailureRepository extends JpaRepository<BanxicoScrapeFailureEntity, String> {

    List<BanxicoScrapeFailureEntity> findTop50ByOrderByDetectedAtDesc();

    long countByResolvedByAiAtIsNull();
}
