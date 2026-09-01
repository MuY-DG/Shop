package org.muybaby.shopserver.payment.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTimeoutZSetProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentTimeoutZSetWorkerTest {

    @Test
    void acknowledgesOnlyTerminalOrdersAndReschedulesRetryableOnes() {
        PaymentTimeoutZSetQueue queue = mock(PaymentTimeoutZSetQueue.class);
        PaymentTimeoutZSetProcessor processor = mock(PaymentTimeoutZSetProcessor.class);
        Instant now = Instant.parse("2026-09-01T08:15:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        PaymentTimeoutZSetProperties properties = new PaymentTimeoutZSetProperties(
                true, Duration.ofSeconds(1), 50, Duration.ofSeconds(30));
        PaymentTimeoutZSetWorker worker = new PaymentTimeoutZSetWorker(queue, processor, properties, clock);
        Instant retryAt = now.plusSeconds(30);

        when(queue.due(now, 2)).thenReturn(List.of(41L, 42L));
        when(processor.process(41L)).thenReturn(PaymentTimeoutZSetProcessor.Result.acknowledge());
        when(processor.process(42L)).thenReturn(PaymentTimeoutZSetProcessor.Result.reschedule(retryAt));

        worker.runOnce(2);

        verify(queue).acknowledge(41L);
        verify(queue, never()).acknowledge(42L);
        verify(queue).reschedule(42L, retryAt);
    }
}
