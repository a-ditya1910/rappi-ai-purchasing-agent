package com.rappi.buyer.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * One agent run per sku and store at a time. Two runs racing on the same sku
 * would each see the other's order as not there yet and both buy.
 *
 * SET NX EX: the TTL is the whole reason this is in redis and not mysql. A run
 * that crashes leaves its lock behind, and the TTL clears it - no sweep job to
 * write, and no window where a dead run wedges a sku forever.
 */
@Service
public class RunLock {

    // delete only if it is still our lock. a run that ran past its TTL must not
    // release the lock a newer run has since taken - get-then-del in two calls
    // has exactly that race, so it runs as one script on the redis side
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RunLock(StringRedisTemplate redis, @Value("${app.run-lock-ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    static String key(String sku, String nodeId) {
        return "agent:lock:" + sku + ":" + nodeId;
    }

    /** null if we got it, otherwise the id of the run that holds it. */
    public String acquire(String sku, String nodeId, String runId) {
        String key = key(sku, nodeId);
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, runId, ttl))) {
            return null;
        }
        String holder = redis.opsForValue().get(key);
        // it expired between the two calls - just try again once
        if (holder == null && Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, runId, ttl))) {
            return null;
        }
        return holder == null ? "unknown" : holder;
    }

    /** true if it was ours and is now released. */
    public boolean release(String sku, String nodeId, String runId) {
        if (sku == null || nodeId == null) {
            return false;
        }
        Long n = redis.execute(RELEASE, List.of(key(sku, nodeId)), runId);
        return n != null && n > 0;
    }
}
