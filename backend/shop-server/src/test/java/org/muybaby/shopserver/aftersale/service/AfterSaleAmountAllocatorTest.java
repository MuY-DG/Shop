package org.muybaby.shopserver.aftersale.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterSaleAmountAllocatorTest {

    @Test
    void quantityTranchesAllocateEveryCentExactlyOnce() {
        long first = AfterSaleAmountAllocator.tranche(101, 2, 0, 1);
        long second = AfterSaleAmountAllocator.tranche(101, 2, 1, 1);

        assertThat(first).isEqualTo(50);
        assertThat(second).isEqualTo(51);
        assertThat(first + second).isEqualTo(101);
    }

    @Test
    void invalidOrOverlappingQuantityRangeIsRejected() {
        assertThatThrownBy(() -> AfterSaleAmountAllocator.tranche(100, 2, 1, 2))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AfterSaleAmountAllocator.tranche(-1, 2, 0, 1))
                .isInstanceOf(BusinessException.class);
    }
}
