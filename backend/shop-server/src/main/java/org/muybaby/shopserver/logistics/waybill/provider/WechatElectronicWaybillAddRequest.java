package org.muybaby.shopserver.logistics.waybill.provider;

import java.math.BigDecimal;
import java.util.List;

public record WechatElectronicWaybillAddRequest(
        Long localRecordId,
        WechatElectronicWaybillEnvironment environment,
        String providerOrderId,
        String openid,
        String deliveryId,
        String bizId,
        String customRemark,
        WechatExpressContact sender,
        WechatExpressContact receiver,
        int parcelCount,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        List<WechatExpressCargoItem> cargoItems,
        String miniProgramOrderPath,
        List<WechatExpressShopItem> shopItems,
        int serviceType,
        String serviceName,
        Long expectedPickupTime
) {
}
