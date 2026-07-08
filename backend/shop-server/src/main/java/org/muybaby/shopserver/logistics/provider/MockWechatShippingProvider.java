package org.muybaby.shopserver.logistics.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "true")
public class MockWechatShippingProvider implements WechatShippingProvider {

    private final List<WechatShippingUploadRequest> uploadRequests = new ArrayList<>();

    @Override
    public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
        uploadRequests.add(request);
        return WechatShippingUploadResult.uploaded();
    }

    public List<WechatShippingUploadRequest> uploadRequests() {
        return List.copyOf(uploadRequests);
    }

    public void reset() {
        uploadRequests.clear();
    }
}
