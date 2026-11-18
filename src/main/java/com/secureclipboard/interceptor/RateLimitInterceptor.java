package com.secureclipboard.interceptor;

import com.secureclipboard.exception.RateLimitExceededException;
import com.secureclipboard.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        String ip = request.getRemoteAddr();

        String method = request.getMethod();
        String uri = request.getRequestURI();

        RateLimitService.RateLimitResult result;

        if ("POST".equals(method)
                && "/api/clipboards".equals(uri)) {

            result = rateLimitService.checkCreate(ip);

        } else if ("POST".equals(method)
                && uri.matches(
                "/api/clipboards/[^/]+/verify")) {

            result = rateLimitService.checkOpen(ip);

        } else if ("GET".equals(method)
                && uri.matches(
                "/api/clipboards/[^/]+/handshake")) {

            result = rateLimitService.checkOpen(ip);

        } else {
            return true;
        }

        if (!result.allowed()) {
            throw new RateLimitExceededException(
                    result.retryAfterSeconds()
            );
        }

        return true;
    }
}