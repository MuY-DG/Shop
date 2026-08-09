package org.muybaby.shopserver.logistics.waybill.provider;

public record WechatExpressContact(
        String name,
        String mobile,
        String company,
        String country,
        String province,
        String city,
        String area,
        String address
) {
}
