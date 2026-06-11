package com.admindi.backend.controller;

import com.admindi.backend.dto.PropertyFileDTO;
import com.admindi.backend.service.PropertyFileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class PropertyFileController {

    private final PropertyFileService fileService;

    @Autowired
    public PropertyFileController(PropertyFileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/api/properties/{propertyId}/files")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','REAL_ESTATE_AGENT') or hasAuthority('PROPERTY_UPDATE')")
    public ResponseEntity<PropertyFileDTO> upload(
            @PathVariable String propertyId,
            @RequestParam String category,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(fileService.uploadFile(propertyId, category, file, label, note));
    }

    @GetMapping("/api/properties/{propertyId}/files")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT','REAL_ESTATE_AGENT') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<List<PropertyFileDTO>> listFiles(@PathVariable String propertyId) {
        return ResponseEntity.ok(fileService.getFiles(propertyId));
    }

    /**
     * @deprecated Endpoint legado con vulnerabilidad IDOR — servía el archivo sin
     * verificar que el {@code fileId} perteneciera al dueño del llamante. Reemplazado
     * por {@code GET /api/secure-files/property-file/{fileId}} que valida
     * {@code property.ownerId} contra el contexto del JWT. Se mantiene retornando 410
     * para no introducir 404 silenciosos en clientes antiguos pero impedir su uso.
     */
    @Deprecated
    @GetMapping("/api/files/{fileId}/download")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT','REAL_ESTATE_AGENT') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<Resource> download(@PathVariable String fileId) {
        return ResponseEntity.status(HttpStatus.GONE)
                .header("X-Migrated-To", "/api/secure-files/property-file/" + fileId)
                .build();
    }

    /**
     * OWNER hard-deletes a property file directly, gated by password + MFA.
     * Staff (even with {@code properties:delete}) must go through the approval flow at
     * {@code POST /api/approval-requests/property-files/{fileId}/delete} because the
     * operation is destructive and irreversible (storage blob + metadata are wiped).
     *
     * <p>The previous version of this endpoint accepted any role with
     * {@code PROPERTY_DELETE} authority and required no reauth — that bypassed the
     * double-reauth policy adopted in Fase 2 and could destroy evidence files under
     * the OTHER category (contracts, deeds). If you need to re-enable self-service
     * deletion for some low-risk category, guard it behind a dedicated endpoint; do
     * not reopen this route.
     */
    @DeleteMapping("/api/files/{fileId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> deleteFile(@PathVariable String fileId,
                                           @RequestBody PropertyController.ReauthRequest reauth) {
        fileService.deleteFileWithReauth(fileId, reauth.getPassword(), reauth.getMfaCode());
        return ResponseEntity.noContent().build();
    }
}
