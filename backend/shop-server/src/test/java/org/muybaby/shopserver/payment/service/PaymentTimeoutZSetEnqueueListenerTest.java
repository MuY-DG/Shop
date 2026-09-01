package org.muybaby.shopserver.payment.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.OrderPaymentTimeoutScheduledEvent;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentTimeoutZSetEnqueueListenerTest {

    @Test
    void schedulesTheCommittedOrderDeadline() {
        PaymentTimeoutZSetQueue queue = mock(PaymentTimeoutZSetQueue.class);
        PaymentTimeoutZSetEnqueueListener listener = new PaymentTimeoutZSetEnqueueListener(queue);
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 1, 8, 15);

        listener.onOrderCreated(new OrderPaymentTimeoutScheduledEvent(41L, deadline));

        verify(queue).schedule(41L, deadline);
    }
}
