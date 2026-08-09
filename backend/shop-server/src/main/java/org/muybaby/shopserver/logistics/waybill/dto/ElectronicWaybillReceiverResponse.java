package org.muybaby.shopserver.logistics.waybill.dto;

public record ElectronicWaybillReceiverResponse(
        String name,
        String mobile,
        String company,
        String province,
        String city,
        String district,
        String detailAddress,
        String locationName,
        String doorplate
) {
}
