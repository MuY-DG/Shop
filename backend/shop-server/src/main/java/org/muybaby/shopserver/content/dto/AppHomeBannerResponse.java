package org.muybaby.shopserver.content.dto;

public record AppHomeBannerResponse(
        Long id,
        String title,
        String subtitle,
        String imageUrl,
        String jumpType,
        Long jumpTargetId,
        String jumpPath
) {
}
