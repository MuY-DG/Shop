package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AppOrderServiceOrderNoTest {

    @Test
    void concurrentGeneratorsAtTheSameSecondProduceCompatibleUniqueOrderNumbers() {
        LocalDateTime fixedTime = LocalDateTime.of(2026, 7, 19, 12, 34, 56);
        int generatedCount = 20_000;
        Set<String> orderNumbers = ConcurrentHashMap.newKeySet(generatedCount);

        IntStream.range(0, generatedCount)
                .parallel()
                .mapToObj(ignored -> AppOrderService.nextOrderNo(fixedTime))
                .forEach(orderNumbers::add);

        assertThat(orderNumbers).hasSize(generatedCount);
        assertThat(orderNumbers).allSatisfy(orderNo -> {
            assertThat(orderNo).hasSize(31);
            assertThat(orderNo).startsWith("ORD20260719123456");
            assertThat(orderNo).matches("ORD[0-9]{14}[0-9A-Z]{14}");
            assertThat("P" + orderNo).hasSizeLessThanOrEqualTo(32);
        });
    }
}
