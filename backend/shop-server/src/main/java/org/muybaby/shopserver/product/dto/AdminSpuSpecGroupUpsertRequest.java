package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class AdminSpuSpecGroupUpsertRequest {

    private Long id;

    @NotBlank
    @Size(max = 64)
    private String groupKey;

    @NotBlank
    @Size(max = 30)
    private String name;

    private Boolean imageEnabled;

    @Min(0)
    private Integer sortOrder;

    @Valid
    private List<AdminSpuSpecValueUpsertRequest> values;

    public AdminSpuSpecGroupUpsertRequest() {
    }

    public AdminSpuSpecGroupUpsertRequest(
            Long id,
            String groupKey,
            String name,
            Boolean imageEnabled,
            Integer sortOrder,
            List<AdminSpuSpecValueUpsertRequest> values
    ) {
        this.id = id;
        this.groupKey = groupKey;
        this.name = name;
        this.imageEnabled = imageEnabled;
        this.sortOrder = sortOrder;
        this.values = values;
    }

    public Long id() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String groupKey() {
        return groupKey;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean imageEnabled() {
        return imageEnabled;
    }

    public void setImageEnabled(Boolean imageEnabled) {
        this.imageEnabled = imageEnabled;
    }

    public Integer sortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public List<AdminSpuSpecValueUpsertRequest> values() {
        return values == null ? List.of() : values;
    }

    public void setValues(List<AdminSpuSpecValueUpsertRequest> values) {
        this.values = values;
    }
}
