package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.order.CheckoutSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

public final class CheckoutRequestDigest {

    private static final String AUTOMATIC_COUPON = "<AUTO>";

    private CheckoutRequestDigest() {
    }

    public static String initialOwnershipDigest(CheckoutRequest request) {
        return digestCanonical(canonical(request));
    }

    public static String digest(CheckoutRequest request, long freightCent) {
        if (freightCent < 0L) {
            throw new IllegalArgumentException("freightCent must not be negative");
        }
        return digestCanonical(canonical(request) + "|freightCent=" + freightCent);
    }

    private static String digestCanonical(String canonical) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonical(CheckoutRequest request) {
        String coupon = request.userCouponId() == null ? AUTOMATIC_COUPON : request.userCouponId().toString();
        if (request.source() == CheckoutSource.CART) {
            String ids = request.cartItemIds().stream()
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            return "source=CART|cartItemIds=" + ids
                    + "|addressId=" + value(request.addressId())
                    + "|userCouponId=" + coupon;
        }
        return "source=DIRECT|skuId=" + value(request.skuId())
                + "|quantity=" + value(request.quantity())
                + "|addressId=" + value(request.addressId())
                + "|userCouponId=" + coupon;
    }

    private static String value(Object value) {
        return value == null ? "<NULL>" : value.toString();
    }
}
