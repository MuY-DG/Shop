package org.muybaby.shopserver.order.dto;

import org.muybaby.shopserver.common.api.JsonStringId;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillAttemptResponse;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        Long orderId,
        String orderNo,
        String status,
        String source,
        @JsonStringId Long userId,
        String userNickname,
        String userPhone,
        Long productOriginalAmountCent,
        Long productAmountCent,
        Long userCouponId,
        String couponName,
        Long couponDiscountCent,
        Long freightCent,
        Long payableAmountCent,
        Long paidAmountCent,
        Integer itemCount,
        Long refundedAmountCent,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String paymentTransactionId,
        String merchantTradeNo,
        String paymentStatus,
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
        Boolean canShip,
        AdminOrderAfterSaleSummaryResponse activeAfterSale,
        OrderShipmentResponse shipment,
        ElectronicWaybillAttemptResponse electronicWaybill,
        List<OrderItemResponse> items
) {
}
