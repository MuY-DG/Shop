package org.muybaby.shopserver.product.dto;

public record AppGuaranteeServiceResponse(
        Long id,
        String termsName,
        String contentDescription,
        String icon,
        Long iconFileId,
        Integer sortOrder
) {
}
