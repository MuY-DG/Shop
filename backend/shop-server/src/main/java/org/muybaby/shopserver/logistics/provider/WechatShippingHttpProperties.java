package org.muybaby.shopserver.logistics.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.wechat.shipping.http")
public record WechatShippingHttpProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("1MB") DataSize maxResponseSize
) {
    public WechatShippingHttpProperties {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("WeChat shipping connect timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("WeChat shipping read timeout must be positive");
        }
        long bytes = maxResponseSize == null ? 0 : maxResponseSize.toBytes();
        if (bytes <= 0 || bytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("WeChat shipping response limit must be between 1 byte and 2 GB");
        }
    }

    public int maxResponseBytes() {
        return Math.toIntExact(maxResponseSize.toBytes());
    }
}
