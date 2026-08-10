package org.muybaby.shopserver.aftersale.dto;

public record AdminAfterSaleItemApprovalRequest(
        Long orderItemId,
        Integer approvedQuantity
) {
}
