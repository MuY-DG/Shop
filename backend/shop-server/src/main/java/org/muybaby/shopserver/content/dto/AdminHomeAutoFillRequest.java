package org.muybaby.shopserver.content.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminHomeAutoFillRequest(
        @NotNull @Min(1) @Max(50) Integer targetCount
) {
}
