package com.secureclipboard;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test configuration that provides a mocked RedisTemplate
 * backed by ConcurrentHashMaps, so tests run without a real Redis server.
 */
@TestConfiguration
public class TestRedisConfig {

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public RedisTemplate<String, byte[]> redisTemplate() {
        Map<String, byte[]> valueStore = new ConcurrentHashMap<>();
        Map<String, Map<Object, Object>> hashStore = new ConcurrentHashMap<>();

        RedisTemplate<String, byte[]> template = mock(RedisTemplate.class);

        // --- ValueOperations mock ---
        ValueOperations<String, byte[]> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);

        doAnswer(inv -> {
            valueStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), any(byte[].class));

        doAnswer(inv -> {
            valueStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), any(byte[].class), any(Duration.class));

        doAnswer(inv -> {
            valueStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), any(byte[].class), anyLong(), any(TimeUnit.class));

        when(valueOps.get(any())).thenAnswer(inv -> valueStore.get(inv.getArgument(0).toString()));

        // --- HashOperations mock ---
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(template.opsForHash()).thenReturn(hashOps);

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object hk = inv.getArgument(1);
            Object hv = inv.getArgument(2);
            hashStore.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(hk, hv);
            return null;
        }).when(hashOps).put(anyString(), any(), any());

        when(hashOps.get(anyString(), any())).thenAnswer(inv -> {
            Map<Object, Object> hash = hashStore.get(inv.getArgument(0).toString());
            return hash != null ? hash.get(inv.getArgument(1)) : null;
        });

        when(hashOps.hasKey(anyString(), any())).thenAnswer(inv -> {
            Map<Object, Object> hash = hashStore.get(inv.getArgument(0).toString());
            return hash != null && hash.containsKey(inv.getArgument(1));
        });

        when(hashOps.keys(anyString())).thenAnswer(inv -> {
            Map<Object, Object> hash = hashStore.get(inv.getArgument(0).toString());
            return hash != null ? hash.keySet() : Set.of();
        });

        when(hashOps.size(anyString())).thenAnswer(inv -> {
            Map<Object, Object> hash = hashStore.get(inv.getArgument(0).toString());
            return hash != null ? (long) hash.size() : 0L;
        });

        when(hashOps.entries(anyString())).thenAnswer(inv ->
            hashStore.getOrDefault(inv.getArgument(0).toString(), Map.of()));

        when(hashOps.delete(anyString(), any())).thenAnswer(inv -> {
            Map<Object, Object> hash = hashStore.get(inv.getArgument(0).toString());
            if (hash == null) return 0L;
            long count = 0;
            Object[] hashKeys = inv.getArguments();
            for (int i = 1; i < hashKeys.length; i++) {
                if (hash.remove(hashKeys[i]) != null) count++;
            }
            if (hash.isEmpty()) hashStore.remove(inv.getArgument(0).toString());
            return count;
        });

        // --- Template-level operations ---
        when(template.hasKey(anyString())).thenAnswer(inv ->
            valueStore.containsKey(inv.getArgument(0).toString()));

        when(template.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            boolean existed = valueStore.containsKey(key) || hashStore.containsKey(key);
            valueStore.remove(key);
            hashStore.remove(key);
            return existed;
        });

        when(template.delete(anyCollection())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            long count = 0;
            for (String key : keys) {
                if (valueStore.remove(key) != null) count++;
                if (hashStore.remove(key) != null) count++;
            }
            return count;
        });

        when(template.keys(anyString())).thenAnswer(inv -> {
            String pattern = inv.getArgument(0).toString();
            String prefix = pattern.replace("*", "");
            Set<String> result = new HashSet<>();
            for (String key : valueStore.keySet()) {
                if (key.startsWith(prefix)) result.add(key);
            }
            for (String key : hashStore.keySet()) {
                if (key.startsWith(prefix)) result.add(key);
            }
            return result;
        });

        when(template.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(template.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        return template;
    }
}
