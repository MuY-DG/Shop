package org.muybaby.shopserver.aftersale.dto;

/**
 * An operator note is mandatory for retry and manual hand-off actions. A provider-only refresh may
 * omit it because the server records a stable action description, operator and provider result.
 */
public record AdminRefundOperationRequest(
        String note
) {
}
