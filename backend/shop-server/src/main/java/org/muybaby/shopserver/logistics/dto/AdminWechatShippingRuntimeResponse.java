package org.muybaby.shopserver.logistics.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminWechatShippingRuntimeResponse(
        boolean uploadEnabled,
        boolean deliveryEnabled,
        boolean receiptReconciliationEnabled,
        boolean runtimePersisted,
        long version,
        boolean defaultUploadEnabled,
        boolean defaultDeliveryEnabled,
        boolean defaultReceiptReconciliationEnabled,
        String reason,
        @JsonStringId Long updatedBy,
        LocalDateTime updatedAt
) {
}
