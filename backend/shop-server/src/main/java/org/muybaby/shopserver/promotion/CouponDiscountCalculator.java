package org.muybaby.shopserver.promotion;

import org.muybaby.shopserver.coupon.CouponScopeType;
import org.muybaby.shopserver.coupon.DiscountType;
import org.springframework.util.StringUtils;

public class CouponDiscountCalculator implements PromotionCalculator<CouponCandidate> {

    @Override
    public DiscountResult calculate(CheckoutContext context, CouponCandidate candidate) {
        if (context == null || candidate == null) {
            return new DiscountResult(null, false, 0L, "COUPON_UNAVAILABLE");
        }
        if (!CouponScopeType.ALL.name().equals(candidate.scopeType())) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "SCOPE_UNSUPPORTED");
        }
        if (StringUtils.hasText(candidate.scopeValue())) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "SCOPE_UNSUPPORTED");
        }
        if (!DiscountType.AMOUNT_OFF.name().equals(candidate.discountType())) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "DISCOUNT_TYPE_UNSUPPORTED");
        }

        long totalAmountCent = Math.max(context.totalAmountCent(), 0L);
        long thresholdCent = candidate.thresholdCent() == null ? 0L : candidate.thresholdCent();
        if (totalAmountCent < thresholdCent) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "THRESHOLD_NOT_MET");
        }

        long discountCent = candidate.discountCent() == null ? 0L : candidate.discountCent();
        return new DiscountResult(
                candidate.userCouponId(),
                true,
                Math.min(discountCent, totalAmountCent),
                null
        );
    }
}
