package org.muybaby.shopserver.order.dto;

/** 订单列表使用的轻量售后摘要，不携带凭证、商品明细等详情字段。 */
public record AppOrderAfterSaleSummaryResponse(
        String afterSaleType,
        String status,
        Long requestedAmountCent,
        Long approvedAmountCent,
        Long refundAmountCent
) {
}
