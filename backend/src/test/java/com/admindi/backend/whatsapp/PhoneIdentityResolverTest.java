package com.admindi.backend.whatsapp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhoneIdentityResolverTest {

    @Test
    void toE164_stripsWhatsappPrefix() {
        assertEquals("+5215512345678", PhoneIdentityResolver.toE164("whatsapp:+5215512345678"));
    }

    @Test
    void toE164_leavesAlreadyFormatted() {
        assertEquals("+5215512345678", PhoneIdentityResolver.toE164("+5215512345678"));
    }

    @Test
    void toE164_stripsNonDigits() {
        assertEquals("+5215512345678", PhoneIdentityResolver.toE164(" +52 (1) 55 1234-5678 "));
    }

    @Test
    void toE164_mexicanLocalFormat() {
        // sin país: retorna con '+' y los 10 dígitos, caller decide default CC
        assertEquals("+5512345678", PhoneIdentityResolver.toE164("5512345678"));
    }

    @Test
    void toE164_tooShortReturnsEmpty() {
        assertEquals("", PhoneIdentityResolver.toE164("123"));
    }

    @Test
    void toE164_nullReturnsEmpty() {
        assertEquals("", PhoneIdentityResolver.toE164(null));
    }

    @Test
    void toE164_preservesInternationalWithoutPlus() {
        assertEquals("+14155551234", PhoneIdentityResolver.toE164("1 (415) 555-1234"));
    }
}
