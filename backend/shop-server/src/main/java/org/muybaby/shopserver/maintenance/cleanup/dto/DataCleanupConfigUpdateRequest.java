package org.muybaby.shopserver.maintenance.cleanup.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DataCleanupConfigUpdateRequest(
        @NotNull @Min(0) Long revision,
        @NotNull @Size(min = 6, max = 6) List<@Valid DataCleanupTaskUpdateRequest> tasks
) {
}
