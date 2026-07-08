package org.muybaby.shopserver.payment.provider;

import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;

public interface WechatPayProvider {
    WechatJsapiPrepayResult createJsapiPrepay(ResolvedPaymentConfig config, WechatJsapiPrepayRequest request);

    WechatPayOrderQueryResult queryOrder(ResolvedPaymentConfig config, String outTradeNo);

    void closeOrder(ResolvedPaymentConfig config, String outTradeNo);

    WechatPayNotification parsePayNotification(
            ResolvedPaymentConfig config,
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    );

    WechatRefundResult requestRefund(ResolvedPaymentConfig config, WechatRefundRequest request);

    WechatRefundNotification parseRefundNotification(
            ResolvedPaymentConfig config,
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    );
}
