package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public class AdminSkuUpsertRequest {

    private Long id;

    @Size(max = 64)
    private String skuCode;

    private String specJson;

    private String specText;

    @NotNull
    @Min(1)
    private Long priceCent;

    @Min(0)
    private Long originalPriceCent;

    @Min(0)
    private Integer stockAvailable;

    @Min(0)
    private Integer lowStockThreshold;

    @Min(0)
    private Integer weightGram;

    @Min(0)
    private Long costPriceCent;

    private BigDecimal volumeCubicMeter;

    private String image;

    private Long imageFileId;

    private String status;

    @Min(0)
    private Integer sortOrder;

    private Boolean defaultSelected;

    private String combinationKey;

    private List<String> specValueKeys;

    @Valid
    @Size(max = 5)
    private List<AdminWholesaleTierUpsertRequest> wholesaleTiers;

    private boolean imageFileIdSpecified;

    private boolean specValueKeysSpecified;

    private boolean costPriceCentSpecified;

    private boolean volumeCubicMeterSpecified;

    private boolean lowStockThresholdSpecified;

    private boolean wholesaleTiersSpecified;

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
        this.specValueKeys = List.of();
        this.wholesaleTiers = List.of();
        this.imageFileIdSpecified = imageFileIdSpecified;
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
            Long costPriceCent,
            BigDecimal volumeCubicMeter,
            String image,
            Long imageFileId,
            String status,
            Integer sortOrder,
            Boolean defaultSelected,
            String combinationKey,
            List<String> specValueKeys,
            boolean imageFileIdSpecified
    ) {
        this(
                id, skuCode, specJson, specText, priceCent, originalPriceCent, stockAvailable, weightGram,
                costPriceCent, volumeCubicMeter, image, imageFileId, status, sortOrder, defaultSelected,
                combinationKey, specValueKeys, imageFileIdSpecified, specValueKeys != null
        );
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
            Long costPriceCent,
            BigDecimal volumeCubicMeter,
            String image,
            Long imageFileId,
            String status,
            Integer sortOrder,
            Boolean defaultSelected,
            String combinationKey,
            List<String> specValueKeys,
            boolean imageFileIdSpecified,
            boolean specValueKeysSpecified
    ) {
        this(
                id, skuCode, specJson, specText, priceCent, originalPriceCent, stockAvailable, weightGram,
                costPriceCent, volumeCubicMeter, image, imageFileId, status, sortOrder, defaultSelected,
                combinationKey, specValueKeys, imageFileIdSpecified, specValueKeysSpecified,
                costPriceCent != null, volumeCubicMeter != null
        );
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
            Long costPriceCent,
            BigDecimal volumeCubicMeter,
            String image,
            Long imageFileId,
            String status,
            Integer sortOrder,
            Boolean defaultSelected,
            String combinationKey,
            List<String> specValueKeys,
            boolean imageFileIdSpecified,
            boolean specValueKeysSpecified,
            boolean costPriceCentSpecified,
            boolean volumeCubicMeterSpecified
    ) {
        this.id = id;
        this.skuCode = skuCode;
        this.specJson = specJson;
        this.specText = specText;
        this.priceCent = priceCent;
        this.originalPriceCent = originalPriceCent;
        this.stockAvailable = stockAvailable;
        this.weightGram = weightGram;
        this.costPriceCent = costPriceCent;
        this.volumeCubicMeter = volumeCubicMeter;
        this.image = image;
        this.imageFileId = imageFileId;
        this.status = status;
        this.sortOrder = sortOrder;
        this.defaultSelected = defaultSelected;
        this.combinationKey = combinationKey;
        this.specValueKeys = specValueKeys == null ? List.of() : List.copyOf(specValueKeys);
        this.wholesaleTiers = List.of();
        this.imageFileIdSpecified = imageFileIdSpecified;
        this.specValueKeysSpecified = specValueKeysSpecified;
        this.costPriceCentSpecified = costPriceCentSpecified;
        this.volumeCubicMeterSpecified = volumeCubicMeterSpecified;
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

    public Integer lowStockThreshold() {
        return lowStockThreshold;
    }

    public void setLowStockThreshold(Integer lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
        this.lowStockThresholdSpecified = true;
    }

    public Integer weightGram() {
        return weightGram;
    }

    public void setWeightGram(Integer weightGram) {
        this.weightGram = weightGram;
    }

    public Long costPriceCent() {
        return costPriceCent;
    }

    public void setCostPriceCent(Long costPriceCent) {
        this.costPriceCent = costPriceCent;
        this.costPriceCentSpecified = true;
    }

    public BigDecimal volumeCubicMeter() {
        return volumeCubicMeter;
    }

    public void setVolumeCubicMeter(BigDecimal volumeCubicMeter) {
        this.volumeCubicMeter = volumeCubicMeter;
        this.volumeCubicMeterSpecified = true;
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

    public Boolean defaultSelected() {
        return defaultSelected;
    }

    public void setDefaultSelected(Boolean defaultSelected) {
        this.defaultSelected = defaultSelected;
    }

    public String combinationKey() {
        return combinationKey;
    }

    public void setCombinationKey(String combinationKey) {
        this.combinationKey = combinationKey;
    }

    public List<String> specValueKeys() {
        return specValueKeys == null ? List.of() : specValueKeys;
    }

    public void setSpecValueKeys(List<String> specValueKeys) {
        this.specValueKeys = specValueKeys == null ? List.of() : List.copyOf(specValueKeys);
        this.specValueKeysSpecified = true;
    }

    public boolean imageFileIdSpecified() {
        return imageFileIdSpecified;
    }

    public boolean specValueKeysSpecified() {
        return specValueKeysSpecified;
    }

    public boolean costPriceCentSpecified() {
        return costPriceCentSpecified;
    }

    public boolean volumeCubicMeterSpecified() {
        return volumeCubicMeterSpecified;
    }

    public boolean lowStockThresholdSpecified() {
        return lowStockThresholdSpecified;
    }

    public List<AdminWholesaleTierUpsertRequest> wholesaleTiers() {
        return wholesaleTiers == null ? List.of() : wholesaleTiers;
    }

    public void setWholesaleTiers(List<AdminWholesaleTierUpsertRequest> wholesaleTiers) {
        this.wholesaleTiers = wholesaleTiers == null ? List.of() : List.copyOf(wholesaleTiers);
        this.wholesaleTiersSpecified = true;
    }

    public boolean wholesaleTiersSpecified() {
        return wholesaleTiersSpecified;
    }
}
