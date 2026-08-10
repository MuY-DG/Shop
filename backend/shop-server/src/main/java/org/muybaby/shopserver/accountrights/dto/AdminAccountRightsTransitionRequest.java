package org.muybaby.shopserver.accountrights.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AdminAccountRightsTransitionRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Size(max = 1000) String retentionExplanation,
        @Size(max = 20) List<@NotBlank @Size(max = 64) String> retainedDataCategories
) {
}
