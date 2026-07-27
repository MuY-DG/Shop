package org.muybaby.shopserver.logistics.provider;

import org.muybaby.shopserver.logistics.WechatProviderMode;

import java.util.List;

public interface WechatShippingProvider {

    WechatProviderMode mode();

    WechatShippingUploadResult upload(WechatShippingUploadRequest request);

    WechatShippingCapabilityResult queryCapability();

    List<WechatDeliveryCompanyResult> getDeliveryCompanies();

    default WechatReceiptQueryResult queryReceiptStatus(String transactionId) {
        return WechatReceiptQueryResult.unavailable(
                "RECEIPT_QUERY_UNSUPPORTED",
                "WeChat receipt status query is unavailable"
        );
    }
}
