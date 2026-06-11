package com.admindi.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ConcurrentHashMap<String, RateLimitData> buckets = new ConcurrentHashMap<>();
    private final AdmindiRateLimitProperties rateLimitProperties;

    public RateLimitInterceptor(AdmindiRateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = request.getRemoteAddr();
        String path = request.getRequestURI();
        String method = request.getMethod();
        long currentTime = System.currentTimeMillis();

        int maxRequests = rateLimitProperties.getDefaultMaxRequests();
        long windowSizeMs = rateLimitProperties.getDefaultWindowMs();

        if (path.contains("/auth/login")) {
            maxRequests = rateLimitProperties.getLoginMaxRequests();
            windowSizeMs = (long) rateLimitProperties.getLoginWindowMinutes() * 60_000L;
        } else if (path.contains("/auth/select-context") || path.contains("/auth/switch-context")
                || path.contains("/auth/refresh")) {
            maxRequests = rateLimitProperties.getAuthContextRefreshMaxRequests();
        } else if (path.contains("/webhooks")) {
            maxRequests = rateLimitProperties.getWebhookMaxRequests();
        } else if (path.contains("/payments") && "POST".equals(method)) {
            maxRequests = rateLimitProperties.getPaymentPostMaxRequests();
        } else if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            maxRequests = rateLimitProperties.getPostMutateMaxRequests();
        } else if ("DELETE".equals(method)) {
            maxRequests = rateLimitProperties.getDeleteMaxRequests();
        }

        String bucketKey = clientIp + ":" + (path.contains("/auth/login") ? "/auth/login" : path);

        final long finalWindow = windowSizeMs;
        RateLimitData data = buckets.compute(bucketKey, (key, current) -> {
            if (current == null || (currentTime - current.timestamp) > finalWindow) {
                return new RateLimitData(new AtomicInteger(1), currentTime);
            }
            current.count.incrementAndGet();
            return current;
        });

        if (data.count.get() > maxRequests) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("Rate limit exceeded. Try again later.");
            return false;
        }

        return true;
    }

    private static class RateLimitData {
        AtomicInteger count;
        long timestamp;

        RateLimitData(AtomicInteger count, long timestamp) {
            this.count = count;
            this.timestamp = timestamp;
        }
    }
}
