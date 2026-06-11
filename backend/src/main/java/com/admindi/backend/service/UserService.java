package com.admindi.backend.service;

import com.admindi.backend.dto.UserSearchDTO;
import com.admindi.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {
    
    private final UserRepository userRepository;

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_RESULTS = 25;

    @Autowired
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Search active users only (default behavior).
     * Requires minimum 2 characters.
     *
     * V54: la búsqueda se realiza sobre name y phone. El campo email desapareció
     * de users y contact_email está cifrado (ciphertext no-determinístico), por
     * lo que LIKE no es viable ahí. Limited to 25 results.
     */
    public List<UserSearchDTO> universalSearch(String query, boolean includeInactive) {
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        String q = query.trim();
        var pageable = PageRequest.of(0, MAX_RESULTS);

        var results = includeInactive
                ? userRepository.searchAllIncludingInactive(q, pageable)
                : userRepository.searchAllByQuery(q, pageable);

        return results.stream()
                .map(user -> new UserSearchDTO(
                        user.getId(),
                        user.getUsername(),
                        user.getName(),
                        user.getContactEmail(),
                        user.getRole(),
                        user.getOwnerId()
                ))
                .collect(Collectors.toList());
    }

    public List<UserSearchDTO> listByRole(String role) {
        return userRepository.findAll().stream()
                .filter(u -> u.isActive())
                .filter(u -> role == null || role.isBlank() || u.getRole().name().equalsIgnoreCase(role))
                .map(user -> new UserSearchDTO(
                        user.getId(),
                        user.getUsername(),
                        user.getName(),
                        user.getContactEmail(),
                        user.getRole(),
                        user.getOwnerId()
                ))
                .collect(Collectors.toList());
    }
}
