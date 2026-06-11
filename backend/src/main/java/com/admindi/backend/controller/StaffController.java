package com.admindi.backend.controller;

import com.admindi.backend.dto.StaffDTO;
import com.admindi.backend.service.StaffService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffService service;

    @Autowired
    public StaffController(StaffService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<List<StaffDTO>> getAll() {
        return ResponseEntity.ok(service.getMyStaff());
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<StaffDTO> create(@RequestBody StaffDTO dto) {
        return ResponseEntity.ok(service.createStaff(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<StaffDTO> update(@PathVariable String id, @RequestBody StaffDTO dto) {
        return ResponseEntity.ok(service.updateStaff(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.deleteStaff(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reenvía el link de activación al staff si aún no ha activado la cuenta
     * o si el link previo expiró. Cualquier token anterior del mismo user se
     * revoca — solo el último enviado funciona.
     */
    @PostMapping("/{id}/resend-activation")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, Object>> resendActivation(@PathVariable String id) {
        return ResponseEntity.ok(service.resendActivation(id));
    }
}
