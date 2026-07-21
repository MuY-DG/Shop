package org.muybaby.shopserver.order.dto;

import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.logistics.dto.AppOrderShipmentResponse;

import java.time.LocalDateTime;
import java.util.List;

public record AppOrderDetailResponse(
        Long orderId,
        String orderNo,
        String status,
        String source,
        Long productOriginalAmountCent,
        Long productAmountCent,
        Long userCouponId,
        String couponName,
        Long couponDiscountCent,
        Long freightCent,
        Long payableAmountCent,
        Long paidAmountCent,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String paymentTransactionId,
        String merchantTradeNo,
        String paymentStatus,
        LocalDateTime paymentExpiresAt,
        Long paymentRemainingSeconds,
        String outTradeNo,
        String transactionId,
        LocalDateTime paidAt,
        String closeReason,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime shippedAt,
        LocalDateTime completedAt,
        LocalDateTime refundingAt,
        LocalDateTime refundedAt,
        AppOrderShipmentResponse shipment,
        AfterSaleResponse latestAfterSale,
        List<OrderItemResponse> items
) {
}
