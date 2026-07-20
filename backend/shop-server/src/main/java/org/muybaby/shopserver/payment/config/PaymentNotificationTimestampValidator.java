package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentNotificationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;

@Component
public class PaymentNotificationTimestampValidator {

    private final PaymentNotificationProperties properties;
    private final Clock clock;

    public PaymentNotificationTimestampValidator(PaymentNotificationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void validate(String timestamp) {
        if (!StringUtils.hasText(timestamp)) {
            throw validationFailure();
        }
        try {
            Instant notificationTime = Instant.ofEpochSecond(Long.parseLong(timestamp));
            Instant now = clock.instant();
            Instant earliestAccepted = now.minus(properties.maxTimestampSkew());
            Instant latestAccepted = now.plus(properties.maxTimestampSkew());
            if (notificationTime.isBefore(earliestAccepted) || notificationTime.isAfter(latestAccepted)) {
                throw validationFailure();
            }
        } catch (NumberFormatException | DateTimeException ex) {
            throw validationFailure();
        }
    }

    private BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
