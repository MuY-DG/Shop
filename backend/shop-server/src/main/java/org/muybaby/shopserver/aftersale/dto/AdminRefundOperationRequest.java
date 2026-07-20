package org.muybaby.shopserver.aftersale.dto;

/**
 * An operator note is mandatory for exceptional refund actions so every provider query, retry or
 * manual hand-off has a human-readable reason in the order audit trail.
 */
public record AdminRefundOperationRequest(
        String note
) {
}
