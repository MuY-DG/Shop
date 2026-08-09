package org.muybaby.shopserver.logistics.waybill.registration;

import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressMode;
import org.springframework.util.StringUtils;

public record WaybillRegistrationSummary(
        boolean trackingSupported,
        WaybillRegistrationKind kind,
        WaybillRegistrationStatus status,
        String message
) {

    public static boolean trackingSupported(
            LogisticsType logisticsType,
            String expressCompanyCode,
            String trackingNo
    ) {
        return logisticsType == LogisticsType.EXPRESS
                && StringUtils.hasText(expressCompanyCode)
                && StringUtils.hasText(trackingNo);
    }

    public static boolean trackingSupported(
            LogisticsType logisticsType,
            String expressCompanyCode,
            String trackingNo,
            WaybillRegistrationStatus registrationStatus
    ) {
        return trackingSupported(logisticsType, expressCompanyCode, trackingNo)
                && registrationStatus != WaybillRegistrationStatus.SKIPPED;
    }

    public static boolean isSandboxElectronicWaybill(String mode) {
        return WechatExpressMode.SANDBOX.name().equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public static WaybillRegistrationStatus effectiveStatus(
            WaybillRegistrationStatus registrationStatus,
            String electronicWaybillMode
    ) {
        return isSandboxElectronicWaybill(electronicWaybillMode)
                ? WaybillRegistrationStatus.SKIPPED
                : registrationStatus;
    }

    public static String safeMessage(WaybillRegistrationStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PENDING -> "物流查询待登记";
            case REGISTERING -> "物流查询正在准备";
            case REGISTERED -> "物流查询已就绪";
            case FAILED -> "物流查询登记失败，可重试";
            case UNKNOWN -> "物流查询登记结果暂不明确，可重试";
            case UNAVAILABLE -> "物流查询暂不可用，请稍后重试";
            case SKIPPED -> "电子面单沙盒不支持微信官方物流轨迹";
        };
    }
}
