package org.muybaby.shopserver.order.dto;

import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
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
        String closeReason,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        OrderShipmentResponse shipment,
        List<OrderItemResponse> items
) {
}
