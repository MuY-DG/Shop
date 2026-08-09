package org.muybaby.shopserver.logistics.tracking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "shop.wechat.express.tracking")
public record WechatTrackingProperties(
        @DefaultValue("5m") @NotNull Duration refreshInterval,
        @DefaultValue("5m") @NotNull Duration claimTimeout,
        @DefaultValue("200") @Min(1) @Max(500) int maxPathItems
) {
    public WechatTrackingProperties {
        requirePositive(refreshInterval, "WeChat tracking refresh interval");
        requirePositive(claimTimeout, "WeChat tracking claim timeout");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
