package org.muybaby.shopserver.product.dto;

public record AdminSpuSpecValueResponse(
        Long id,
        String valueKey,
        String valueName,
        String image,
        Long imageFileId,
        Integer sortOrder
) {
}
