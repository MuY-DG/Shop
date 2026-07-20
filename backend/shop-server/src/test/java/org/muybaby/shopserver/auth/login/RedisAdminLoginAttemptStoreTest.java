package org.muybaby.shopserver.auth.login;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

class RedisAdminLoginAttemptStoreTest {

    @Test
    void usesAtomicLuaForFailureCountAndTemporaryLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(), any(), any())).thenReturn(900_000L);
        RedisAdminLoginAttemptStore store = new RedisAdminLoginAttemptStore(redis);

        Duration decision = store.recordFailure(
                "shop:auth:admin-login:account:hash",
                12,
                Duration.ofMinutes(15),
                Duration.ofMinutes(15));

        assertThat(decision).isEqualTo(Duration.ofMinutes(15));
        Invocation execution = mockingDetails(redis).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .findFirst()
                .orElseThrow();
        RedisScript<?> script = (RedisScript<?>) execution.getArguments()[0];
        assertThat(script.getScriptAsString()).contains("HINCRBY", "PEXPIRE", "failures >= limit");
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) execution.getArguments()[1];
        assertThat(keys).containsExactly("shop:auth:admin-login:account:hash");
    }

    @Test
    void nullRedisDecisionIsTreatedAsStoreFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList())).thenReturn(null);
        RedisAdminLoginAttemptStore store = new RedisAdminLoginAttemptStore(redis);

        assertThatThrownBy(() -> store.lockedFor("key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no admin login");
    }

    @Test
    void redisFailureIsMappedByTheGuardToFailClosedAuthentication() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList())).thenThrow(new IllegalStateException("redis unavailable"));
        AdminLoginProtectionProperties properties = new AdminLoginProtectionProperties(
                true,
                "redis",
                Duration.ofMinutes(15),
                5,
                12,
                30,
                Duration.ofMinutes(15),
                100);
        AdminLoginGuard guard = new AdminLoginGuard(new RedisAdminLoginAttemptStore(redis), properties);

        assertThatThrownBy(() -> guard.start("operator", "198.51.100.8"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE));
    }
}
