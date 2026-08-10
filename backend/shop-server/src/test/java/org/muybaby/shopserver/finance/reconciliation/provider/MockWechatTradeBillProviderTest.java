package org.muybaby.shopserver.finance.reconciliation.provider;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.download.StagedTradeBill;
import org.muybaby.shopserver.finance.reconciliation.download.TradeBillDownloadService;
import org.muybaby.shopserver.finance.reconciliation.parser.ParsedTradeBill;
import org.muybaby.shopserver.finance.reconciliation.parser.WechatTradeBillParser;
import org.springframework.util.unit.DataSize;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MockWechatTradeBillProviderTest {

    @Test
    void deterministicMockSourcePassesDownloadIntegrityAndStrictSummaryParsing() throws Exception {
        FinanceReconciliationProperties properties = new FinanceReconciliationProperties(
                false, false, null, null, null, null, null,
                8, 90, DataSize.ofMegabytes(1), 100, 4_096, 31, 1_000);
        TradeBillDownloadService downloadService = new TradeBillDownloadService(
                new MockWechatTradeBillProvider(), properties);
        WechatTradeBillParser parser = new WechatTradeBillParser(properties);

        try (StagedTradeBill staged = downloadService.download(null, LocalDate.of(2026, 8, 1))) {
            ParsedTradeBill parsed = parser.parse(staged.path());
            assertThat(parsed.rows()).isEmpty();
            assertThat(parsed.paymentAmountCent()).isZero();
            assertThat(parsed.refundAmountCent()).isZero();
            assertThat(staged.providerHashVerified()).isTrue();
        }
    }
}
