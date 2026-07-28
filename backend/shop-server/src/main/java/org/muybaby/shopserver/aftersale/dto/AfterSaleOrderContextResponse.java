package org.muybaby.shopserver.aftersale.dto;

import org.muybaby.shopserver.order.dto.OrderItemResponse;

import java.util.List;

public record AfterSaleOrderContextResponse(
        Long orderId,
        String orderNo,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        Long productAmountCent,
        Long paidAmountCent,
        Integer itemCount,
        List<OrderItemResponse> items
) {
}
