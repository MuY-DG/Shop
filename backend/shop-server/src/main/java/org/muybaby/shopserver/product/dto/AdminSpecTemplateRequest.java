package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AdminSpecTemplateRequest(
        @NotBlank @Size(max = 64) String name,
        @NotEmpty @Size(max = 10) List<@Valid AdminSpecTemplateGroupRequest> groups
) {
}
