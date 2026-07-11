package org.muybaby.shopserver.user.address.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AddressResponse(
        @JsonStringId
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
