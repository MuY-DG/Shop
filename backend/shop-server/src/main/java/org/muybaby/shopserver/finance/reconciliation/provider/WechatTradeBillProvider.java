package org.muybaby.shopserver.finance.reconciliation.provider;

import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;

import java.time.LocalDate;

public interface WechatTradeBillProvider {

    WechatTradeBillDownload openTradeBill(ResolvedPaymentConfig config, LocalDate billDate);
}
