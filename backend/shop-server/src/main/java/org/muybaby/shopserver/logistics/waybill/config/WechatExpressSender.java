package org.muybaby.shopserver.logistics.waybill.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WechatExpressSender(
        @NotNull @Size(max = 64) String name,
        @NotNull @Size(max = 32) String mobile,
        @NotNull @Size(max = 64) String company,
        @NotNull @Size(max = 64) String province,
        @NotNull @Size(max = 64) String city,
        @NotNull @Size(max = 64) String district,
        @NotNull @Size(max = 512) String detailAddress
) {
}
