package org.muybaby.shopserver.payment.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class PaymentTimeoutZSetQueue {

    public static final String QUEUE_KEY = "shop:order:payment-timeout:v1";

    private final StringRedisTemplate redisTemplate;

    public PaymentTimeoutZSetQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void schedule(Long orderId, LocalDateTime deadline) {
        requireOrderId(orderId);
        if (deadline == null) {
            throw new IllegalArgumentException("Payment deadline must not be null");
        }
        reschedule(orderId, deadline.toInstant(ZoneOffset.UTC));
    }

    public List<Long> due(Instant now, int batchSize) {
        if (now == null) {
            throw new IllegalArgumentException("Current time must not be null");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Payment timeout ZSet batch size must be between 1 and 500");
        }
        Set<String> members = redisTemplate.opsForZSet().rangeByScore(
                QUEUE_KEY, Double.NEGATIVE_INFINITY, now.toEpochMilli(), 0, batchSize);
        if (members == null || members.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = new ArrayList<>(members.size());
        List<String> invalidMembers = new ArrayList<>();
        for (String member : members) {
            try {
                long orderId = Long.parseLong(member);
                if (orderId <= 0) {
                    invalidMembers.add(member);
                } else {
                    orderIds.add(orderId);
                }
            } catch (NumberFormatException ex) {
                invalidMembers.add(member);
            }
        }
        if (!invalidMembers.isEmpty()) {
            redisTemplate.opsForZSet().remove(QUEUE_KEY, invalidMembers.toArray());
        }
        return List.copyOf(orderIds);
    }

    public void acknowledge(Long orderId) {
        requireOrderId(orderId);
        redisTemplate.opsForZSet().remove(QUEUE_KEY, orderId.toString());
    }

    public void reschedule(Long orderId, Instant nextAttemptAt) {
        requireOrderId(orderId);
        if (nextAttemptAt == null) {
            throw new IllegalArgumentException("Next attempt time must not be null");
        }
        redisTemplate.opsForZSet().add(
                QUEUE_KEY, orderId.toString(), (double) nextAttemptAt.toEpochMilli());
    }

    private void requireOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("Order id must be positive");
        }
    }
}
