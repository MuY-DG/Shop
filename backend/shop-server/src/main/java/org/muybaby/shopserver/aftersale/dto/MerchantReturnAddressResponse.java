package org.muybaby.shopserver.aftersale.dto;

import java.time.LocalDateTime;

public record MerchantReturnAddressResponse(
        Long id,
        String contactName,
        String contactPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        Boolean enabled,
        Boolean defaultAddress,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
