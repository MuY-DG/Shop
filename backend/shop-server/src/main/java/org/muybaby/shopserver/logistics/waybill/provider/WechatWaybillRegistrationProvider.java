package org.muybaby.shopserver.logistics.waybill.provider;

public interface WechatWaybillRegistrationProvider {

    WechatWaybillRegistrationResult trace(WechatWaybillRegistrationRequest request);

    WechatWaybillRegistrationResult follow(WechatWaybillRegistrationRequest request);
}
