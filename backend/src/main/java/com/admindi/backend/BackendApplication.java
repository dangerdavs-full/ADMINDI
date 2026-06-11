package com.admindi.backend;

import org.springframework.boot.SpringApplication;
import com.admindi.backend.config.AdmindiQaSeedProperties;
import com.admindi.backend.config.AdmindiRateLimitProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableAsync} habilita los hooks {@code @Async} que AiAccountingService
 * usa para categorizar pagos y egresos en background (V56 — no bloquea el
 * flujo crítico del inquilino al subir comprobante).
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({ AdmindiRateLimitProperties.class, AdmindiQaSeedProperties.class })
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
