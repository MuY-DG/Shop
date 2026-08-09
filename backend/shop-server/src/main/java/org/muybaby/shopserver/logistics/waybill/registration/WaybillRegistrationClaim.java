package org.muybaby.shopserver.logistics.waybill.registration;

import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationRequest;

record WaybillRegistrationClaim(
        long shipmentId,
        String claimToken,
        WaybillRegistrationKind kind,
        WechatWaybillRegistrationRequest request
) {
}
