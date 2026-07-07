package org.muybaby.shopserver.promotion;

public interface PromotionCalculator<T> {
    DiscountResult calculate(CheckoutContext context, T candidate);
}
