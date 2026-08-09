package org.muybaby.shopserver.user.address.service;

public record OwnedAddress(
        Long id,
        Long userId,
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        String locationName,
        String doorplate,
        String formattedAddress
) {
}
