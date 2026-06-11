package com.admindi.backend.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Storage abstraction for file management.
 * Current implementation: local filesystem (FileStorageService).
 * Future: S3, GCS, Azure Blob, etc.
 */
public interface StorageService {

    /**
     * Store a file under the given category.
     * @param file     the uploaded file
     * @param category organizational folder (e.g., "proofs", "documents", "profiles")
     * @return the relative path to the stored file
     */
    String store(MultipartFile file, String category);

    /**
     * Delete a file by its stored path.
     * @param path the path returned by store()
     */
    void delete(String path);

    /**
     * Check if a file exists at the given path.
     */
    boolean exists(String path);
}
