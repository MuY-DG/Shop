package org.muybaby.shopserver.product.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

public record AdminProductImageUpsertRequest(
        @NotBlank String url,
        Long fileId
) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AdminProductImageUpsertRequest fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return new AdminProductImageUpsertRequest(null, null);
        }
        if (node.isTextual()) {
            return new AdminProductImageUpsertRequest(node.asText(), null);
        }
        return new AdminProductImageUpsertRequest(
                node.path("url").isMissingNode() || node.path("url").isNull() ? null : node.path("url").asText(),
                node.path("fileId").isMissingNode() || node.path("fileId").isNull() ? null : node.path("fileId").asLong()
        );
    }
}
