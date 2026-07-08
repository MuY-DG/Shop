package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AdminSkuUpsertRequest {

    private Long id;

    @NotBlank
    private String skuCode;

    @NotBlank
    private String specJson;

    @NotBlank
    private String specText;

    @NotNull
    @Min(1)
    private Long priceCent;

    @NotNull
    @Min(0)
    private Long originalPriceCent;

    @NotNull
    @Min(0)
    private Integer stockAvailable;

    @NotNull
    @Min(0)
    private Integer weightGram;

    private String image;

    private Long imageFileId;

    @NotBlank
    private String status;

    @NotNull
    @Min(0)
    private Integer sortOrder;

    private boolean imageFileIdSpecified;

    public AdminSkuUpsertRequest() {
    }

    public AdminSkuUpsertRequest(
            Long id,
            String skuCode,
            String specJson,
            String specText,
            Long priceCent,
            Long originalPriceCent,
            Integer stockAvailable,
            Integer weightGram,
            String image,
            Long imageFileId,
            String status,
            Integer sortOrder
    ) {
        this(id, skuCode, specJson, specText, priceCent, originalPriceCent, stockAvailable, weightGram, image, imageFileId,
                status, sortOrder, imageFileId != null);
    }

    public AdminSkuUpsertRequest(
            Long id,
            String skuCode,
            String specJson,
            String specText,
            Long priceCent,
            Long originalPriceCent,
            Integer stockAvailable,
            Integer weightGram,
            String image,
            Long imageFileId,
            String status,
            Integer sortOrder,
            boolean imageFileIdSpecified
    ) {
        this.id = id;
        this.skuCode = skuCode;
        this.specJson = specJson;
        this.specText = specText;
        this.priceCent = priceCent;
        this.originalPriceCent = originalPriceCent;
        this.stockAvailable = stockAvailable;
        this.weightGram = weightGram;
        this.image = image;
        this.imageFileId = imageFileId;
        this.status = status;
        this.sortOrder = sortOrder;
        this.imageFileIdSpecified = imageFileIdSpecified;
    }

    public Long id() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String skuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String specJson() {
        return specJson;
    }

    public void setSpecJson(String specJson) {
        this.specJson = specJson;
    }

    public String specText() {
        return specText;
    }

    public void setSpecText(String specText) {
        this.specText = specText;
    }

    public Long priceCent() {
        return priceCent;
    }

    public void setPriceCent(Long priceCent) {
        this.priceCent = priceCent;
    }

    public Long originalPriceCent() {
        return originalPriceCent;
    }

    public void setOriginalPriceCent(Long originalPriceCent) {
        this.originalPriceCent = originalPriceCent;
    }

    public Integer stockAvailable() {
        return stockAvailable;
    }

    public void setStockAvailable(Integer stockAvailable) {
        this.stockAvailable = stockAvailable;
    }

    public Integer weightGram() {
        return weightGram;
    }

    public void setWeightGram(Integer weightGram) {
        this.weightGram = weightGram;
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

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
