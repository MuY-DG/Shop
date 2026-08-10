package org.muybaby.shopserver.finance.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.finance.reconciliation")
public record FinanceReconciliationProperties(
        boolean workerEnabled,
        boolean dailyEnabled,
        String dailyCron,
        Duration workerDelay,
        Duration claimTimeout,
        Duration retryBase,
        Duration retryMax,
        int maxAttempts,
        int lookbackDays,
        DataSize maxSourceSize,
        long maxRows,
        int maxFieldLength,
        int exportMaxDays,
        long exportMaxRows
) {
    @ConstructorBinding
    public FinanceReconciliationProperties {
        dailyCron = defaultString(dailyCron, "0 30 10 * * *");
        workerDelay = defaultDuration(workerDelay, Duration.ofSeconds(30));
        claimTimeout = defaultDuration(claimTimeout, Duration.ofMinutes(15));
        retryBase = defaultDuration(retryBase, Duration.ofMinutes(5));
        retryMax = defaultDuration(retryMax, Duration.ofHours(6));
        maxAttempts = positiveOrDefault(maxAttempts, 8);
        lookbackDays = positiveOrDefault(lookbackDays, 90);
        maxSourceSize = maxSourceSize == null ? DataSize.ofMegabytes(20) : maxSourceSize;
        maxRows = maxRows <= 0 ? 50_000L : maxRows;
        maxFieldLength = positiveOrDefault(maxFieldLength, 4_096);
        exportMaxDays = positiveOrDefault(exportMaxDays, 31);
        exportMaxRows = exportMaxRows <= 0 ? 50_000L : exportMaxRows;
        if (workerDelay.isZero() || workerDelay.isNegative()
                || claimTimeout.isZero() || claimTimeout.isNegative()
                || retryBase.isZero() || retryBase.isNegative()
                || retryMax.compareTo(retryBase) < 0
                || maxSourceSize.isNegative() || maxSourceSize.toBytes() == 0L) {
            throw new IllegalArgumentException("Invalid finance reconciliation limits");
        }
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Duration defaultDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }
}
