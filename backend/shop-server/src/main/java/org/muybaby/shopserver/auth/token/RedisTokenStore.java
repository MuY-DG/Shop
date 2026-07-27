package org.muybaby.shopserver.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisTokenStore implements TokenStore {

    private static final String SESSION_PREFIX = "shop:auth:session:";
    private static final String REVOKED_PREFIX = "shop:auth:revoked:";
    private static final String REVOKED_GENERATION_PREFIX = "shop:auth:generation-revoked:";
    private static final String SESSION_METADATA_PREFIX = "shop:auth:session-meta:";
    private static final String SUBJECT_SESSION_PREFIX = "shop:auth:subject:";

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
            local function keyType(key)
                local result = redis.call('TYPE', key)
                return result['ok']
            end

            local indexType = keyType(KEYS[2])
            local metadataType = keyType(KEYS[3])

            local subjectKey = nil
            local sessionId = nil
            if metadataType == 'hash' then
                local kind = redis.call('HGET', KEYS[3], 'kind')
                local subjectId = redis.call('HGET', KEYS[3], 'subjectId')
                sessionId = redis.call('HGET', KEYS[3], 'sessionId')
                if kind and subjectId and sessionId then
                    subjectKey = ARGV[2] .. kind .. ':' .. subjectId .. ':sessions'
                    local subjectType = keyType(subjectKey)
                    if subjectType ~= 'none' and subjectType ~= 'zset' then
                        subjectKey = nil
                        sessionId = nil
                    end
                end
            end

            local family = {}
            if indexType == 'set' then
                family = redis.call('SMEMBERS', KEYS[2])
            end

            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            for _, tokenKey in ipairs(family) do
                redis.call('DEL', tokenKey)
            end
            redis.call('DEL', KEYS[2])
            redis.call('DEL', KEYS[3])
            if subjectKey and sessionId then
                redis.call('ZREM', subjectKey, sessionId)
                if redis.call('ZCARD', subjectKey) == 0 then
                    redis.call('DEL', subjectKey)
                end
            end
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
    public boolean saveRegisteredFamily(
            AccountSession accountSession,
            int maxSessions,
            List<TokenGrant> grants,
            Duration revokedTtl
    ) {
        if (accountSession == null) {
            throw new IllegalArgumentException("Account session is required");
        }
        if (maxSessions < 0) {
            throw new IllegalArgumentException("Maximum sessions cannot be negative");
        }
        List<TokenGrant> family = List.copyOf(grants);
        if (family.isEmpty()) {
            throw new IllegalArgumentException("At least one token grant is required");
        }

        try {
            List<String> serializedSessions = new ArrayList<>(family.size());
            long metadataTtlMillis = 1L;
            for (TokenGrant grant : family) {
                TokenSession tokenSession = grant.session();
                if (!accountSession.sessionId().equals(tokenSession.sessionId())
                        || accountSession.kind() != tokenSession.kind()
                        || !accountSession.subjectId().equals(tokenSession.subjectId())) {
                    throw new IllegalArgumentException(
                            "Token grant session does not match account session registration"
                    );
                }
                serializedSessions.add(objectMapper.writeValueAsString(tokenSession));
                metadataTtlMillis = Math.max(metadataTtlMillis, positiveMillis(grant.ttl()));
            }

            List<String> keys = new ArrayList<>(family.size() + 4);
            keys.add(sessionKey(accountSession.sessionId()));
            keys.add(revokedKey(accountSession.sessionId()));
            keys.add(sessionMetadataKey(accountSession.sessionId()));
            keys.add(subjectSessionKey(accountSession.kind(), accountSession.subjectId()));
            family.stream().map(TokenGrant::key).forEach(keys::add);

            List<Object> arguments = new ArrayList<>(16 + (family.size() * 2));
            arguments.add(accountSession.sessionId());
            arguments.add(accountSession.kind().namespace());
            arguments.add(accountSession.subjectId().toString());
            arguments.add(accountSession.subjectName());
            arguments.add(accountSession.deviceId());
            arguments.add(accountSession.ipAddress());
            arguments.add(accountSession.userAgent());
            arguments.add(accountSession.loginAt().toString());
            arguments.add(accountSession.lastSeenAt().toString());
            arguments.add(Long.toString(accountSession.loginAt().toEpochMilli()));
            arguments.add(Long.toString(metadataTtlMillis));
            arguments.add(Long.toString(positiveMillis(revokedTtl)));
            arguments.add(Integer.toString(maxSessions));
            arguments.add(SESSION_PREFIX);
            arguments.add(REVOKED_PREFIX);
            arguments.add(SESSION_METADATA_PREFIX);
            for (int index = 0; index < family.size(); index++) {
                arguments.add(serializedSessions.get(index));
                arguments.add(Long.toString(positiveMillis(family.get(index).ttl())));
            }

            Long saved = redisTemplate.execute(
                    RedisAccountSessionScripts.SAVE_REGISTERED_FAMILY,
                    keys,
                    arguments.toArray()
            );
            return Long.valueOf(1L).equals(saved);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to save registered token family", ex);
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
                    List.of(
                            revokedKey(sessionId),
                            sessionKey(sessionId),
                            sessionMetadataKey(sessionId)
                    ),
                    Long.toString(positiveMillis(revokedTtl)),
                    SUBJECT_SESSION_PREFIX
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to revoke token session", ex);
        }
    }

    @Override
    public List<AccountSession> listSessions(TokenKind kind, Long subjectId) {
        requireSubject(kind, subjectId);
        String subjectKey = subjectSessionKey(kind, subjectId);
        try {
            Set<String> sessionIds = redisTemplate.opsForZSet().reverseRange(subjectKey, 0, -1);
            if (sessionIds == null || sessionIds.isEmpty()) {
                return List.of();
            }
            List<AccountSession> sessions = new ArrayList<>(sessionIds.size());
            List<String> staleSessionIds = new ArrayList<>();
            for (String sessionId : sessionIds) {
                Map<Object, Object> metadata =
                        redisTemplate.opsForHash().entries(sessionMetadataKey(sessionId));
                if (metadata.isEmpty()) {
                    staleSessionIds.add(sessionId);
                    continue;
                }
                AccountSession session = readAccountSession(metadata);
                if (session.kind() != kind || !subjectId.equals(session.subjectId())) {
                    staleSessionIds.add(sessionId);
                    continue;
                }
                sessions.add(session);
            }
            if (!staleSessionIds.isEmpty()) {
                redisTemplate.opsForZSet().remove(subjectKey, staleSessionIds.toArray());
            }
            return sessions.stream()
                    .sorted(Comparator.comparing(AccountSession::loginAt)
                            .reversed()
                            .thenComparing(AccountSession::sessionId))
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to list account sessions", ex);
        }
    }

    @Override
    public boolean revokeSubjectSession(
            TokenKind kind,
            Long subjectId,
            String sessionId,
            Duration revokedTtl
    ) {
        requireSubject(kind, subjectId);
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            Long revoked = redisTemplate.execute(
                    RedisAccountSessionScripts.REVOKE_SUBJECT_SESSION,
                    List.of(
                            subjectSessionKey(kind, subjectId),
                            sessionMetadataKey(sessionId),
                            revokedKey(sessionId),
                            sessionKey(sessionId)
                    ),
                    kind.namespace(),
                    subjectId.toString(),
                    sessionId,
                    Long.toString(positiveMillis(revokedTtl))
            );
            return Long.valueOf(1L).equals(revoked);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to revoke account session", ex);
        }
    }

    @Override
    public int revokeSubjectSessions(TokenKind kind, Long subjectId, Duration revokedTtl) {
        requireSubject(kind, subjectId);
        return revokeOrTrimSubjectSessions(kind, subjectId, -1, revokedTtl);
    }

    @Override
    public int trimSubjectSessions(
            TokenKind kind,
            Long subjectId,
            int maxSessions,
            Duration revokedTtl
    ) {
        requireSubject(kind, subjectId);
        if (maxSessions < 0) {
            throw new IllegalArgumentException("Maximum sessions cannot be negative");
        }
        if (maxSessions == 0) {
            return 0;
        }
        return revokeOrTrimSubjectSessions(kind, subjectId, maxSessions, revokedTtl);
    }

    @Override
    public boolean renewSession(String sessionId, TokenKind kind, Duration ttl) {
        if (sessionId == null || sessionId.isBlank() || kind == null) {
            return false;
        }
        try {
            Long renewed = redisTemplate.execute(
                    RedisAccountSessionScripts.RENEW_SESSION,
                    List.of(sessionMetadataKey(sessionId), sessionKey(sessionId)),
                    kind.namespace(),
                    Long.toString(positiveMillis(ttl)),
                    SUBJECT_SESSION_PREFIX
            );
            return Long.valueOf(1L).equals(renewed);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to renew account session", ex);
        }
    }

    @Override
    public boolean touchSession(String sessionId, TokenKind kind, Instant lastSeenAt) {
        if (sessionId == null || sessionId.isBlank() || kind == null || lastSeenAt == null) {
            return false;
        }
        try {
            Long touched = redisTemplate.execute(
                    RedisAccountSessionScripts.TOUCH_SESSION,
                    List.of(sessionMetadataKey(sessionId)),
                    kind.namespace(),
                    lastSeenAt.toString()
            );
            return Long.valueOf(1L).equals(touched);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to touch account session", ex);
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

    private int revokeOrTrimSubjectSessions(
            TokenKind kind,
            Long subjectId,
            int targetMax,
            Duration revokedTtl
    ) {
        try {
            Long revoked = redisTemplate.execute(
                    RedisAccountSessionScripts.REVOKE_OR_TRIM_SUBJECT,
                    List.of(subjectSessionKey(kind, subjectId)),
                    kind.namespace(),
                    subjectId.toString(),
                    Integer.toString(targetMax),
                    Long.toString(positiveMillis(revokedTtl)),
                    SESSION_PREFIX,
                    REVOKED_PREFIX,
                    SESSION_METADATA_PREFIX
            );
            return revoked == null ? 0 : Math.toIntExact(revoked);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to revoke account sessions", ex);
        }
    }

    private AccountSession readAccountSession(Map<Object, Object> metadata) {
        String namespace = metadataValue(metadata, "kind");
        TokenKind kind = tokenKind(namespace);
        return new AccountSession(
                metadataValue(metadata, "sessionId"),
                kind,
                Long.valueOf(metadataValue(metadata, "subjectId")),
                metadataValue(metadata, "subjectName"),
                metadataValue(metadata, "deviceId"),
                metadataValue(metadata, "ipAddress"),
                metadataValue(metadata, "userAgent"),
                Instant.parse(metadataValue(metadata, "loginAt")),
                Instant.parse(metadataValue(metadata, "lastSeenAt"))
        );
    }

    private String metadataValue(Map<Object, Object> metadata, String field) {
        Object value = metadata.get(field);
        if (value == null) {
            throw new IllegalStateException("Account session metadata is missing " + field);
        }
        return value.toString();
    }

    private TokenKind tokenKind(String namespace) {
        for (TokenKind kind : TokenKind.values()) {
            if (kind.namespace().equals(namespace)) {
                return kind;
            }
        }
        throw new IllegalStateException("Unknown token kind namespace");
    }

    private void requireSubject(TokenKind kind, Long subjectId) {
        if (kind == null) {
            throw new IllegalArgumentException("Token kind is required");
        }
        if (subjectId == null) {
            throw new IllegalArgumentException("Subject ID is required");
        }
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

    private String sessionMetadataKey(String sessionId) {
        return SESSION_METADATA_PREFIX + sessionId;
    }

    private String subjectSessionKey(TokenKind kind, Long subjectId) {
        return SUBJECT_SESSION_PREFIX + kind.namespace() + ":" + subjectId + ":sessions";
    }
}
