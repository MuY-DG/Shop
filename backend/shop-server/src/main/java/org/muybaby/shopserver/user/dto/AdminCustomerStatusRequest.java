package org.muybaby.shopserver.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminCustomerStatusRequest(
        @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
        @NotBlank @Size(max = 200) String reason
) {
}
