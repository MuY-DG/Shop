package org.muybaby.shopserver.aftersale.dto;

public record AppReturnShipmentRequest(
        String deliveryCompanyCode,
        String deliveryCompanyName,
        String trackingNo
) {
}
