package org.muybaby.shopserver.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminShipOrderRequest(
        @NotBlank @Size(max = 80) String expressCompany,
        @NotBlank @Size(max = 80) String trackingNo,
        @Size(max = 255) String shipmentNote
) {
}
