package com.admindi.backend.whatsapp;

import com.admindi.backend.model.TenantWhatsappPinEntity;
import com.admindi.backend.repository.TenantWhatsappPinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppPinServiceTest {

    private WhatsAppPinService service;
    private InMemoryRepo repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRepo();
        service = new WhatsAppPinService(repo, 4); // strength bajo para que los tests sean rápidos
        ReflectionTestUtils.setField(service, "minLength", 4);
        ReflectionTestUtils.setField(service, "maxLength", 6);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        ReflectionTestUtils.setField(service, "lockoutMinutes", 30);
    }

    @Test
    void setAndVerifyRoundtrip() {
        service.setPin("user-1", "1234");
        assertEquals(WhatsAppPinService.VerifyResult.OK, service.verify("user-1", "1234"));
    }

    @Test
    void verify_withWrongPinReturnsMismatch() {
        service.setPin("user-1", "1234");
        assertEquals(WhatsAppPinService.VerifyResult.MISMATCH, service.verify("user-1", "9999"));
    }

    @Test
    void verify_notConfiguredReturnsNotConfigured() {
        assertEquals(WhatsAppPinService.VerifyResult.NOT_CONFIGURED,
                service.verify("ghost-user", "1234"));
    }

    @Test
    void setPin_tooShortThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.setPin("u", "12"));
    }

    @Test
    void setPin_nonDigitsThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.setPin("u", "abcd"));
    }

    @Test
    void verify_bruteForceLocksAfterMaxAttempts() {
        service.setPin("user-1", "1234");
        // 3 intentos consecutivos malos → LOCKED en el 3º
        assertEquals(WhatsAppPinService.VerifyResult.MISMATCH, service.verify("user-1", "0000"));
        assertEquals(WhatsAppPinService.VerifyResult.MISMATCH, service.verify("user-1", "0000"));
        assertEquals(WhatsAppPinService.VerifyResult.LOCKED, service.verify("user-1", "0000"));
        // Ya bloqueado: incluso el PIN correcto devuelve LOCKED durante la ventana
        assertEquals(WhatsAppPinService.VerifyResult.LOCKED, service.verify("user-1", "1234"));
    }

    @Test
    void successResetsFailedCounter() {
        service.setPin("user-1", "1234");
        service.verify("user-1", "0000");
        service.verify("user-1", "1234"); // OK
        // Ahora puede fallar 2 veces sin quedar bloqueado
        assertEquals(WhatsAppPinService.VerifyResult.MISMATCH, service.verify("user-1", "9999"));
        assertEquals(WhatsAppPinService.VerifyResult.MISMATCH, service.verify("user-1", "9999"));
        assertEquals(WhatsAppPinService.VerifyResult.LOCKED, service.verify("user-1", "9999"));
    }

    @Test
    void reset_removesPinEntry() {
        service.setPin("user-1", "1234");
        assertTrue(service.hasPinConfigured("user-1"));
        service.reset("user-1");
        assertFalse(service.hasPinConfigured("user-1"));
        assertEquals(WhatsAppPinService.VerifyResult.NOT_CONFIGURED,
                service.verify("user-1", "1234"));
    }

    /** Repo in-memory mínimo para aislar el service de JPA. */
    static class InMemoryRepo implements TenantWhatsappPinRepository {
        private final Map<String, TenantWhatsappPinEntity> store = new HashMap<>();

        @Override public Optional<TenantWhatsappPinEntity> findByUserId(String userId) {
            return Optional.ofNullable(store.get(userId));
        }
        @Override public <S extends TenantWhatsappPinEntity> S save(S entity) {
            entity.setUpdatedAt(LocalDateTime.now());
            store.put(entity.getUserId(), entity);
            return entity;
        }
        @Override public void delete(TenantWhatsappPinEntity entity) {
            if (entity != null && entity.getUserId() != null) store.remove(entity.getUserId());
        }
        @Override public void deleteById(String id) { store.remove(id); }
        @Override public boolean existsById(String id) { return store.containsKey(id); }
        @Override public Optional<TenantWhatsappPinEntity> findById(String id) {
            return findByUserId(id);
        }
        @Override public long count() { return store.size(); }

        // ── Métodos no usados por el service; se dejan con stubs mínimos ──
        @Override public java.util.List<TenantWhatsappPinEntity> findAll() { return new java.util.ArrayList<>(store.values()); }
        @Override public java.util.List<TenantWhatsappPinEntity> findAllById(Iterable<String> ids) { return java.util.List.of(); }
        @Override public <S extends TenantWhatsappPinEntity> java.util.List<S> saveAll(Iterable<S> entities) { return java.util.List.of(); }
        @Override public void deleteAll() { store.clear(); }
        @Override public void deleteAll(Iterable<? extends TenantWhatsappPinEntity> entities) {}
        @Override public void deleteAllById(Iterable<? extends String> ids) {}
        @Override public void flush() {}
        @Override public <S extends TenantWhatsappPinEntity> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends TenantWhatsappPinEntity> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { return java.util.List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<TenantWhatsappPinEntity> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) {}
        @Override public TenantWhatsappPinEntity getOne(String id) { return store.get(id); }
        @Override public TenantWhatsappPinEntity getById(String id) { return store.get(id); }
        @Override public TenantWhatsappPinEntity getReferenceById(String id) { return store.get(id); }
        @Override public <S extends TenantWhatsappPinEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { return java.util.List.of(); }
        @Override public <S extends TenantWhatsappPinEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return java.util.List.of(); }
        @Override public java.util.List<TenantWhatsappPinEntity> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<TenantWhatsappPinEntity> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends TenantWhatsappPinEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends TenantWhatsappPinEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends TenantWhatsappPinEntity> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends TenantWhatsappPinEntity> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends TenantWhatsappPinEntity, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
    }
}
