package org.muybaby.shopserver.logistics.tracking.provider;

import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;

public record WechatTrackingQueryRequest(
        long shipmentId,
        WaybillRegistrationKind registrationKind,
        String waybillToken
) {
}
