package org.muybaby.shopserver.payment.service;

import com.wechat.pay.java.core.exception.ValidationException;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.error.ProviderFailureCode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentialResolver;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Component
public class PaymentConfigVerifier {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfigVerifier.class);
    private static final String VERIFY_ORDER_PREFIX = "CFGTEST";

    private final WechatPayProvider wechatPayProvider;
    private final WechatPlatformCredentialResolver platformCredentialResolver;

    public PaymentConfigVerifier(
            WechatPayProvider wechatPayProvider,
            WechatPlatformCredentialResolver platformCredentialResolver
    ) {
        this.wechatPayProvider = wechatPayProvider;
        this.platformCredentialResolver = platformCredentialResolver;
    }

    public void requireUsable(ResolvedPaymentConfig config) {
        if (config == null || !StringUtils.hasText(config.appId())) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIG_VERIFICATION_FAILED);
        }
        WechatPlatformCredentials platformCredentials = platformCredentialResolver.resolve();
        if (platformCredentials == null
                || !config.appId().equals(platformCredentials.appId())) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIG_APP_ID_MISMATCH);
        }

        String outTradeNo = verificationOrderNo();
        try {
            WechatPayOrderQueryResult result = wechatPayProvider.queryOrder(config, outTradeNo);
            if (result == null || !outTradeNo.equals(result.outTradeNo())) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIG_VERIFICATION_FAILED);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (ValidationException | IllegalArgumentException ex) {
            log.warn(
                    "WeChat payment config verification rejected locally: configId={}, type={}",
                    config.configId(), ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.PAYMENT_CONFIG_VERIFICATION_FAILED);
        } catch (RuntimeException ex) {
            Integer status = ProviderFailureCode.safeHttpStatus(ex);
            ErrorCode errorCode = status == null || status == 429 || status >= 500
                    ? ErrorCode.PAYMENT_CONFIG_VERIFICATION_UNAVAILABLE
                    : ErrorCode.PAYMENT_CONFIG_VERIFICATION_FAILED;
            log.warn(
                    "WeChat payment config verification failed: configId={}, status={}, providerCode={}",
                    config.configId(),
                    status,
                    ProviderFailureCode.safeCode(ex)
            );
            throw new BusinessException(errorCode);
        }
    }

    private String verificationOrderNo() {
        String random = UUID.randomUUID().toString().replace("-", "");
        return VERIFY_ORDER_PREFIX + random.substring(0, 32 - VERIFY_ORDER_PREFIX.length());
    }
}
