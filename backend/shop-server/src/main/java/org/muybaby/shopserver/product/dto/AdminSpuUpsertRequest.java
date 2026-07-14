package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class AdminSpuUpsertRequest {

    @NotNull
    private Long categoryId;

    @NotBlank
    private String title;

    private String subtitle;

    @NotBlank
    private String mainImage;

    private Long mainImageFileId;

    private String mainVideo;

    private Long mainVideoFileId;

    private String specType;

    private Long freightTemplateId;

    @Min(0)
    private Long virtualSales;

    private String sellingPoints;

    private String detailHtml;

    @Min(0)
    private Integer sortOrder;

    @Valid
    private List<AdminProductImageUpsertRequest> images;

    @Valid
    private List<AdminSkuUpsertRequest> skus;

    @Valid
    private List<AdminSpuSpecGroupUpsertRequest> specGroups;

    private List<String> tags;

    private List<Long> guaranteeServiceIds;

    private List<Long> couponTemplateIds;

    private boolean mainImageFileIdSpecified;

    private boolean mainVideoFileIdSpecified;

    private boolean specTypeSpecified;

    private boolean specGroupsSpecified;

    private boolean tagsSpecified;

    private boolean guaranteeServiceIdsSpecified;

    public AdminSpuUpsertRequest() {
    }

    public AdminSpuUpsertRequest(
            Long categoryId,
            String title,
            String subtitle,
            String mainImage,
            Long mainImageFileId,
            String sellingPoints,
            String detailHtml,
            Integer sortOrder,
            List<AdminProductImageUpsertRequest> images,
            List<AdminSkuUpsertRequest> skus
    ) {
        this(categoryId, title, subtitle, mainImage, mainImageFileId, sellingPoints, detailHtml, sortOrder, images, skus,
                mainImageFileId != null);
    }

    public AdminSpuUpsertRequest(
            Long categoryId,
            String title,
            String subtitle,
            String mainImage,
            Long mainImageFileId,
            String sellingPoints,
            String detailHtml,
            Integer sortOrder,
            List<AdminProductImageUpsertRequest> images,
            List<AdminSkuUpsertRequest> skus,
            boolean mainImageFileIdSpecified
    ) {
        this.categoryId = categoryId;
        this.title = title;
        this.subtitle = subtitle;
        this.mainImage = mainImage;
        this.mainImageFileId = mainImageFileId;
        this.sellingPoints = sellingPoints;
        this.detailHtml = detailHtml;
        this.sortOrder = sortOrder;
        this.images = images;
        this.skus = skus;
        this.mainImageFileIdSpecified = mainImageFileIdSpecified;
    }

    public AdminSpuUpsertRequest(
            Long categoryId,
            String title,
            String subtitle,
            String mainImage,
            Long mainImageFileId,
            String mainVideo,
            Long mainVideoFileId,
            String specType,
            Long freightTemplateId,
            Long virtualSales,
            String sellingPoints,
            String detailHtml,
            Integer sortOrder,
            List<AdminProductImageUpsertRequest> images,
            List<AdminSkuUpsertRequest> skus,
            List<AdminSpuSpecGroupUpsertRequest> specGroups,
            List<String> tags,
            List<Long> guaranteeServiceIds,
            List<Long> couponTemplateIds,
            boolean mainImageFileIdSpecified,
            boolean mainVideoFileIdSpecified,
            boolean specTypeSpecified
    ) {
        this(
                categoryId, title, subtitle, mainImage, mainImageFileId, mainVideo, mainVideoFileId, specType,
                freightTemplateId, virtualSales, sellingPoints, detailHtml, sortOrder, images, skus, specGroups,
                tags, guaranteeServiceIds, couponTemplateIds, mainImageFileIdSpecified, mainVideoFileIdSpecified,
                specTypeSpecified, specGroups != null, tags != null, guaranteeServiceIds != null
        );
    }

    public AdminSpuUpsertRequest(
            Long categoryId,
            String title,
            String subtitle,
            String mainImage,
            Long mainImageFileId,
            String mainVideo,
            Long mainVideoFileId,
            String specType,
            Long freightTemplateId,
            Long virtualSales,
            String sellingPoints,
            String detailHtml,
            Integer sortOrder,
            List<AdminProductImageUpsertRequest> images,
            List<AdminSkuUpsertRequest> skus,
            List<AdminSpuSpecGroupUpsertRequest> specGroups,
            List<String> tags,
            List<Long> guaranteeServiceIds,
            List<Long> couponTemplateIds,
            boolean mainImageFileIdSpecified,
            boolean mainVideoFileIdSpecified,
            boolean specTypeSpecified,
            boolean specGroupsSpecified,
            boolean tagsSpecified,
            boolean guaranteeServiceIdsSpecified
    ) {
        this.categoryId = categoryId;
        this.title = title;
        this.subtitle = subtitle;
        this.mainImage = mainImage;
        this.mainImageFileId = mainImageFileId;
        this.mainVideo = mainVideo;
        this.mainVideoFileId = mainVideoFileId;
        this.specType = specType;
        this.freightTemplateId = freightTemplateId;
        this.virtualSales = virtualSales;
        this.sellingPoints = sellingPoints;
        this.detailHtml = detailHtml;
        this.sortOrder = sortOrder;
        this.images = images;
        this.skus = skus;
        this.specGroups = specGroups;
        this.tags = tags;
        this.guaranteeServiceIds = guaranteeServiceIds;
        this.couponTemplateIds = couponTemplateIds;
        this.mainImageFileIdSpecified = mainImageFileIdSpecified;
        this.mainVideoFileIdSpecified = mainVideoFileIdSpecified;
        this.specTypeSpecified = specTypeSpecified;
        this.specGroupsSpecified = specGroupsSpecified;
        this.tagsSpecified = tagsSpecified;
        this.guaranteeServiceIdsSpecified = guaranteeServiceIdsSpecified;
    }

    public Long categoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String subtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String mainImage() {
        return mainImage;
    }

    public void setMainImage(String mainImage) {
        this.mainImage = mainImage;
    }

    public Long mainImageFileId() {
        return mainImageFileId;
    }

    public void setMainImageFileId(Long mainImageFileId) {
        this.mainImageFileId = mainImageFileId;
        this.mainImageFileIdSpecified = true;
    }

    public String mainVideo() {
        return mainVideo;
    }

    public void setMainVideo(String mainVideo) {
        this.mainVideo = mainVideo;
    }

    public Long mainVideoFileId() {
        return mainVideoFileId;
    }

    public void setMainVideoFileId(Long mainVideoFileId) {
        this.mainVideoFileId = mainVideoFileId;
        this.mainVideoFileIdSpecified = true;
    }

    public String specType() {
        return specType;
    }

    public void setSpecType(String specType) {
        this.specType = specType;
        this.specTypeSpecified = true;
    }

    public Long freightTemplateId() {
        return freightTemplateId;
    }

    public void setFreightTemplateId(Long freightTemplateId) {
        this.freightTemplateId = freightTemplateId;
    }

    public Long virtualSales() {
        return virtualSales;
    }

    public void setVirtualSales(Long virtualSales) {
        this.virtualSales = virtualSales;
    }

    public String sellingPoints() {
        return sellingPoints;
    }

    public void setSellingPoints(String sellingPoints) {
        this.sellingPoints = sellingPoints;
    }

    public String detailHtml() {
        return detailHtml;
    }

    public void setDetailHtml(String detailHtml) {
        this.detailHtml = detailHtml;
    }

    public Integer sortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public List<AdminProductImageUpsertRequest> images() {
        return images;
    }

    public void setImages(List<AdminProductImageUpsertRequest> images) {
        this.images = images;
    }

    public List<AdminSkuUpsertRequest> skus() {
        return skus;
    }

    public void setSkus(List<AdminSkuUpsertRequest> skus) {
        this.skus = skus;
    }

    public List<AdminSpuSpecGroupUpsertRequest> specGroups() {
        return specGroups == null ? List.of() : specGroups;
    }

    public void setSpecGroups(List<AdminSpuSpecGroupUpsertRequest> specGroups) {
        this.specGroups = specGroups;
        this.specGroupsSpecified = true;
    }

    public List<String> tags() {
        return tags == null ? List.of() : tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
        this.tagsSpecified = true;
    }

    public List<Long> guaranteeServiceIds() {
        return guaranteeServiceIds == null ? List.of() : guaranteeServiceIds;
    }

    public void setGuaranteeServiceIds(List<Long> guaranteeServiceIds) {
        this.guaranteeServiceIds = guaranteeServiceIds;
        this.guaranteeServiceIdsSpecified = true;
    }

    public List<Long> couponTemplateIds() {
        return couponTemplateIds == null ? List.of() : couponTemplateIds;
    }

    public void setCouponTemplateIds(List<Long> couponTemplateIds) {
        this.couponTemplateIds = couponTemplateIds;
    }

    public boolean mainImageFileIdSpecified() {
        return mainImageFileIdSpecified;
    }

    public boolean mainVideoFileIdSpecified() {
        return mainVideoFileIdSpecified;
    }

    public boolean specTypeSpecified() {
        return specTypeSpecified;
    }

    public boolean specGroupsSpecified() {
        return specGroupsSpecified;
    }

    public boolean tagsSpecified() {
        return tagsSpecified;
    }

    public boolean guaranteeServiceIdsSpecified() {
        return guaranteeServiceIdsSpecified;
    }
}
