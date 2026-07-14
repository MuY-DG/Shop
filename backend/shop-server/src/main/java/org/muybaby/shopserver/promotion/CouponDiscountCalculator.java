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
        Long scopedSpuId = resolveScopedSpuId(candidate);
        if (scopedSpuId == null && !isAllScope(candidate)) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "SCOPE_UNSUPPORTED");
        }
        if (!DiscountType.AMOUNT_OFF.name().equals(candidate.discountType())) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "DISCOUNT_TYPE_UNSUPPORTED");
        }

        long totalAmountCent = Math.max(context.totalAmountCent(), 0L);
        long applicableAmountCent = scopedSpuId == null
                ? totalAmountCent
                : Math.max(context.amountCentForSpu(scopedSpuId), 0L);
        if (scopedSpuId != null && applicableAmountCent == 0L) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "SCOPE_NOT_APPLICABLE");
        }
        long thresholdCent = candidate.thresholdCent() == null ? 0L : candidate.thresholdCent();
        if (applicableAmountCent < thresholdCent) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "THRESHOLD_NOT_MET");
        }

        long discountCent = Math.max(candidate.discountCent() == null ? 0L : candidate.discountCent(), 0L);
        if (discountCent == 0L) {
            return new DiscountResult(candidate.userCouponId(), true, 0L, null);
        }
        long maxDiscountCent = Math.min(
                applicableAmountCent,
                Math.max(totalAmountCent - 1L, 0L)
        );
        if (maxDiscountCent == 0L) {
            return new DiscountResult(candidate.userCouponId(), false, 0L, "PAYABLE_AMOUNT_TOO_LOW");
        }
        return new DiscountResult(
                candidate.userCouponId(),
                true,
                Math.min(discountCent, maxDiscountCent),
                null
        );
    }

    private boolean isAllScope(CouponCandidate candidate) {
        return CouponScopeType.ALL.name().equals(candidate.scopeType())
                && !StringUtils.hasText(candidate.scopeValue());
    }

    private Long resolveScopedSpuId(CouponCandidate candidate) {
        if (!CouponScopeType.PRODUCT.name().equals(candidate.scopeType())
                || !StringUtils.hasText(candidate.scopeValue())) {
            return null;
        }
        try {
            long spuId = Long.parseLong(candidate.scopeValue().trim());
            return spuId > 0L ? spuId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
