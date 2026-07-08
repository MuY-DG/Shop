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

    private String sellingPoints;

    private String detailHtml;

    @NotNull
    @Min(0)
    private Integer sortOrder;

    @Valid
    private List<AdminProductImageUpsertRequest> images;

    @Valid
    private List<AdminSkuUpsertRequest> skus;

    private boolean mainImageFileIdSpecified;

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

    public boolean mainImageFileIdSpecified() {
        return mainImageFileIdSpecified;
    }
}
