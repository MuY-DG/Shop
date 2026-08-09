package org.muybaby.shopserver.logistics.waybill.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.muybaby.shopserver.logistics.waybill.ElectronicWaybillStatus;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressParcel;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillEnvironment;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ElectronicWaybillAttemptResponse(
        Long id,
        Long orderId,
        WechatElectronicWaybillEnvironment environment,
        ElectronicWaybillStatus status,
        String deliveryId,
        String deliveryName,
        String bizIdMasked,
        int serviceType,
        String serviceName,
        String waybillNo,
        WechatExpressParcel parcel,
        String remark,
        Long expectTime,
        int printCount,
        LocalDateTime lastPrintedAt,
        LocalDateTime createdAt,
        LocalDateTime cancelledAt,
        LocalDateTime confirmedAt,
        boolean canRefresh,
        boolean canCancel,
        boolean canPrint,
        boolean canConfirmShipment,
        boolean canSimulate
) {
}
