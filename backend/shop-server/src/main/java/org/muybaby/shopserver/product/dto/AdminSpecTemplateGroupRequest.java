package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AdminSpecTemplateGroupRequest(
        Long id,
        @Size(max = 64) String groupKey,
        @NotBlank @Size(max = 30) String name,
        @NotNull Boolean imageEnabled,
        @Min(0) Integer sortOrder,
        @NotEmpty @Size(max = 50) List<@Valid AdminSpecTemplateValueRequest> values
) {
}
