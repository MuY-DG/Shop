package org.muybaby.shopserver.logistics.provider;

public interface WechatShippingProvider {

    WechatShippingUploadResult upload(WechatShippingUploadRequest request);
}
