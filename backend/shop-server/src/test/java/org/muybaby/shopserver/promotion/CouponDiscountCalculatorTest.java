package org.muybaby.shopserver.promotion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CouponDiscountCalculatorTest {

    @Test
    void noThresholdAmountCouponDiscountsUpToCartAmount() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 3990L, 1)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "ALL", 0L, 500L));

        assertThat(result.available()).isTrue();
        assertThat(result.discountAmountCent()).isEqualTo(500L);
    }

    @Test
    void minimumSpendCouponRequiresThreshold() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 3990L, 2)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "ALL", 10000L, 2000L));

        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).isEqualTo("THRESHOLD_NOT_MET");
    }

    @Test
    void unsupportedScopeReturnsUnavailableReason() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 3990L, 1)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "PRODUCT", 0L, 500L));

        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).isEqualTo("SCOPE_UNSUPPORTED");
    }

    @Test
    void discountDoesNotExceedCartAmount() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 300L, 1)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "ALL", 0L, 500L));

        assertThat(result.available()).isTrue();
        assertThat(result.discountAmountCent()).isEqualTo(300L);
    }

    private CouponCandidate coupon(Long userCouponId, String scopeType, Long thresholdCent, Long discountCent) {
        return new CouponCandidate(
                userCouponId,
                5001L,
                "Coupon",
                "NO_THRESHOLD",
                "AMOUNT_OFF",
                thresholdCent,
                discountCent,
                scopeType,
                ""
        );
    }
}
