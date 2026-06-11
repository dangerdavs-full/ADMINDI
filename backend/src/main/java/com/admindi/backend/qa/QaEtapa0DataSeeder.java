package com.admindi.backend.qa;

import com.admindi.backend.config.AdmindiQaSeedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Arranque: semilla QA Etapa 0 si {@code admindi.qa-seed.enabled=true}.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 50)
public class QaEtapa0DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QaEtapa0DataSeeder.class);

    private final AdmindiQaSeedProperties qaSeedProperties;
    private final QaEtapa0SeedService seedService;

    public QaEtapa0DataSeeder(AdmindiQaSeedProperties qaSeedProperties, QaEtapa0SeedService seedService) {
        this.qaSeedProperties = qaSeedProperties;
        this.seedService = seedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!qaSeedProperties.isEnabled()) {
            log.info("[QA_SEED] Deshabilitado (admindi.qa-seed.enabled=false); no se ejecuta semilla en este arranque.");
            return;
        }
        seedService.seedAll();
    }
}
