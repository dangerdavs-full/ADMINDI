package com.admindi.backend.dto;

import com.admindi.backend.model.Role;

public class UserSearchDTO {
    private String id;
    private String username;
    private String name;
    // V54: `email` en este DTO ya es el contactEmail del user (único canal
    // de correo tras la purga). Mantenemos el nombre del campo para compat
    // con frontend.
    private String email;
    private Role role;
    private String ownerId;

    public UserSearchDTO(String id, String username, String name, String email, Role role, String ownerId) {
        this.id = id;
        this.username = username;
        this.name = name;
        this.email = email;
        this.role = role;
        this.ownerId = ownerId;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public String getOwnerId() { return ownerId; }
}
