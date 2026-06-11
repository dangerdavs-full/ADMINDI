package com.admindi.backend.controller;

import com.admindi.backend.ai.ReceiptOcrService;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.whatsapp.ReceiptOcrPort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint para el modo "Foto + IA" del portal del inquilino.
 *
 * Recibe la imagen del comprobante y devuelve los datos extraídos por Claude
 * Vision para que el usuario confirme antes de enviar al flujo de pagos. No
 * crea el TransferProofSubmission — eso lo hace el endpoint existente
 * {@code POST /api/payments/proofs} una vez que el usuario confirma.
 *
 * Seguridad:
 *  - @PreAuthorize TENANT: solo un inquilino puede pedir OCR de un comprobante.
 *  - Tamaño máximo 5 MB (se deja a Spring multipart config).
 *  - El archivo se valida por MIME en el service (reusa el mismo whitelist
 *    que el adapter de Twilio).
 *  - Rate limit: aplica el interceptor global de admin rate limit.
 */
@RestController
@RequestMapping("/api/payments/proofs")
public class ReceiptOcrController {

    private final ReceiptOcrService ocrService;
    private final UserRepository userRepo;

    public ReceiptOcrController(ReceiptOcrService ocrService, UserRepository userRepo) {
        this.ocrService = ocrService;
        this.userRepo = userRepo;
    }

    @PostMapping("/ocr")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<Map<String, Object>> extractReceipt(
            @RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "empty_file"));
        }

        String contentType = file.getContentType();
        if (contentType == null) contentType = "image/jpeg";

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity user = userRepo.findByUsername(username).orElse(null);
        String userId = user != null ? user.getId() : null;
        String ownerId = user != null ? user.getOwnerId() : null;

        ReceiptOcrPort.ExtractedReceipt extracted =
                ocrService.extract(file.getBytes(), contentType, userId, ownerId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", extracted.ok());
        response.put("claveRastreo", extracted.claveRastreo());
        response.put("amount", extracted.amount());
        response.put("transferDate", extracted.transferDate());
        response.put("bankEmitter", extracted.bankEmitter());
        response.put("accountReceiver", extracted.accountReceiver());
        response.put("beneficiaryName", extracted.beneficiaryName());
        response.put("rfcBeneficiary", extracted.rfcBeneficiary());
        response.put("confidence", extracted.confidence());
        response.put("errorMessage", extracted.errorMessage());
        return ResponseEntity.ok(response);
    }
}
