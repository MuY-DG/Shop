package org.muybaby.shopserver.logistics.waybill.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "true")
public class MockWechatWaybillRegistrationProvider implements WechatWaybillRegistrationProvider {

    @Override
    public WechatWaybillRegistrationResult trace(WechatWaybillRegistrationRequest request) {
        return result(request, "mock-trace-token-");
    }

    @Override
    public WechatWaybillRegistrationResult follow(WechatWaybillRegistrationRequest request) {
        return result(request, "mock-follow-token-");
    }

    private WechatWaybillRegistrationResult result(
            WechatWaybillRegistrationRequest request,
            String prefix
    ) {
        if (!valid(request)) {
            return WechatWaybillRegistrationResult.failure(
                    WechatProviderOutcome.UNAVAILABLE,
                    "INVALID_REQUEST",
                    "Mock WeChat waybill registration request is invalid"
            );
        }
        return WechatWaybillRegistrationResult.success(prefix + request.shipmentId());
    }

    private boolean valid(WechatWaybillRegistrationRequest request) {
        return request != null
                && request.shipmentId() != null
                && !missing(
                request.openid(),
                request.receiverPhone(),
                request.waybillId(),
                request.transactionId(),
                request.orderDetailPath()
        )
                && request.goods() != null
                && !request.goods().isEmpty()
                && request.goods().stream().allMatch(this::validGoods);
    }

    private boolean validGoods(WechatWaybillGoodsItem item) {
        if (item == null || missing(item.goodsName(), item.goodsImageUrl())) {
            return false;
        }
        try {
            URI uri = URI.create(item.goodsImageUrl().trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
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
