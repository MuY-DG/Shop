package org.muybaby.shopserver.logistics.provider;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WechatShippingItem(
        String trackingNo,
        String expressCompany,
        String itemDesc,
        String consignorContact,
        String receiverContact
) {
}
