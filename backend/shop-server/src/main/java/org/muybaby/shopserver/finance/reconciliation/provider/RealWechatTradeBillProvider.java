package org.muybaby.shopserver.finance.reconciliation.provider;

import com.wechat.pay.java.service.billdownload.BillDownloadServiceExtension;
import com.wechat.pay.java.service.billdownload.DigestBillEntity;
import com.wechat.pay.java.service.billdownload.model.BillType;
import com.wechat.pay.java.service.billdownload.model.GetTradeBillRequest;
import com.wechat.pay.java.service.billdownload.model.TarType;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPaySdkConfigFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;

@Component
@ConditionalOnProperty(prefix = "shop.pay", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class RealWechatTradeBillProvider implements WechatTradeBillProvider {

    @Override
    public WechatTradeBillDownload openTradeBill(
            ResolvedPaymentConfig config,
            LocalDate billDate
    ) {
        GetTradeBillRequest request = new GetTradeBillRequest();
        request.setBillDate(billDate.toString());
        request.setBillType(BillType.ALL);
        request.setTarType(TarType.GZIP);
        DigestBillEntity bill = new BillDownloadServiceExtension.Builder()
                .config(WechatPaySdkConfigFactory.create(config))
                .build()
                .getTradeBill(request);
        return new SdkTradeBillDownload(bill);
    }

    private record SdkTradeBillDownload(DigestBillEntity bill) implements WechatTradeBillDownload {
        @Override
        public InputStream inputStream() {
            return bill.getInputStream();
        }

        @Override
        public boolean verifyProviderHash() {
            return bill.verifyHash();
        }
    }
}
