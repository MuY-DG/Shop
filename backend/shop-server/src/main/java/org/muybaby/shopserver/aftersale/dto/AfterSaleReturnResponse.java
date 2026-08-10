package org.muybaby.shopserver.aftersale.dto;

import java.time.LocalDateTime;

public record AfterSaleReturnResponse(
        Long returnAddressId,
        String contactName,
        String contactPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        String deliveryCompanyCode,
        String deliveryCompanyName,
        String trackingNo,
        LocalDateTime returnDeadlineAt,
        LocalDateTime userShippedAt,
        LocalDateTime merchantReceivedAt,
        String inspectionResult,
        String inspectionNote,
        LocalDateTime inspectedAt
) {
}
