package org.muybaby.shopserver.user.address.dto;

import java.time.LocalDateTime;

public record AddressResponse(
        Long id,
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        boolean isDefault,
        String formattedAddress,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
