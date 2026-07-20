package org.muybaby.shopserver.content.dto;

public record AppHomeProductFeatureResponse(
        String code,
        String name,
        String displayText,
        String renderer,
        Integer level
) {
}
