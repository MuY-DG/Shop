package org.muybaby.shopserver.logistics.tracking.provider;

public interface WechatTrackingProvider {

    WechatTrackingQueryResult query(WechatTrackingQueryRequest request);

    WechatTrackingPathResult getPath(WechatTrackingPathRequest request);
}
