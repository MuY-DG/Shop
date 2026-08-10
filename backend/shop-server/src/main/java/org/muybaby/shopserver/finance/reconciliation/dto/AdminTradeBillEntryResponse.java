package org.muybaby.shopserver.finance.reconciliation.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminTradeBillEntryResponse(
        @JsonStringId Long id,
        @JsonStringId Long batchId,
        long rowNo,
        String entryType,
        String transactionId,
        String outTradeNo,
        String refundId,
        String outRefundNo,
        LocalDateTime occurredAt,
        long amountCent,
        String currency,
        String channelStatus,
        String rowDigest,
        LocalDateTime createdAt
) {
}
