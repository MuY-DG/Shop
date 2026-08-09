package org.muybaby.shopserver.logistics.waybill.provider;

public interface WechatElectronicWaybillProvider {

    WechatElectronicWaybillResult add(WechatElectronicWaybillAddRequest request);

    WechatElectronicWaybillResult get(WechatElectronicWaybillGetRequest request);

    WechatElectronicWaybillResult cancel(WechatElectronicWaybillCancelRequest request);

    WechatElectronicWaybillResult testUpdate(WechatElectronicWaybillTestUpdateRequest request);
}
