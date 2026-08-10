package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;

import java.math.BigInteger;

public final class AfterSaleAmountAllocator {

    private AfterSaleAmountAllocator() {
    }

    public static long tranche(
            long paidAmountBasisCent,
            int orderQuantity,
            int refundedQuantityBefore,
            int requestedQuantity
    ) {
        if (paidAmountBasisCent < 0
                || orderQuantity <= 0
                || refundedQuantityBefore < 0
                || requestedQuantity <= 0
                || refundedQuantityBefore + requestedQuantity > orderQuantity) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        BigInteger amount = BigInteger.valueOf(paidAmountBasisCent);
        BigInteger divisor = BigInteger.valueOf(orderQuantity);
        long end = amount.multiply(BigInteger.valueOf(refundedQuantityBefore + requestedQuantity))
                .divide(divisor)
                .longValueExact();
        long start = amount.multiply(BigInteger.valueOf(refundedQuantityBefore))
                .divide(divisor)
                .longValueExact();
        return Math.subtractExact(end, start);
    }
}
