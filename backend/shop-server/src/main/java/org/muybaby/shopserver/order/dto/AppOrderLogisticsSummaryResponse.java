package org.muybaby.shopserver.order.dto;

public record AppOrderLogisticsSummaryResponse(
        long shipmentId, String statusText, String latestMessage, int packageCount
) { }
