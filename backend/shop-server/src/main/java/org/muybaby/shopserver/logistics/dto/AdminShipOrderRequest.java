package org.muybaby.shopserver.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.muybaby.shopserver.logistics.LogisticsType;

import java.util.List;

public record AdminShipOrderRequest(
        @NotNull LogisticsType logisticsType,
        @NotBlank String itemDesc,
        @Size(max = 128) String expressCompanyCode,
        @Size(max = 80) String trackingNo,
        @Size(max = 128) String consignorContact,
        @Size(max = 255) String shipmentNote,
        @Size(max = 100) List<@Valid ShipmentItemRequest> items
) {
    public AdminShipOrderRequest(
            LogisticsType logisticsType,
            String itemDesc,
            String expressCompanyCode,
            String trackingNo,
            String consignorContact,
            String shipmentNote
    ) {
        this(logisticsType, itemDesc, expressCompanyCode, trackingNo, consignorContact, shipmentNote, null);
    }
}
