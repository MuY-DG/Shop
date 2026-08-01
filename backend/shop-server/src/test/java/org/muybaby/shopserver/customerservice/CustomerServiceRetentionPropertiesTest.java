package org.muybaby.shopserver.customerservice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServiceRetentionPropertiesTest {

    @Test
    void cleanupIsOptInAndBoundsOperationalLimits() {
        CustomerServiceRetentionProperties defaults =
                new CustomerServiceRetentionProperties(null, null, null, null);
        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.effectiveDays()).isEqualTo(365);
        assertThat(defaults.effectiveBatchSize()).isEqualTo(1_000);
        assertThat(defaults.effectiveMaxBatchesPerRun()).isEqualTo(100);

        CustomerServiceRetentionProperties bounded =
                new CustomerServiceRetentionProperties(true, 99_999, 99_999, 99_999);
        assertThat(bounded.isEnabled()).isTrue();
        assertThat(bounded.effectiveDays()).isEqualTo(3_650);
        assertThat(bounded.effectiveBatchSize()).isEqualTo(10_000);
        assertThat(bounded.effectiveMaxBatchesPerRun()).isEqualTo(1_000);
    }
}
