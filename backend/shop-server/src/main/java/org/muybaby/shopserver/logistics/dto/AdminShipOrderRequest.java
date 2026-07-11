package org.muybaby.shopserver.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.logistics.LogisticsType;

public record AdminShipOrderRequest(
        @NotNull LogisticsType logisticsType,
        @NotBlank String itemDesc,
        @Size(max = 128) String expressCompanyCode,
        @Size(max = 80) String trackingNo,
        @Size(max = 128) String consignorContact,
        @Size(max = 255) String shipmentNote
) {
}
