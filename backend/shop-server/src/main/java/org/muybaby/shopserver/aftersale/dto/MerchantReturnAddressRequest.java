package org.muybaby.shopserver.aftersale.dto;

public record MerchantReturnAddressRequest(
        String contactName,
        String contactPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        Boolean enabled,
        Boolean defaultAddress,
        Long version
) {
}
