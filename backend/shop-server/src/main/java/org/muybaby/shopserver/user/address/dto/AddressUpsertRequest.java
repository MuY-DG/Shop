package org.muybaby.shopserver.user.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressUpsertRequest(
        @NotBlank @Size(max = 10) String receiverName,
        @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String receiverPhone,
        @NotBlank @Size(max = 64) String province,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 64) String district,
        @NotBlank @Size(max = 255) String detailAddress,
        @Size(max = 128) String locationName,
        @Size(max = 128) String doorplate,
        boolean isDefault
) {
    public AddressUpsertRequest {
        receiverName = strip(receiverName);
        receiverPhone = stripWhitespace(receiverPhone);
        province = strip(province);
        city = strip(city);
        district = strip(district);
        detailAddress = strip(detailAddress);
        locationName = stripOrEmpty(locationName);
        doorplate = stripOrEmpty(doorplate);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static String stripOrEmpty(String value) {
        String stripped = strip(value);
        return stripped == null ? "" : stripped;
    }

    private static String stripWhitespace(String value) {
        return value == null ? null : value.replaceAll("\\s+", "");
    }
}
