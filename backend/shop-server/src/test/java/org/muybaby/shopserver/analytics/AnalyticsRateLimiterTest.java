package org.muybaby.shopserver.analytics;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

class AnalyticsRateLimiterTest {

    private static final String VISITOR_ID = "00000000-0000-4000-8000-000000000071";

    @Test
    void trustedProxyHeaderIsUsedButAnUntrustedPeerCannotSpoofIt() {
        AnalyticsClientIpResolver resolver = new AnalyticsClientIpResolver(properties());
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("127.0.0.1");
        trusted.addHeader("X-Forwarded-For", "198.51.100.25");
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("203.0.113.10");
        untrusted.addHeader("X-Forwarded-For", "198.51.100.25");

        assertThat(resolver.resolve(trusted)).isEqualTo("198.51.100.25");
        assertThat(resolver.resolve(untrusted)).isEqualTo("203.0.113.10");
    }

    @Test
    void usesOneAtomicLuaDecisionAndDoesNotPutRawIdentifiersInRedisKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(), any(), any(), any())).thenReturn(1L);
        AnalyticsRateLimiter limiter = limiter(redis);
        MockHttpServletRequest request = request();

        limiter.check(request, VISITOR_ID, 25);

        List<Invocation> executions = mockingDetails(redis).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .toList();
        assertThat(executions).hasSize(1);
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) executions.get(0).getArguments()[1];
        assertThat(keys).hasSize(2).allSatisfy(key -> assertThat(key)
                .doesNotContain("198.51.100.25", VISITOR_ID));
        assertThat(((RedisScript<?>) executions.get(0).getArguments()[0]).getScriptAsString())
                .contains("INCRBY", "PEXPIRE");
    }

    @Test
    void rejectsExceededLimitsButFailsOpenWhenRedisIsUnavailable() {
        StringRedisTemplate limitedRedis = mock(StringRedisTemplate.class);
        when(limitedRedis.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(), any(), any(), any())).thenReturn(0L);

        assertThatThrownBy(() -> limiter(limitedRedis).check(request(), VISITOR_ID, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ANALYTICS_RATE_LIMITED));

        StringRedisTemplate unavailableRedis = mock(StringRedisTemplate.class);
        when(unavailableRedis.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(), any(), any(), any())).thenThrow(new IllegalStateException("redis unavailable"));

        assertThatCode(() -> limiter(unavailableRedis).check(request(), VISITOR_ID, 1))
                .doesNotThrowAnyException();
    }

    private AnalyticsRateLimiter limiter(StringRedisTemplate redis) {
        AnalyticsRateLimitProperties properties = properties();
        return new AnalyticsRateLimiter(redis, properties, new AnalyticsClientIpResolver(properties));
    }

    private AnalyticsRateLimitProperties properties() {
        return new AnalyticsRateLimitProperties(
                true,
                Duration.ofMinutes(1),
                500,
                100,
                List.of("127.0.0.0/8", "::1/128"));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.25");
        return request;
    }
}
