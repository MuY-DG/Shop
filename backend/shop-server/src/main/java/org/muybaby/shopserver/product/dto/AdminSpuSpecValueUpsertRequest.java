package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminSpuSpecValueUpsertRequest {

    private Long id;

    @NotBlank
    @Size(max = 64)
    private String valueKey;

    @NotBlank
    @Size(max = 30)
    private String valueName;

    private String image;

    private Long imageFileId;

    @Min(0)
    private Integer sortOrder;

    private boolean imageFileIdSpecified;

    public AdminSpuSpecValueUpsertRequest() {
    }

    public AdminSpuSpecValueUpsertRequest(
            Long id,
            String valueKey,
            String valueName,
            String image,
            Long imageFileId,
            Integer sortOrder
    ) {
        this(id, valueKey, valueName, image, imageFileId, sortOrder, imageFileId != null);
    }

    public AdminSpuSpecValueUpsertRequest(
            Long id,
            String valueKey,
            String valueName,
            String image,
            Long imageFileId,
            Integer sortOrder,
            boolean imageFileIdSpecified
    ) {
        this.id = id;
        this.valueKey = valueKey;
        this.valueName = valueName;
        this.image = image;
        this.imageFileId = imageFileId;
        this.sortOrder = sortOrder;
        this.imageFileIdSpecified = imageFileIdSpecified;
    }

    public Long id() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String valueKey() {
        return valueKey;
    }

    public void setValueKey(String valueKey) {
        this.valueKey = valueKey;
    }

    public String valueName() {
        return valueName;
    }

    public void setValueName(String valueName) {
        this.valueName = valueName;
    }

    public String image() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Long imageFileId() {
        return imageFileId;
    }

    public void setImageFileId(Long imageFileId) {
        this.imageFileId = imageFileId;
        this.imageFileIdSpecified = true;
    }

    public Integer sortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean imageFileIdSpecified() {
        return imageFileIdSpecified;
    }
}
