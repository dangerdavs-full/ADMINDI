package com.admindi.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import com.admindi.backend.model.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.io.*;
import java.security.*;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import jakarta.annotation.PostConstruct;

@Service
public class JwtService {

    private static final long ACCESS_TOKEN_TTL_MS = 15 * 60 * 1000; // 15 minutes
    private static final long REFRESH_TOKEN_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private KeyPair keyPair;

    @PostConstruct
    public void init() {
        File keyFile = new File(".jwt-key.bin");
        if (keyFile.exists()) {
            try (ObjectInputStream ios = new ObjectInputStream(new FileInputStream(keyFile))) {
                this.keyPair = (KeyPair) ios.readObject();
                this.privateKey = keyPair.getPrivate();
                this.publicKey = keyPair.getPublic();
                return;
            } catch (Exception e) {
                // Ignore and regenerate if corrupted
            }
        }
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            this.keyPair = keyPairGenerator.generateKeyPair();
            this.privateKey = keyPair.getPrivate();
            this.publicKey = keyPair.getPublic();
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(keyFile))) {
                oos.writeObject(keyPair);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error initializing RSA keys", e);
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractType(String token) {
        return extractClaim(token, claims -> claims.get("type", String.class));
    }

    public String extractOwnerId(String token) {
        return extractClaim(token, claims -> claims.get("ownerId", String.class));
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateBaseToken(UserDetails userDetails, UserEntity entity) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "BASE");
        claims.put("uid", entity.getId());
        return generateToken(claims, userDetails, UUID.randomUUID().toString(), ACCESS_TOKEN_TTL_MS);
    }

    public String generateFullToken(UserDetails userDetails, String ownerId, UserEntity entity) {
        return generateFullToken(userDetails, ownerId, entity, java.util.List.of());
    }

    public String generateFullToken(UserDetails userDetails, String ownerId, UserEntity entity, java.util.List<String> resolvedPermissions) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "FULL");
        claims.put("uid", entity.getId());
        claims.put("ownerId", ownerId);
        claims.put("role", entity.getRole().name());
        claims.put("permissions", !resolvedPermissions.isEmpty() ? resolvedPermissions : (entity.getPermissions() != null ? entity.getPermissions() : java.util.List.of()));
        claims.put("providerType", entity.getProviderType() != null ? entity.getProviderType() : "LOCAL");
        return generateToken(claims, userDetails, UUID.randomUUID().toString(), ACCESS_TOKEN_TTL_MS);
    }

    /**
     * Long-lived (24h) refresh token with minimal claims.
     * Cannot be used as Bearer for API access — only for /auth/refresh.
     */
    public String generateRefreshToken(UserEntity entity) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "REFRESH");
        claims.put("uid", entity.getId());
        return generateToken(claims, entity, UUID.randomUUID().toString(), REFRESH_TOKEN_TTL_MS);
    }

    /**
     * Short-lived (5 min) token that proves the user passed the first authentication factor.
     * Only valid for /auth/mfa/verify endpoint. Cannot be used for any other API call.
     */
    public String generateMfaChallengeToken(UserEntity entity) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "MFA_CHALLENGE");
        claims.put("uid", entity.getId());
        // Subject = username (V48). Antes se usaba email; el frontend y verifyMfa
        // deben mandar el mismo identificador que usó el cliente para logear.
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(entity.getUsername())
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + 300_000)) // 5 minutes
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    private String generateToken(Map<String, Object> extraClaims, UserDetails userDetails, String jti, long ttlMs) {
        return Jwts.builder()
                .setClaims(extraClaims)
                .setSubject(userDetails.getUsername())
                .setId(jti)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
