package com.admindi.backend;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BcryptTest {
    @Test
    public void testHash() {
        System.out.println("HASH_START:" + new BCryptPasswordEncoder().encode("superadmin") + ":HASH_END");
    }
}
