package org.muybaby.shopserver.wechat.servicecard;

import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfig;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.stream.Collectors;

public final class WechatServiceCardTestConfigs {

    private WechatServiceCardTestConfigs() {
    }

    public static WechatServiceCardConfig fromProperties(
            WechatServiceCardProperties properties
    ) {
        return new WechatServiceCardConfig(
                properties.accountTemplateRecordId(),
                properties.fallbackProductImage(),
                properties.allowedImageHosts().stream()
                        .filter(StringUtils::hasText)
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet()),
                properties.preferOrderSnapshotImages(),
                properties.callback().enabled(),
                properties.callback().token(),
                properties.callback().encodingAesKey(),
                WechatServiceCardConfig.Source.ENVIRONMENT);
    }
}
