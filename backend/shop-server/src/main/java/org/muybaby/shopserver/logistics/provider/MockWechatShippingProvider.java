package org.muybaby.shopserver.logistics.provider;

import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "true")
public class MockWechatShippingProvider implements WechatShippingProvider {

    private final List<WechatShippingUploadRequest> uploadRequests = new ArrayList<>();

    @Override
    public WechatProviderMode mode() {
        return WechatProviderMode.MOCK;
    }

    @Override
    public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
        uploadRequests.add(request);
        return WechatShippingUploadResult.unavailable(
                "MOCK_PROVIDER", "Mock provider cannot confirm WeChat shipping upload"
        );
    }

    @Override
    public WechatShippingCapabilityResult queryCapability() {
        return WechatShippingCapabilityResult.unavailable(
                "MOCK_PROVIDER", "Mock provider cannot confirm WeChat shipping capability"
        );
    }

    @Override
    public List<WechatDeliveryCompanyResult> getDeliveryCompanies() {
        return List.of(
                new WechatDeliveryCompanyResult("SF", "顺丰速运"),
                new WechatDeliveryCompanyResult("JD", "京东物流")
        );
    }

    public List<WechatShippingUploadRequest> uploadRequests() {
        return List.copyOf(uploadRequests);
    }

    public void reset() {
        uploadRequests.clear();
    }
}
