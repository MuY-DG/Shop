package org.muybaby.shopserver.user.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressUpsertRequest(
        @NotBlank @Size(max = 64) String receiverName,
        @NotBlank @Size(max = 32) String receiverPhone,
        @NotBlank @Size(max = 64) String province,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 64) String district,
        @NotBlank @Size(max = 255) String detailAddress,
        boolean isDefault
) {
    public AddressUpsertRequest {
        receiverName = strip(receiverName);
        receiverPhone = strip(receiverPhone);
        province = strip(province);
        city = strip(city);
        district = strip(district);
        detailAddress = strip(detailAddress);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
