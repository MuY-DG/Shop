package org.muybaby.shopserver.user.address.service;

public record OwnedAddress(
        Long id,
        Long userId,
        String receiverName,
        String receiverPhone,
        String formattedAddress
) {
}
