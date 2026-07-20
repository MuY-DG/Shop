package org.muybaby.shopserver.auth.login;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "shop.auth.admin-login-protection",
        name = "store",
        havingValue = "redis",
        matchIfMissing = true
)
public class RedisAdminLoginAttemptStore implements AdminLoginAttemptStore {

    private static final DefaultRedisScript<Long> CHECK_LOCK_SCRIPT = new DefaultRedisScript<>("""
            local locked = redis.call('HGET', KEYS[1], 'locked')
            if locked ~= '1' then
                return 0
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl <= 0 then
                redis.call('DEL', KEYS[1])
                return 0
            end
            return ttl
            """, Long.class);

    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>("""
            local limit = tonumber(ARGV[1])
            local windowMillis = tonumber(ARGV[2])
            local lockMillis = tonumber(ARGV[3])

            local ttl = redis.call('PTTL', KEYS[1])
            local locked = redis.call('HGET', KEYS[1], 'locked')
            if locked == '1' and ttl > 0 then
                return ttl
            end
            if ttl <= 0 then
                redis.call('DEL', KEYS[1])
            end

            local failures = redis.call('HINCRBY', KEYS[1], 'failures', 1)
            if failures >= limit then
                redis.call('HSET', KEYS[1], 'locked', '1')
                redis.call('PEXPIRE', KEYS[1], lockMillis)
                return lockMillis
            end
            if failures == 1 or redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], windowMillis)
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisAdminLoginAttemptStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Duration lockedFor(String key) {
        Long millis = redisTemplate.execute(CHECK_LOCK_SCRIPT, List.of(key));
        return durationDecision(millis, "check lock");
    }

    @Override
    public Duration recordFailure(
            String key,
            int failureLimit,
            Duration failureWindow,
            Duration lockDuration
    ) {
        Long millis = redisTemplate.execute(
                RECORD_FAILURE_SCRIPT,
                List.of(key),
                Integer.toString(failureLimit),
                Long.toString(atLeastOneMillis(failureWindow)),
                Long.toString(atLeastOneMillis(lockDuration)));
        return durationDecision(millis, "record failure");
    }

    @Override
    public void clear(String key) {
        Boolean deleted = redisTemplate.delete(key);
        if (deleted == null) {
            throw new IllegalStateException("Redis returned no admin login clear decision");
        }
    }

    private Duration durationDecision(Long millis, String operation) {
        if (millis == null) {
            throw new IllegalStateException("Redis returned no admin login " + operation + " decision");
        }
        return millis <= 0 ? Duration.ZERO : Duration.ofMillis(millis);
    }

    private long atLeastOneMillis(Duration duration) {
        return Math.max(1L, duration.toMillis());
    }
}
