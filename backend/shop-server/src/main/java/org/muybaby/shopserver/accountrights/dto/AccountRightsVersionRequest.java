package org.muybaby.shopserver.accountrights.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AccountRightsVersionRequest(
        @NotNull @PositiveOrZero Long version
) {
}
