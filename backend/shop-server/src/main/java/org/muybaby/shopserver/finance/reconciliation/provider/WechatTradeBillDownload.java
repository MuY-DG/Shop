package org.muybaby.shopserver.finance.reconciliation.provider;

import java.io.IOException;
import java.io.InputStream;

public interface WechatTradeBillDownload extends AutoCloseable {

    InputStream inputStream();

    boolean verifyProviderHash();

    @Override
    default void close() throws IOException {
        inputStream().close();
    }
}
