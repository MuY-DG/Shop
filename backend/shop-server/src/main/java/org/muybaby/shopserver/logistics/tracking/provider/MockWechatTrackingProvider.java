package org.muybaby.shopserver.logistics.tracking.provider;

import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "true")
public class MockWechatTrackingProvider implements WechatTrackingProvider {

    @Override
    public WechatTrackingQueryResult query(WechatTrackingQueryRequest request) {
        if (request == null
                || request.shipmentId() <= 0
                || request.registrationKind() == null
                || !StringUtils.hasText(request.waybillToken())) {
            return WechatTrackingQueryResult.failure(
                    WechatProviderOutcome.UNAVAILABLE,
                    "INVALID_REQUEST",
                    "Mock WeChat tracking query request is invalid"
            );
        }
        return WechatTrackingQueryResult.success(2);
    }

    @Override
    public WechatTrackingPathResult getPath(WechatTrackingPathRequest request) {
        if (request == null
                || request.shipmentId() <= 0
                || missing(
                request.providerOrderId(), request.openid(), request.deliveryId(), request.waybillId()
        )) {
            return WechatTrackingPathResult.failure(
                    WechatProviderOutcome.UNAVAILABLE,
                    "INVALID_REQUEST",
                    "Mock WeChat tracking path request is invalid"
            );
        }
        return WechatTrackingPathResult.success(List.of(
                new WechatTrackingPathItem(1_786_000_200L, 200001, "快件正在运输中"),
                new WechatTrackingPathItem(1_786_000_100L, 100001, "快递公司已揽件")
        ));
    }

    private boolean missing(String... values) {
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }
}
