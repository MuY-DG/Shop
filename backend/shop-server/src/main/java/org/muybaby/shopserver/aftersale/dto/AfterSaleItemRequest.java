package org.muybaby.shopserver.aftersale.dto;

/**
 * 售后申请的商品项。requestedAmountCent 为用户自报的退款金额（分），
 * 允许为空：空表示按服务端分摊金额全额申报；非空时必须落在服务端
 * 计算的该商品可退上限之内，否则整个申请被拒绝。
 */
public record AfterSaleItemRequest(
        Long orderItemId,
        Integer quantity,
        Long requestedAmountCent
) {
    public AfterSaleItemRequest(Long orderItemId, Integer quantity) {
        this(orderItemId, quantity, null);
    }
}
