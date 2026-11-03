package com.rsreach.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lightweight in-process rate limit for auth and webhook routes (MVP; replace with gateway limits in prod).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SimpleRateLimitFilter extends OncePerRequestFilter {

    private final int maxPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public SimpleRateLimitFilter(
            @Value("${rsreach.security.rate-limit-per-minute:120}") int maxPerMinute
    ) {
        this.maxPerMinute = Math.max(maxPerMinute, 10);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !(path.startsWith("/api/v1/webhooks/")
                || path.startsWith("/api/v1/app/auth/")
                || path.startsWith("/api/v1/platform/auth/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String key = request.getRemoteAddr() + "|" + request.getRequestURI();
        long minute = System.currentTimeMillis() / 60_000L;
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.minute != minute) {
                return new Window(minute, new AtomicInteger(0));
            }
            return existing;
        });
        if (window.count.incrementAndGet() > maxPerMinute) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
