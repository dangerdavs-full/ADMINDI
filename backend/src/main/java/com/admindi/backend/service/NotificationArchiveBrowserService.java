package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Navegador del archivo de notificaciones para SUPER_ADMIN (Bloque C8).
 *
 * <h2>Operaciones expuestas</h2>
 * <ul>
 *   <li>{@link #search(String)} — buscador fuzzy por fragmento del nombre de carpeta.
 *       Coincide con nombre de inquilino, dirección, CP, número — tal como aparecen en
 *       el slug del path.</li>
 *   <li>{@link #listFiles(String)} — trimestres archivados de una "carpeta".</li>
 *   <li>{@link #readCsv(String, String)} — bytes del CSV para descarga.</li>
 *   <li>{@link #zipFolder(String)} — carpeta completa comprimida en ZIP.</li>
 *   <li>{@link #deleteFile(String, String, String, String)} — borrar un CSV con reauth.</li>
 *   <li>{@link #deleteFolder(String, String, String)} — borrar carpeta completa con reauth.</li>
 * </ul>
 *
 * <h2>Seguridad</h2>
 * <ul>
 *   <li>Todas las rutas se normalizan contra {@code archiveRoot} — imposible path-traversal.</li>
 *   <li>Las operaciones de borrado exigen reauth (password+MFA) por {@link ReauthService}.</li>
 *   <li>Cada operación (descarga, listado, borrado) emite un {@code audit_events}
 *       permanente con el identificador completo del objeto tocado — así el propio
 *       archivo queda auditable aunque después se borre.</li>
 * </ul>
 */
@Service
public class NotificationArchiveBrowserService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationArchiveBrowserService.class);
    private static final int MAX_SEARCH_RESULTS = 50;

    private final ReauthService reauthService;
    private final AuditEventRepository auditRepository;
    private final NotificationArchiverService archiverService;

    @Value("${admindi.notification-archive.path:./archive/notifications}")
    private String archivePath;

    public NotificationArchiveBrowserService(ReauthService reauthService,
                                             AuditEventRepository auditRepository,
                                             NotificationArchiverService archiverService) {
        this.reauthService = reauthService;
        this.auditRepository = auditRepository;
        this.archiverService = archiverService;
    }

    /**
     * Dispara el archivador a demanda (catch-up manual).
     * Útil cuando el cron nocturno fue saltado (servidor caído, backfill, etc.).
     * Solo SUPER_ADMIN. Audita la operación.
     *
     * @param cutoffDaysOverride si &gt; 0, archiva eventos con más de ese número de días
     *                           en lugar del threshold configurado. Útil para smokes
     *                           (usar valor pequeño) y para backfills históricos agresivos.
     * @return número de eventos archivados en esta corrida.
     */
    public int triggerArchiveNow(Integer cutoffDaysOverride) {
        requireSuperAdmin();
        int days = (cutoffDaysOverride != null && cutoffDaysOverride >= 0)
                ? cutoffDaysOverride
                : archiverService.getConfiguredThresholdDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int archived = archiverService.archiveOlderThan(cutoff);
        auditSuperAdminOp("ARCHIVE_RUN_NOW",
                "cutoffDays=" + days + " archived=" + archived);
        return archived;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DTOs de respuesta
    // ════════════════════════════════════════════════════════════════════════

    public static final class FolderSummary {
        public String folder;          // slug raw (ej "david_cuernavaca_103_06140")
        public String tenantLabel;     // primer componente legible ("david")
        public String propertyLabel;   // resto ("cuernavaca 103 06140")
        public int fileCount;
        public long totalSizeBytes;

        public FolderSummary() {}
        public FolderSummary(String folder, String tenantLabel, String propertyLabel,
                             int fileCount, long totalSizeBytes) {
            this.folder = folder;
            this.tenantLabel = tenantLabel;
            this.propertyLabel = propertyLabel;
            this.fileCount = fileCount;
            this.totalSizeBytes = totalSizeBytes;
        }
    }

    public static final class FileSummary {
        public String fileName;         // "2026Q1.csv"
        public String period;           // "2026Q1"
        public long sizeBytes;
        public String lastModified;     // ISO date

        public FileSummary() {}
        public FileSummary(String fileName, String period, long sizeBytes, String lastModified) {
            this.fileName = fileName;
            this.period = period;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Operaciones
    // ════════════════════════════════════════════════════════════════════════

    public List<FolderSummary> search(String query) {
        Path root = archiveRoot();
        if (!Files.isDirectory(root)) return Collections.emptyList();
        String q = query == null ? "" : query.trim().toLowerCase();
        List<FolderSummary> results = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path folderPath : stream) {
                if (!Files.isDirectory(folderPath)) continue;
                String folderName = folderPath.getFileName().toString();
                if (!q.isEmpty() && !folderName.toLowerCase().contains(q)) continue;

                int count = 0;
                long size = 0L;
                try (DirectoryStream<Path> fs = Files.newDirectoryStream(folderPath, "*.csv")) {
                    for (Path f : fs) {
                        if (Files.isRegularFile(f)) {
                            count++;
                            size += Files.size(f);
                        }
                    }
                }
                String[] labels = splitFolderName(folderName);
                results.add(new FolderSummary(folderName, labels[0], labels[1], count, size));
                if (results.size() >= MAX_SEARCH_RESULTS) break;
            }
        } catch (IOException e) {
            logger.error("[ARCHIVE-BROWSER] error listando archive root {}: {}", root, e.getMessage());
        }
        auditSuperAdminOp("ARCHIVE_SEARCH", "query=" + q + " results=" + results.size());
        return results;
    }

    public List<FileSummary> listFiles(String folder) {
        Path dir = resolveSafe(folder, null);
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Carpeta no encontrada.");
        }
        List<FileSummary> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path f : stream) {
                if (!Files.isRegularFile(f)) continue;
                BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);
                String name = f.getFileName().toString();
                String period = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
                files.add(new FileSummary(name, period, attrs.size(),
                        attrs.lastModifiedTime().toInstant().toString()));
            }
        } catch (IOException e) {
            logger.error("[ARCHIVE-BROWSER] error listando folder {}: {}", folder, e.getMessage());
        }
        files.sort(Comparator.comparing((FileSummary f) -> f.period).reversed());
        return files;
    }

    public byte[] readCsv(String folder, String fileName) {
        Path file = resolveSafe(folder, fileName);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado.");
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            auditSuperAdminOp("ARCHIVE_DOWNLOAD",
                    "folder=" + folder + " file=" + fileName + " bytes=" + bytes.length);
            return bytes;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo leer el archivo: " + e.getMessage());
        }
    }

    public byte[] zipFolder(String folder) {
        Path dir = resolveSafe(folder, null);
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Carpeta no encontrada.");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos);
             DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path f : stream) {
                if (!Files.isRegularFile(f)) continue;
                ZipEntry entry = new ZipEntry(f.getFileName().toString());
                zos.putNextEntry(entry);
                Files.copy(f, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error comprimiendo carpeta: " + e.getMessage());
        }
        byte[] bytes = baos.toByteArray();
        auditSuperAdminOp("ARCHIVE_DOWNLOAD_ZIP",
                "folder=" + folder + " bytes=" + bytes.length);
        return bytes;
    }

    public void deleteFile(String folder, String fileName, String password, String mfaCode) {
        requireSuperAdmin();
        reauthOrThrow(password, mfaCode, "ARCHIVE_DELETE_FILE");
        Path file = resolveSafe(folder, fileName);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado.");
        }
        try {
            Files.delete(file);
            auditSuperAdminOp("ARCHIVE_DELETE_FILE", "folder=" + folder + " file=" + fileName);
            // Si la carpeta quedó vacía, limpiamos para no dejar directorios huérfanos.
            Path dir = file.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    if (!ds.iterator().hasNext()) Files.delete(dir);
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo borrar: " + e.getMessage());
        }
    }

    public void deleteFolder(String folder, String password, String mfaCode) {
        requireSuperAdmin();
        reauthOrThrow(password, mfaCode, "ARCHIVE_DELETE_FOLDER");
        Path dir = resolveSafe(folder, null);
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Carpeta no encontrada.");
        }
        try {
            deleteRecursive(dir);
            auditSuperAdminOp("ARCHIVE_DELETE_FOLDER", "folder=" + folder);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo borrar la carpeta: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private Path archiveRoot() {
        Path p = Paths.get(archivePath);
        return (p.isAbsolute() ? p : p.toAbsolutePath()).normalize();
    }

    /**
     * Normaliza y valida que la ruta resultante quede dentro del archiveRoot.
     * {@code fileName=null} devuelve la carpeta; si no, el archivo dentro.
     */
    private Path resolveSafe(String folder, String fileName) {
        if (folder == null || folder.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "folder requerido.");
        }
        // Rechazamos cualquier nombre que intente escaparse — no son path-components válidos.
        if (folder.contains("..") || folder.contains("/") || folder.contains("\\")
                || folder.startsWith(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "folder inválido.");
        }
        if (fileName != null) {
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")
                    || !fileName.endsWith(".csv")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileName inválido.");
            }
        }
        Path root = archiveRoot();
        Path result = fileName == null ? root.resolve(folder) : root.resolve(folder).resolve(fileName);
        Path norm = result.toAbsolutePath().normalize();
        if (!norm.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ruta fuera del área permitida.");
        }
        return norm;
    }

    private static String[] splitFolderName(String folderName) {
        // Convención (definida en NotificationArchiverService): tenant y property
        // se concatenan con "__" como separador. Fallback a primer '_' si el folder
        // no usa la convención (por ejemplo, CSVs archivados por versiones previas).
        int at = folderName.indexOf("__");
        if (at < 0) {
            at = folderName.indexOf('_');
            if (at < 0) return new String[] { folderName, "" };
            return new String[] { folderName.substring(0, at),
                                  folderName.substring(at + 1).replace('_', ' ') };
        }
        String tenant = folderName.substring(0, at).replace('_', ' ');
        String property = folderName.substring(at + 2).replace('_', ' ');
        return new String[] { tenant, property };
    }

    private static void deleteRecursive(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) deleteRecursive(entry);
                else Files.delete(entry);
            }
        }
        Files.delete(dir);
    }

    private void requireSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión inválida.");
        }
        boolean ok = auth.getAuthorities().stream()
                .anyMatch(ga -> "ROLE_SUPER_ADMIN".equals(ga.getAuthority()));
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo SUPER_ADMIN.");
        }
    }

    private void reauthOrThrow(String password, String mfaCode, String op) {
        try {
            reauthService.verifyReauth(password, mfaCode, op);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private void auditSuperAdminOp(String op, String details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorEmail = (auth != null) ? auth.getName() : "UNKNOWN";
        try {
            AuditEventEntity a = new AuditEventEntity();
            a.setId(UUID.randomUUID().toString());
            a.setTimestamp(LocalDateTime.now());
            a.setActorId(actorEmail);
            a.setActorRole("SUPER_ADMIN");
            a.setEventType(op);
            a.setResourceType("NOTIFICATION_ARCHIVE");
            a.setResourceId("browser");
            String safe = details == null ? "" : details.replace("\"", "'");
            a.setNewValues("{\"detail\":\"" + safe + "\"}");
            auditRepository.save(a);
        } catch (Exception e) {
            logger.warn("[ARCHIVE-BROWSER] audit save failed for {}: {}", op, e.getMessage());
        }
    }
}
