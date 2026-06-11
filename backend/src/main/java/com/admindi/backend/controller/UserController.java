package com.admindi.backend.controller;

import com.admindi.backend.dto.UserSearchDTO;
import com.admindi.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/users/search")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<UserSearchDTO>> searchUsers(
            @RequestParam String q,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(userService.universalSearch(q, includeInactive));
    }

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<UserSearchDTO>> listByRole(@RequestParam(required = false) String role) {
        return ResponseEntity.ok(userService.listByRole(role));
    }
}
