package com.admindi.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Local filesystem implementation of StorageService.
 * Files are organized as: {basePath}/{category}/{uuid}.{ext}
 * Configurable via storage.local.base-path (default: uploads).
 */
@Service
public class FileStorageService implements StorageService {

    private final String basePath;

    public FileStorageService(@Value("${storage.local.base-path:uploads}") String basePath) {
        this.basePath = basePath;
        File dir = new File(basePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    @Override
    public String store(MultipartFile file, String category) {
        if (file.isEmpty()) throw new RuntimeException("Archivo vacío.");
        try {
            // Ensure category directory exists
            Path categoryDir = Paths.get(basePath, category);
            Files.createDirectories(categoryDir);

            String originalFileName = file.getOriginalFilename();
            String fileExtension = originalFileName != null && originalFileName.contains(".")
                    ? originalFileName.substring(originalFileName.lastIndexOf(".")) : "";

            String newFileName = UUID.randomUUID().toString() + fileExtension;
            Path filePath = categoryDir.resolve(newFileName);
            Files.write(filePath, file.getBytes());

            return "/" + basePath + "/" + category + "/" + newFileName;
        } catch (IOException e) {
            throw new RuntimeException("Error guardando el archivo", e);
        }
    }

    /**
     * Legacy method for backward compatibility (stores in root uploads/).
     */
    public String storeFile(MultipartFile file) {
        return store(file, "general");
    }

    /**
     * V55: variante para almacenar bytes crudos (sin {@link MultipartFile}).
     *
     * Usado por el chatbot de WhatsApp, que recibe medios directamente por HTTP
     * desde Twilio y no tiene un MultipartFile nativo. Se respeta el mismo
     * layout {@code {basePath}/{category}/{uuid}.{ext}} que {@link #store}.
     *
     * @param bytes       contenido del archivo (ya validado en tamaño/MIME por el caller)
     * @param originalName nombre original (solo para deducir extensión; opcional)
     * @param contentType MIME (opcional; se usa para deducir extensión si originalName no aporta)
     * @param category    subdirectorio lógico (ej. "bot-ticket", "bot-proof")
     */
    public String storeBytes(byte[] bytes, String originalName, String contentType, String category) {
        if (bytes == null || bytes.length == 0) throw new RuntimeException("Archivo vacío.");
        try {
            Path categoryDir = Paths.get(basePath, category == null ? "general" : category);
            Files.createDirectories(categoryDir);

            String ext = "";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf("."));
            } else if (contentType != null) {
                ext = switch (contentType.toLowerCase()) {
                    case "image/jpeg", "image/jpg" -> ".jpg";
                    case "image/png" -> ".png";
                    case "image/webp" -> ".webp";
                    case "image/gif" -> ".gif";
                    case "application/pdf" -> ".pdf";
                    default -> "";
                };
            }

            String newFileName = UUID.randomUUID() + ext;
            Path filePath = categoryDir.resolve(newFileName);
            Files.write(filePath, bytes);

            return "/" + basePath + "/" + (category == null ? "general" : category) + "/" + newFileName;
        } catch (IOException e) {
            throw new RuntimeException("Error guardando el archivo (bytes)", e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            // Strip leading slash if present
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;
            Path filePath = Paths.get(cleanPath);
            if (Files.exists(filePath)) {
                Files.delete(filePath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error eliminando archivo: " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        return Files.exists(Paths.get(cleanPath));
    }
}
