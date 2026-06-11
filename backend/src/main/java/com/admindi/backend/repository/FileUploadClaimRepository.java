package com.admindi.backend.repository;

import com.admindi.backend.model.FileUploadClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FileUploadClaimRepository extends JpaRepository<FileUploadClaimEntity, String> {
    Optional<FileUploadClaimEntity> findByFilePath(String filePath);
}
