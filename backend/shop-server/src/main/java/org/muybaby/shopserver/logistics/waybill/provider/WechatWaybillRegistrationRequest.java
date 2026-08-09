package org.muybaby.shopserver.logistics.waybill.provider;

import java.util.List;

public record WechatWaybillRegistrationRequest(
        Long shipmentId,
        String openid,
        String senderPhone,
        String receiverPhone,
        String waybillId,
        String deliveryId,
        String transactionId,
        String orderDetailPath,
        List<WechatWaybillGoodsItem> goods
) {
}
