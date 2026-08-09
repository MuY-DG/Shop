package org.muybaby.shopserver.logistics.waybill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ElectronicWaybillSandboxEventRequest(
        @NotNull Integer actionType,
        @NotBlank @Size(max = 255) String actionMessage
) {
}
