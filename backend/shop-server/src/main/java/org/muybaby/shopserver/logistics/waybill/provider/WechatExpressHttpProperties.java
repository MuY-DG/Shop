package org.muybaby.shopserver.logistics.waybill.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.wechat.express.http")
public record WechatExpressHttpProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("5MB") DataSize maxResponseSize
) {

    public WechatExpressHttpProperties {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("WeChat express connect timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("WeChat express read timeout must be positive");
        }
        long maxResponseBytes = maxResponseSize == null ? 0 : maxResponseSize.toBytes();
        if (maxResponseBytes <= 0 || maxResponseBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("WeChat express response limit must be between 1 byte and 2 GB");
        }
    }

    public int maxResponseBytes() {
        return Math.toIntExact(maxResponseSize.toBytes());
    }
}
