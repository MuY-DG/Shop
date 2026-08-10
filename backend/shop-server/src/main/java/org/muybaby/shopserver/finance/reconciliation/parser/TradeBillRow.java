package org.muybaby.shopserver.finance.reconciliation.parser;

import org.muybaby.shopserver.finance.reconciliation.TradeBillEntryType;

import java.time.LocalDateTime;

public record TradeBillRow(
        long rowNo,
        TradeBillEntryType entryType,
        String transactionId,
        String outTradeNo,
        String refundId,
        String outRefundNo,
        LocalDateTime occurredAt,
        long amountCent,
        String currency,
        String channelStatus,
        String rowDigest
) {
}
