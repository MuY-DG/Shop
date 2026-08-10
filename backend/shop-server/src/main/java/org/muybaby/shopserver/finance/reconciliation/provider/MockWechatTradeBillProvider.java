package org.muybaby.shopserver.finance.reconciliation.provider;

import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@Component
@ConditionalOnProperty(prefix = "shop.pay", name = "mock-enabled", havingValue = "true")
public class MockWechatTradeBillProvider implements WechatTradeBillProvider {

    private static final byte[] EMPTY_TRADE_BILL = ("交易时间,交易状态,微信订单号,商户订单号,"
            + "微信退款单号,商户退款单号,订单金额,申请退款金额,退款状态,货币种类\r\n"
            + "总交易单数,订单总金额,申请退款总金额\r\n"
            + "0,0.00,0.00\r\n").getBytes(StandardCharsets.UTF_8);

    @Override
    public WechatTradeBillDownload openTradeBill(
            ResolvedPaymentConfig config,
            LocalDate billDate
    ) {
        return new MockDownload(EMPTY_TRADE_BILL);
    }

    private static final class MockDownload implements WechatTradeBillDownload {

        private final ByteArrayInputStream input;

        private MockDownload(byte[] content) {
            this.input = new ByteArrayInputStream(content);
        }

        @Override
        public InputStream inputStream() {
            return input;
        }

        @Override
        public boolean verifyProviderHash() {
            return input.available() == 0;
        }
    }
}
