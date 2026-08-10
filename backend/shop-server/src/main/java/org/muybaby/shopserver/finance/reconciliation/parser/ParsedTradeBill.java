package org.muybaby.shopserver.finance.reconciliation.parser;

import org.muybaby.shopserver.finance.reconciliation.TradeBillEntryType;

import java.util.List;

public record ParsedTradeBill(
        List<TradeBillRow> rows,
        long paymentRows,
        long refundRows,
        long paymentAmountCent,
        long refundAmountCent
) {
    public ParsedTradeBill {
        rows = List.copyOf(rows);
    }

    public static ParsedTradeBill of(List<TradeBillRow> rows) {
        long paymentRows = 0L;
        long refundRows = 0L;
        long paymentAmount = 0L;
        long refundAmount = 0L;
        try {
            for (TradeBillRow row : rows) {
                if (row.entryType() == TradeBillEntryType.PAYMENT) {
                    paymentRows = Math.addExact(paymentRows, 1L);
                    paymentAmount = Math.addExact(paymentAmount, row.amountCent());
                } else {
                    refundRows = Math.addExact(refundRows, 1L);
                    refundAmount = Math.addExact(refundAmount, row.amountCent());
                }
            }
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("WeChat trade bill aggregate exceeds supported range", ex);
        }
        return new ParsedTradeBill(rows, paymentRows, refundRows, paymentAmount, refundAmount);
    }
}
