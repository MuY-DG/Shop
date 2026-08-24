package org.muybaby.shopserver.finance.reconciliation.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.TradeBillEntryType;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechatTradeBillParserTest {

    @TempDir
    private Path tempDir;

    private final WechatTradeBillParser parser = new WechatTradeBillParser(properties());

    @Test
    void parsesOfficialAllSemanticsAndUsesOrderAndRequestedRefundAmounts() throws Exception {
        ParsedTradeBill bill = parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,退款金额,退款状态,订单金额,申请退款金额,货币种类
                `2026-08-01 10:00:00,`SUCCESS,`wx-pay-1,`trade-1,`0,`0,`0.00,`0,`10.00,`0.00,`CNY
                `2026-08-01 11:00:00,`REFUND,`wx-pay-1,`trade-1,`wx-refund-1,`refund-1,`2.50,`PROCESSING,`0.00,`3.00,`CNY
                `2026-08-01 12:00:00,`REFUND,`wx-pay-1,`trade-1,`wx-refund-2,`refund-2,`1.50,`SUCCESS,`0.00,`2.00,`CNY
                总交易单数,订单总金额,申请退款总金额
                `3,`10.00,`5.00
                """);

        assertThat(bill.paymentRows()).isEqualTo(1);
        assertThat(bill.refundRows()).isEqualTo(2);
        assertThat(bill.paymentAmountCent()).isEqualTo(1_000L);
        assertThat(bill.refundAmountCent()).isEqualTo(500L);
        assertThat(bill.rows().getFirst().refundId()).isEmpty();
        assertThat(bill.rows().getFirst().outRefundNo()).isEmpty();
        assertThat(bill.rows().get(1).amountCent()).isEqualTo(300L);
    }

    @Test
    void acceptsRevokedRefundEvidenceWithoutRefundBusinessIds() throws Exception {
        ParsedTradeBill bill = parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,退款金额,退款状态,订单金额,申请退款金额,货币种类
                `2026-08-01 10:00:00,`REVOKED,`wx-pay-revoked,`trade-revoked,`0,`0,`10.00,`SUCCESS,`0.00,`10.00,`CNY
                总交易单数,订单总金额,申请退款总金额
                `1,`0.00,`10.00
                """);

        assertThat(bill.rows()).singleElement().satisfies(row -> {
            assertThat(row.entryType()).isEqualTo(TradeBillEntryType.REFUND);
            assertThat(row.refundId()).isEmpty();
            assertThat(row.outRefundNo()).isEmpty();
            assertThat(row.amountCent()).isEqualTo(1_000L);
        });
    }

    @Test
    void rejectsMissingOrTamperedSummary() throws Exception {
        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,退款金额,退款状态,订单金额,申请退款金额,货币种类
                `2026-08-01 10:00:00,`SUCCESS,`wx-pay-1,`trade-1,`0,`0,`0.00,`0,`10.00,`0.00,`CNY
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("summary header is missing");

        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,退款金额,退款状态,订单金额,申请退款金额,货币种类
                `2026-08-01 10:00:00,`SUCCESS,`wx-pay-1,`trade-1,`0,`0,`0.00,`0,`10.00,`0.00,`CNY
                总交易单数,订单总金额,申请退款总金额
                `2,`10.00,`0.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("row count");

        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,退款金额,退款状态,订单金额,申请退款金额,货币种类
                `2026-08-01 10:00:00,`SUCCESS,`wx-pay-1,`trade-1,`0,`0,`0.00,`0,`10.00,`0.00,`CNY
                总交易单数,订单总金额,申请退款总金额
                `1,`9.99,`0.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("summary amounts");
    }

    @Test
    void currentAllContractRejectsMissingOfficialColumnsInsteadOfUsingOtherAmounts() {
        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,订单金额,退款状态,货币种类
                总交易单数,订单总金额,申请退款总金额
                `0,`0.00,`0.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("申请退款金额");

        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,订单金额,申请退款金额,货币种类
                总交易单数,订单总金额,申请退款总金额
                `0,`0.00,`0.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("退款状态");

        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,订单金额,申请退款金额,退款状态,货币种类
                总交易单数,订单总金额,退款总金额
                `0,`0.00,`0.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("申请退款总金额");

        assertThatThrownBy(() -> parse("""
                交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,订单金额,申请退款金额,退款状态,货币种类
                总交易单数,订单总金额,申请退款总金额
                `0,`0.00,`0.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("交易时间");
    }

    @Test
    void currentAllContractRejectsBlankRefundStatusCurrencyAndTradeTime() {
        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,订单金额,申请退款金额,退款状态,货币种类
                `2026-08-01 11:00:00,`REFUND,`wx-pay-1,`trade-1,`wx-refund-1,`refund-1,`0.00,`3.00,,`CNY
                总交易单数,订单总金额,申请退款总金额
                `1,`0.00,`3.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("status");

        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,订单金额,申请退款金额,退款状态,货币种类
                `2026-08-01 10:00:00,`SUCCESS,`wx-pay-1,`trade-1,`0,`0,`10.00,`0.00,`0,
                总交易单数,订单总金额,申请退款总金额
                `1,`10.00,`0.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("currency");

        assertThatThrownBy(() -> parse("""
                交易时间,交易状态,微信订单号,商户订单号,微信退款单号,商户退款单号,订单金额,申请退款金额,退款状态,货币种类
                ,`SUCCESS,`wx-pay-1,`trade-1,`0,`0,`10.00,`0.00,`0,`CNY
                总交易单数,订单总金额,申请退款总金额
                `1,`10.00,`0.00
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void aggregateOverflowFailsClosed() {
        TradeBillRow first = row(1L, Long.MAX_VALUE);
        TradeBillRow second = row(2L, 1L);

        assertThatThrownBy(() -> ParsedTradeBill.of(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate exceeds supported range");
    }

    private ParsedTradeBill parse(String csv) throws IOException {
        Path file = tempDir.resolve("bill-" + System.nanoTime() + ".csv");
        Files.writeString(file, csv);
        return parser.parse(file);
    }

    private TradeBillRow row(long rowNo, long amountCent) {
        return new TradeBillRow(
                rowNo,
                TradeBillEntryType.PAYMENT,
                "transaction-" + rowNo,
                "trade-" + rowNo,
                "",
                "",
                LocalDateTime.of(2026, 8, 1, 10, 0),
                amountCent,
                "CNY",
                "SUCCESS",
                "a".repeat(64)
        );
    }

    private FinanceReconciliationProperties properties() {
        return new FinanceReconciliationProperties(
                null,
                null,
                null,
                null,
                null,
                8,
                90,
                DataSize.ofMegabytes(20),
                200_000,
                4_096,
                31,
                50_000
        );
    }
}
