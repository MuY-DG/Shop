package org.muybaby.shopserver.content.dto;

public record AdminHomeCategoryOptionResponse(
        Long id,
        Long parentId,
        String name,
        String icon
) {
}
