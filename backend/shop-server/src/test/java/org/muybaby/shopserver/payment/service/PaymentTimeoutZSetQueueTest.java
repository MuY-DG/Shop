package org.muybaby.shopserver.payment.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentTimeoutZSetQueueTest {

    @Test
    void schedulesReadsAcknowledgesAndReschedulesByUtcDeadline() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zsets);
        PaymentTimeoutZSetQueue queue = new PaymentTimeoutZSetQueue(redisTemplate);
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 1, 8, 15);
        Instant dueAt = deadline.toInstant(ZoneOffset.UTC);

        queue.schedule(41L, deadline);
        when(zsets.rangeByScore(
                PaymentTimeoutZSetQueue.QUEUE_KEY,
                Double.NEGATIVE_INFINITY,
                dueAt.toEpochMilli(),
                0,
                10
        )).thenReturn(new LinkedHashSet<>(java.util.List.of("41", "invalid", "-2")));

        assertThat(queue.due(dueAt, 10)).containsExactly(41L);
        queue.acknowledge(41L);
        queue.reschedule(41L, dueAt.plusSeconds(30));

        verify(zsets).add(PaymentTimeoutZSetQueue.QUEUE_KEY, "41", (double) dueAt.toEpochMilli());
        verify(zsets).remove(PaymentTimeoutZSetQueue.QUEUE_KEY, "invalid", "-2");
        verify(zsets).remove(PaymentTimeoutZSetQueue.QUEUE_KEY, "41");
        verify(zsets).add(PaymentTimeoutZSetQueue.QUEUE_KEY, "41", (double) dueAt.plusSeconds(30).toEpochMilli());
    }
}
