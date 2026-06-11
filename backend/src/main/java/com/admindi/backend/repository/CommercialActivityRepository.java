package com.admindi.backend.repository;

import com.admindi.backend.model.CommercialActivityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommercialActivityRepository extends JpaRepository<CommercialActivityEntity, String> {
    List<CommercialActivityEntity> findByVacancyId(String vacancyId);
}
