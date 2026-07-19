package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AdminCategoryRequest {

    @NotNull
    @Min(0)
    private Long parentId;

    @NotBlank
    private String name;

    private String icon;

    private Long iconFileId;

    @NotNull
    @Min(0)
    private Integer sortOrder;

    @NotBlank
    private String status;

    private boolean iconFileIdSpecified;

    public AdminCategoryRequest() {
    }

    public AdminCategoryRequest(
            Long parentId,
            String name,
            String icon,
            Long iconFileId,
            Integer sortOrder,
            String status
    ) {
        this(parentId, name, icon, iconFileId, sortOrder, status, iconFileId != null);
    }

    public AdminCategoryRequest(
            Long parentId,
            String name,
            String icon,
            Long iconFileId,
            Integer sortOrder,
            String status,
            boolean iconFileIdSpecified
    ) {
        this.parentId = parentId;
        this.name = name;
        this.icon = icon;
        this.iconFileId = iconFileId;
        this.sortOrder = sortOrder;
        this.status = status;
        this.iconFileIdSpecified = iconFileIdSpecified;
    }

    public Long parentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String icon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Long iconFileId() {
        return iconFileId;
    }

    public void setIconFileId(Long iconFileId) {
        this.iconFileId = iconFileId;
        this.iconFileIdSpecified = true;
    }

    public Integer sortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean iconFileIdSpecified() {
        return iconFileIdSpecified;
    }
}
