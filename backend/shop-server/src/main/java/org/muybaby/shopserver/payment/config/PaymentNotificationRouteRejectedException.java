package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;

/**
 * Rejects an invalid or unknown opaque callback route before provider verification starts.
 * Callers intentionally do not persist these unauthenticated probes in the callback audit table.
 */
public final class PaymentNotificationRouteRejectedException extends BusinessException {

    public PaymentNotificationRouteRejectedException() {
        super(ErrorCode.VALIDATION_FAILED);
    }
}
