package org.muybaby.shopserver.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisTokenStore implements TokenStore {

    private static final String SESSION_PREFIX = "shop:auth:session:";
    private static final String REVOKED_PREFIX = "shop:auth:revoked:";
    private static final String REVOKED_GENERATION_PREFIX = "shop:auth:generation-revoked:";

    private static final DefaultRedisScript<Long> SAVE_FAMILY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end

            local indexTypeResult = redis.call('TYPE', KEYS[1])
            local indexType = indexTypeResult['ok']
            if indexType ~= 'none' and indexType ~= 'set' then
                return redis.error_reply('session index has wrong type')
            end

            local tokenCount = #KEYS - 2
            for i = 1, tokenCount do
                local valueIndex = ((i - 1) * 2) + 1
                redis.call('SET', KEYS[i + 2], ARGV[valueIndex], 'PX', ARGV[valueIndex + 1])
                redis.call('SADD', KEYS[1], KEYS[i + 2])
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[#ARGV])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<String> CONSUME_REFRESH_SCRIPT = new DefaultRedisScript<>("""
            local payload = redis.call('GET', KEYS[1])
            if not payload then
                return nil
            end

            local session = cjson.decode(payload)
            local sessionId = session['sessionId']
            if type(sessionId) ~= 'string'
                    or sessionId == ''
                    or string.match(sessionId, '^%s*$') then
                redis.call('DEL', KEYS[1])
                return nil
            end

            local function isCanonicalGenerationId(value)
                if type(value) ~= 'string' or string.len(value) ~= 36 then
                    return false
                end
                if string.sub(value, 9, 9) ~= '-'
                        or string.sub(value, 14, 14) ~= '-'
                        or string.sub(value, 19, 19) ~= '-'
                        or string.sub(value, 24, 24) ~= '-' then
                    return false
                end
                local compact, hyphenCount = string.gsub(value, '%-', '')
                return hyphenCount == 4
                        and string.len(compact) == 32
                        and string.match(compact, '^[0-9a-fA-F]+$') ~= nil
            end

            local generationId = session['generationId']
            if not isCanonicalGenerationId(generationId) then
                generationId = sessionId
            end

            local familyRevokedKey = ARGV[1] .. sessionId
            local generationRevokedKey = ARGV[2] .. generationId
            local indexKey = ARGV[4] .. sessionId
            local indexTypeResult = redis.call('TYPE', indexKey)
            local indexType = indexTypeResult['ok']
            local family = {}
            if indexType == 'set' then
                family = redis.call('SMEMBERS', indexKey)
            end

            if redis.call('EXISTS', familyRevokedKey) == 1
                    or redis.call('EXISTS', generationRevokedKey) == 1 then
                redis.call('DEL', KEYS[1])
                return nil
            end

            redis.call('SET', generationRevokedKey, '1', 'PX', ARGV[3])
            for _, tokenKey in ipairs(family) do
                redis.call('DEL', tokenKey)
            end
            redis.call('DEL', indexKey)
            redis.call('DEL', KEYS[1])
            return payload
            """, String.class);

    private static final DefaultRedisScript<Long> REVOKE_SESSION_SCRIPT = new DefaultRedisScript<>("""
            local indexTypeResult = redis.call('TYPE', KEYS[2])
            local indexType = indexTypeResult['ok']
            local family = {}
            if indexType == 'set' then
                family = redis.call('SMEMBERS', KEYS[2])
            end

            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            for _, tokenKey in ipairs(family) do
                redis.call('DEL', tokenKey)
            end
            redis.call('DEL', KEYS[2])
            return #family
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisTokenStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean saveFamily(String sessionId, List<TokenGrant> grants) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID is required");
        }
        List<TokenGrant> family = List.copyOf(grants);
        if (family.isEmpty()) {
            throw new IllegalArgumentException("At least one token grant is required");
        }

        try {
            List<String> serializedSessions = new ArrayList<>(family.size());
            long indexTtlMillis = 1;
            for (TokenGrant grant : family) {
                if (!sessionId.equals(grant.session().sessionId())) {
                    throw new IllegalArgumentException("Token grant session does not match family session");
                }
                serializedSessions.add(objectMapper.writeValueAsString(grant.session()));
                indexTtlMillis = Math.max(indexTtlMillis, positiveMillis(grant.ttl()));
            }

            List<String> keys = new ArrayList<>(family.size() + 2);
            keys.add(sessionKey(sessionId));
            keys.add(revokedKey(sessionId));
            family.stream().map(TokenGrant::key).forEach(keys::add);

            List<Object> arguments = new ArrayList<>((family.size() * 2) + 1);
            for (int index = 0; index < family.size(); index++) {
                arguments.add(serializedSessions.get(index));
                arguments.add(Long.toString(positiveMillis(family.get(index).ttl())));
            }
            arguments.add(Long.toString(indexTtlMillis));
            Long saved = redisTemplate.execute(SAVE_FAMILY_SCRIPT, keys, arguments.toArray());
            return Long.valueOf(1L).equals(saved);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to save token family", ex);
        }
    }

    @Override
    public Optional<TokenSession> find(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, TokenSession.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read token session", ex);
        }
    }

    @Override
    public Optional<TokenSession> consumeRefreshAndRevokeFamily(String refreshKey, Duration revokedTtl) {
        try {
            String value = redisTemplate.execute(
                    CONSUME_REFRESH_SCRIPT,
                    List.of(refreshKey),
                    REVOKED_PREFIX,
                    REVOKED_GENERATION_PREFIX,
                    Long.toString(positiveMillis(revokedTtl)),
                    SESSION_PREFIX
            );
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, TokenSession.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to consume refresh token", ex);
        }
    }

    @Override
    public void revokeSession(String sessionId, Duration revokedTtl) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            redisTemplate.execute(
                    REVOKE_SESSION_SCRIPT,
                    List.of(revokedKey(sessionId), sessionKey(sessionId)),
                    Long.toString(positiveMillis(revokedTtl))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to revoke token session", ex);
        }
    }

    @Override
    public boolean isSessionRevoked(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(revokedKey(sessionId)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read revoked session marker", ex);
        }
    }

    @Override
    public boolean isGenerationRevoked(String generationId) {
        if (generationId == null || generationId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(revokedGenerationKey(generationId)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read revoked token generation marker", ex);
        }
    }

    private long positiveMillis(Duration ttl) {
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("Token TTL cannot be negative");
        }
        return Math.max(1, ttl.toMillis());
    }

    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private String revokedKey(String sessionId) {
        return REVOKED_PREFIX + sessionId;
    }

    private String revokedGenerationKey(String generationId) {
        return REVOKED_GENERATION_PREFIX + generationId;
    }
}
