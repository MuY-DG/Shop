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
    void categoryScopeRemainsUnsupported() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 3990L, 1)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "CATEGORY", "100", 0L, 500L));

        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).isEqualTo("SCOPE_UNSUPPORTED");
    }

    @Test
    void productCouponUsesOnlyMatchingSpuAmountForThresholdAndDiscountCap() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(
                new CheckoutItem(1000L, 100L, 300L, 1),
                new CheckoutItem(2000L, 200L, 700L, 1)
        ));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "PRODUCT", "100", 300L, 500L));

        assertThat(result.available()).isTrue();
        assertThat(result.discountAmountCent()).isEqualTo(300L);
    }

    @Test
    void productCouponIsUnavailableWithoutMatchingSpu() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 200L, 3990L, 1)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "PRODUCT", "100", 0L, 500L));

        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).isEqualTo("SCOPE_NOT_APPLICABLE");
    }

    @Test
    void productCouponStillLeavesPositivePayableAmountWhenItCoversTheOnlyLine() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 300L, 1)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "PRODUCT", "100", 0L, 500L));

        assertThat(result.available()).isTrue();
        assertThat(result.discountAmountCent()).isEqualTo(299L);
    }

    @Test
    void discountLeavesAtLeastOneCentPayableAmount() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 300L, 1)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "ALL", 0L, 500L));

        assertThat(result.available()).isTrue();
        assertThat(result.discountAmountCent()).isEqualTo(299L);
    }

    @Test
    void oneCentOrderCannotUseAmountOffCoupon() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 1L, 1)));

        DiscountResult result = calculator.calculate(context, coupon(7001L, "ALL", 0L, 500L));

        assertThat(result.available()).isFalse();
        assertThat(result.discountAmountCent()).isZero();
        assertThat(result.unavailableReason()).isEqualTo("PAYABLE_AMOUNT_TOO_LOW");
    }

    private CouponCandidate coupon(Long userCouponId, String scopeType, Long thresholdCent, Long discountCent) {
        return coupon(userCouponId, scopeType, "", thresholdCent, discountCent);
    }

    private CouponCandidate coupon(
            Long userCouponId,
            String scopeType,
            String scopeValue,
            Long thresholdCent,
            Long discountCent
    ) {
        return new CouponCandidate(
                userCouponId,
                5001L,
                "Coupon",
                "NO_THRESHOLD",
                "AMOUNT_OFF",
                thresholdCent,
                discountCent,
                scopeType,
                scopeValue
        );
    }
}
