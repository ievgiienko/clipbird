package com.secureclipboard.service;

import com.secureclipboard.config.AppProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;

    private static final DefaultRedisScript<List> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();

        RATE_LIMIT_SCRIPT.setScriptText("""
                local count = redis.call('INCR', KEYS[1])

                if count == 1 then
                    redis.call('PEXPIRE', KEYS[1], 60000)
                end

                local ttl = redis.call('PTTL', KEYS[1])

                return {count, ttl}
                """);

        RATE_LIMIT_SCRIPT.setResultType(List.class);
    }

    public RateLimitService(
            StringRedisTemplate redisTemplate,
            AppProperties appProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.appProperties = appProperties;
    }

    public RateLimitResult checkCreate(String ip) {
        return check(
                ip,
                "creates",
                appProperties.getRateLimit().getMaxCreatesPerMinute()
        );
    }

    public RateLimitResult checkOpen(String ip) {
        return check(
                ip,
                "opens",
                appProperties.getRateLimit().getMaxOpensPerMinute()
        );
    }

    private RateLimitResult check(
            String ip,
            String operation,
            int limit
    ) {
        if (limit < 1) {
            throw new IllegalStateException(
                    "Rate limit must be greater than zero"
            );
        }

        String key = "rate-limit:" + operation + ":" + ip;

        List<?> result = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of(key)
        );

        if (result == null || result.size() < 2) {
            throw new IllegalStateException(
                    "Redis returned an invalid rate limit response"
            );
        }

        long count = ((Number) result.get(0)).longValue();
        long ttlMillis = ((Number) result.get(1)).longValue();

        long retryAfterSeconds = Math.max(
                1,
                (ttlMillis + 999) / 1000
        );

        return new RateLimitResult(
                count <= limit,
                retryAfterSeconds
        );
    }

    public record RateLimitResult(
            boolean allowed,
            long retryAfterSeconds
    ) {
    }
}