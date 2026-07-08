package org.muybaby.shopserver.product.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

public class AdminProductImageUpsertRequest {

    @NotBlank
    private String url;

    private Long fileId;

    private boolean fileIdSpecified;

    public AdminProductImageUpsertRequest() {
    }

    public AdminProductImageUpsertRequest(String url, Long fileId) {
        this(url, fileId, fileId != null);
    }

    public AdminProductImageUpsertRequest(String url, Long fileId, boolean fileIdSpecified) {
        this.url = url;
        this.fileId = fileId;
        this.fileIdSpecified = fileIdSpecified;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AdminProductImageUpsertRequest fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return new AdminProductImageUpsertRequest(null, null, false);
        }
        if (node.isTextual()) {
            return new AdminProductImageUpsertRequest(node.asText(), null, false);
        }
        boolean fileIdSpecified = node.has("fileId");
        return new AdminProductImageUpsertRequest(
                node.path("url").isMissingNode() || node.path("url").isNull() ? null : node.path("url").asText(),
                node.path("fileId").isMissingNode() || node.path("fileId").isNull() ? null : node.path("fileId").asLong(),
                fileIdSpecified
        );
    }

    public String url() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Long fileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
        this.fileIdSpecified = true;
    }

    public boolean fileIdSpecified() {
        return fileIdSpecified;
    }
}
