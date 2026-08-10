package org.muybaby.shopserver.payment.provider;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;

public final class WechatPaySdkConfigFactory {

    private WechatPaySdkConfigFactory() {
    }

    public static Config create(ResolvedPaymentConfig config) {
        return new RSAPublicKeyConfig.Builder()
                .merchantId(config.mchId())
                .merchantSerialNumber(config.merchantSerialNo())
                .privateKey(config.privateKeyPem())
                .apiV3Key(config.apiV3Key())
                .publicKeyId(config.wechatPublicKeyId())
                .publicKey(config.wechatPublicKeyPem())
                .build();
    }
}
