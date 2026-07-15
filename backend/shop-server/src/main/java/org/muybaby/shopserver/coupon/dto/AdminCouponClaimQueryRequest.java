package org.muybaby.shopserver.coupon.dto;

public record AdminCouponClaimQueryRequest(
        Long current,
        Long size,
        String templateName,
        String userKeyword,
        String distributionMode,
        String issueSource,
        String status
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }
}
