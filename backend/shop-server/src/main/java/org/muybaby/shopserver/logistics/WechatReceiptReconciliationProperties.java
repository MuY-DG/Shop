package org.muybaby.shopserver.logistics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "shop.wechat.shipping.receipt-reconciliation")
public record WechatReceiptReconciliationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5m") @NotNull Duration delay,
        @DefaultValue("50") @Min(1) @Max(200) int batchSize,
        @DefaultValue("1h") @NotNull Duration minShippedAge,
        @DefaultValue("30m") @NotNull Duration recheckInterval,
        @DefaultValue("5m") @NotNull Duration claimTimeout
) {

    public WechatReceiptReconciliationProperties {
        requirePositive(delay, "WeChat receipt reconciliation delay");
        requirePositive(minShippedAge, "WeChat receipt reconciliation minimum shipped age");
        requirePositive(recheckInterval, "WeChat receipt reconciliation recheck interval");
        requirePositive(claimTimeout, "WeChat receipt reconciliation claim timeout");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
