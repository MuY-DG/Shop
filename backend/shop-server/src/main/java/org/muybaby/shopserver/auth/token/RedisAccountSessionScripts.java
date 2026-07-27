package org.muybaby.shopserver.auth.token;

import org.springframework.data.redis.core.script.DefaultRedisScript;

final class RedisAccountSessionScripts {

    static final DefaultRedisScript<Long> SAVE_REGISTERED_FAMILY = new DefaultRedisScript<>("""
            local function keyType(key)
                local result = redis.call('TYPE', key)
                return result['ok']
            end

            local sessionId = ARGV[1]
            local kind = ARGV[2]
            local subjectId = ARGV[3]
            local metadataTtl = tonumber(ARGV[11])
            local revokedTtl = ARGV[12]
            local maxSessions = tonumber(ARGV[13])
            local sessionPrefix = ARGV[14]
            local revokedPrefix = ARGV[15]
            local metadataPrefix = ARGV[16]

            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end

            local familyType = keyType(KEYS[1])
            if familyType ~= 'none' and familyType ~= 'set' then
                return redis.error_reply('session index has wrong type')
            end
            local metadataType = keyType(KEYS[3])
            if metadataType ~= 'none' and metadataType ~= 'hash' then
                return redis.error_reply('session metadata has wrong type')
            end
            local subjectType = keyType(KEYS[4])
            if subjectType ~= 'none' and subjectType ~= 'zset' then
                return redis.error_reply('subject session index has wrong type')
            end

            if metadataType == 'hash' then
                local storedKind = redis.call('HGET', KEYS[3], 'kind')
                local storedSubjectId = redis.call('HGET', KEYS[3], 'subjectId')
                if storedKind ~= kind or storedSubjectId ~= subjectId then
                    return redis.error_reply('account session ownership cannot change')
                end
            end

            local members = {}
            if subjectType == 'zset' then
                members = redis.call('ZRANGE', KEYS[4], 0, -1)
            end
            local active = {}
            local stale = {}
            local currentIndexed = false
            for _, member in ipairs(members) do
                local memberMetadataKey = metadataPrefix .. member
                local memberMetadataType = keyType(memberMetadataKey)
                if memberMetadataType == 'none' then
                    table.insert(stale, member)
                elseif memberMetadataType ~= 'hash' then
                    return redis.error_reply('indexed session metadata has wrong type')
                else
                    local memberKind = redis.call('HGET', memberMetadataKey, 'kind')
                    local memberSubjectId = redis.call('HGET', memberMetadataKey, 'subjectId')
                    if memberKind ~= kind or memberSubjectId ~= subjectId then
                        table.insert(stale, member)
                    else
                        local memberFamilyType = keyType(sessionPrefix .. member)
                        if memberFamilyType ~= 'none' and memberFamilyType ~= 'set' then
                            return redis.error_reply('indexed session family has wrong type')
                        end
                        table.insert(active, member)
                        if member == sessionId then
                            currentIndexed = true
                        end
                    end
                end
            end

            local effectiveSubjectName = ARGV[4]
            local effectiveDeviceId = ARGV[5]
            local effectiveIpAddress = ARGV[6]
            local effectiveUserAgent = ARGV[7]
            local effectiveLoginAt = ARGV[8]
            local effectiveLoginAtMillis = ARGV[10]
            if metadataType == 'hash' then
                local storedSubjectName = redis.call('HGET', KEYS[3], 'subjectName')
                local storedDeviceId = redis.call('HGET', KEYS[3], 'deviceId')
                local storedIpAddress = redis.call('HGET', KEYS[3], 'ipAddress')
                local storedUserAgent = redis.call('HGET', KEYS[3], 'userAgent')
                local storedLoginAt = redis.call('HGET', KEYS[3], 'loginAt')
                local storedLoginAtMillis = redis.call('HGET', KEYS[3], 'loginAtEpochMillis')
                if effectiveSubjectName == '' and storedSubjectName then
                    effectiveSubjectName = storedSubjectName
                end
                if effectiveDeviceId == '' and storedDeviceId then
                    effectiveDeviceId = storedDeviceId
                end
                if effectiveIpAddress == '' and storedIpAddress then
                    effectiveIpAddress = storedIpAddress
                end
                if effectiveUserAgent == '' and storedUserAgent then
                    effectiveUserAgent = storedUserAgent
                end
                if storedLoginAt and storedLoginAt ~= '' then
                    effectiveLoginAt = storedLoginAt
                end
                if storedLoginAtMillis and storedLoginAtMillis ~= '' then
                    effectiveLoginAtMillis = storedLoginAtMillis
                end
            end

            local victims = {}
            local victimIds = {}
            local function addVictim(member)
                if member ~= sessionId and not victimIds[member] then
                    victimIds[member] = true
                    table.insert(victims, member)
                end
            end

            if effectiveDeviceId ~= '' then
                for _, member in ipairs(active) do
                    if member ~= sessionId then
                        local memberDeviceId =
                                redis.call('HGET', metadataPrefix .. member, 'deviceId')
                        if memberDeviceId == effectiveDeviceId then
                            addVictim(member)
                        end
                    end
                end
            end

            local activeAfterVictims = #active - #victims
            if not currentIndexed and maxSessions > 0 then
                for _, member in ipairs(active) do
                    if activeAfterVictims < maxSessions then
                        break
                    end
                    if member ~= sessionId and not victimIds[member] then
                        addVictim(member)
                        activeAfterVictims = activeAfterVictims - 1
                    end
                end
            end

            for _, member in ipairs(stale) do
                redis.call('ZREM', KEYS[4], member)
            end
            for _, victim in ipairs(victims) do
                local victimFamilyKey = sessionPrefix .. victim
                local victimFamily = {}
                if keyType(victimFamilyKey) == 'set' then
                    victimFamily = redis.call('SMEMBERS', victimFamilyKey)
                end
                redis.call('SET', revokedPrefix .. victim, '1', 'PX', revokedTtl)
                for _, tokenKey in ipairs(victimFamily) do
                    redis.call('DEL', tokenKey)
                end
                redis.call('DEL', victimFamilyKey)
                redis.call('DEL', metadataPrefix .. victim)
                redis.call('ZREM', KEYS[4], victim)
            end

            local tokenCount = #KEYS - 4
            for i = 1, tokenCount do
                local valueIndex = 16 + ((i - 1) * 2) + 1
                redis.call('SET', KEYS[i + 4], ARGV[valueIndex], 'PX', ARGV[valueIndex + 1])
                redis.call('SADD', KEYS[1], KEYS[i + 4])
            end
            redis.call('PEXPIRE', KEYS[1], metadataTtl)

            redis.call('HSET', KEYS[3],
                    'sessionId', sessionId,
                    'kind', kind,
                    'subjectId', subjectId,
                    'subjectName', effectiveSubjectName,
                    'deviceId', effectiveDeviceId,
                    'ipAddress', effectiveIpAddress,
                    'userAgent', effectiveUserAgent,
                    'loginAt', effectiveLoginAt,
                    'loginAtEpochMillis', effectiveLoginAtMillis,
                    'lastSeenAt', ARGV[9])
            redis.call('PEXPIRE', KEYS[3], metadataTtl)
            redis.call('ZADD', KEYS[4], effectiveLoginAtMillis, sessionId)
            local subjectTtl = redis.call('PTTL', KEYS[4])
            if subjectTtl < metadataTtl then
                redis.call('PEXPIRE', KEYS[4], metadataTtl)
            end
            return 1
            """, Long.class);

    static final DefaultRedisScript<Long> REVOKE_SUBJECT_SESSION = new DefaultRedisScript<>("""
            local function keyType(key)
                local result = redis.call('TYPE', key)
                return result['ok']
            end

            local subjectType = keyType(KEYS[1])
            if subjectType ~= 'none' and subjectType ~= 'zset' then
                return redis.error_reply('subject session index has wrong type')
            end
            local metadataType = keyType(KEYS[2])
            if metadataType == 'none' then
                if subjectType == 'zset' then
                    redis.call('ZREM', KEYS[1], ARGV[3])
                end
                return 0
            end
            if metadataType ~= 'hash' then
                return redis.error_reply('session metadata has wrong type')
            end
            if redis.call('HGET', KEYS[2], 'kind') ~= ARGV[1]
                    or redis.call('HGET', KEYS[2], 'subjectId') ~= ARGV[2] then
                return 0
            end
            local familyType = keyType(KEYS[4])
            if familyType ~= 'none' and familyType ~= 'set' then
                return redis.error_reply('session index has wrong type')
            end

            local family = {}
            if familyType == 'set' then
                family = redis.call('SMEMBERS', KEYS[4])
            end
            redis.call('SET', KEYS[3], '1', 'PX', ARGV[4])
            for _, tokenKey in ipairs(family) do
                redis.call('DEL', tokenKey)
            end
            redis.call('DEL', KEYS[4])
            redis.call('DEL', KEYS[2])
            if subjectType == 'zset' then
                redis.call('ZREM', KEYS[1], ARGV[3])
            end
            return 1
            """, Long.class);

    static final DefaultRedisScript<Long> REVOKE_OR_TRIM_SUBJECT = new DefaultRedisScript<>("""
            local function keyType(key)
                local result = redis.call('TYPE', key)
                return result['ok']
            end

            local subjectType = keyType(KEYS[1])
            if subjectType == 'none' then
                return 0
            end
            if subjectType ~= 'zset' then
                return redis.error_reply('subject session index has wrong type')
            end

            local kind = ARGV[1]
            local subjectId = ARGV[2]
            local targetMax = tonumber(ARGV[3])
            local revokedTtl = ARGV[4]
            local sessionPrefix = ARGV[5]
            local revokedPrefix = ARGV[6]
            local metadataPrefix = ARGV[7]
            local members = redis.call('ZRANGE', KEYS[1], 0, -1)
            local owned = {}
            local stale = {}

            for _, member in ipairs(members) do
                local metadataKey = metadataPrefix .. member
                local metadataType = keyType(metadataKey)
                if metadataType == 'none' then
                    table.insert(stale, member)
                elseif metadataType ~= 'hash' then
                    return redis.error_reply('indexed session metadata has wrong type')
                elseif redis.call('HGET', metadataKey, 'kind') ~= kind
                        or redis.call('HGET', metadataKey, 'subjectId') ~= subjectId then
                    table.insert(stale, member)
                else
                    local familyType = keyType(sessionPrefix .. member)
                    if familyType ~= 'none' and familyType ~= 'set' then
                        return redis.error_reply('indexed session family has wrong type')
                    end
                    table.insert(owned, member)
                end
            end

            for _, member in ipairs(stale) do
                redis.call('ZREM', KEYS[1], member)
            end

            local revokeCount = #owned
            if targetMax >= 0 then
                revokeCount = #owned - targetMax
                if revokeCount < 0 then
                    revokeCount = 0
                end
            end

            for index = 1, revokeCount do
                local member = owned[index]
                local familyKey = sessionPrefix .. member
                local family = {}
                if keyType(familyKey) == 'set' then
                    family = redis.call('SMEMBERS', familyKey)
                end
                redis.call('SET', revokedPrefix .. member, '1', 'PX', revokedTtl)
                for _, tokenKey in ipairs(family) do
                    redis.call('DEL', tokenKey)
                end
                redis.call('DEL', familyKey)
                redis.call('DEL', metadataPrefix .. member)
                redis.call('ZREM', KEYS[1], member)
            end

            if redis.call('ZCARD', KEYS[1]) == 0 then
                redis.call('DEL', KEYS[1])
            end
            return revokeCount
            """, Long.class);

    static final DefaultRedisScript<Long> RENEW_SESSION = new DefaultRedisScript<>("""
            local function keyType(key)
                local result = redis.call('TYPE', key)
                return result['ok']
            end
            local function extendTtl(key, ttl)
                local currentTtl = redis.call('PTTL', key)
                if currentTtl < ttl then
                    redis.call('PEXPIRE', key, ttl)
                end
            end

            local metadataType = keyType(KEYS[1])
            if metadataType == 'none' then
                return 0
            end
            if metadataType ~= 'hash' then
                return redis.error_reply('session metadata has wrong type')
            end
            if redis.call('HGET', KEYS[1], 'kind') ~= ARGV[1] then
                return 0
            end
            local familyType = keyType(KEYS[2])
            if familyType ~= 'none' and familyType ~= 'set' then
                return redis.error_reply('session index has wrong type')
            end

            local subjectId = redis.call('HGET', KEYS[1], 'subjectId')
            local sessionId = redis.call('HGET', KEYS[1], 'sessionId')
            local loginAtMillis = redis.call('HGET', KEYS[1], 'loginAtEpochMillis')
            if not subjectId or not sessionId or not loginAtMillis then
                return 0
            end
            local subjectKey = ARGV[3] .. ARGV[1] .. ':' .. subjectId .. ':sessions'
            local subjectType = keyType(subjectKey)
            if subjectType ~= 'none' and subjectType ~= 'zset' then
                return redis.error_reply('subject session index has wrong type')
            end

            local ttl = tonumber(ARGV[2])
            extendTtl(KEYS[1], ttl)
            if familyType == 'set' then
                extendTtl(KEYS[2], ttl)
            end
            redis.call('ZADD', subjectKey, loginAtMillis, sessionId)
            extendTtl(subjectKey, ttl)
            return 1
            """, Long.class);

    static final DefaultRedisScript<Long> TOUCH_SESSION = new DefaultRedisScript<>("""
            local typeResult = redis.call('TYPE', KEYS[1])
            local metadataType = typeResult['ok']
            if metadataType == 'none' then
                return 0
            end
            if metadataType ~= 'hash' then
                return redis.error_reply('session metadata has wrong type')
            end
            if redis.call('HGET', KEYS[1], 'kind') ~= ARGV[1] then
                return 0
            end
            redis.call('HSET', KEYS[1], 'lastSeenAt', ARGV[2])
            return 1
            """, Long.class);

    private RedisAccountSessionScripts() {
    }
}
