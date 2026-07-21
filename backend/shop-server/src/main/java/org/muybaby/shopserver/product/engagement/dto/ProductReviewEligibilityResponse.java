package org.muybaby.shopserver.product.engagement.dto;

import java.util.List;

public record ProductReviewEligibilityResponse(List<ReviewableOrderItemResponse> orderItems) {

    public ProductReviewEligibilityResponse {
        orderItems = orderItems == null ? List.of() : List.copyOf(orderItems);
    }
}
