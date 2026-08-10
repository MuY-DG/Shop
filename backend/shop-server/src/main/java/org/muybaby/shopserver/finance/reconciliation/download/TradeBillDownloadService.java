package org.muybaby.shopserver.finance.reconciliation.download;

import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.provider.WechatTradeBillDownload;
import org.muybaby.shopserver.finance.reconciliation.provider.WechatTradeBillProvider;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

@Service
public class TradeBillDownloadService {

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final WechatTradeBillProvider provider;
    private final FinanceReconciliationProperties properties;

    public TradeBillDownloadService(
            WechatTradeBillProvider provider,
            FinanceReconciliationProperties properties
    ) {
        this.provider = provider;
        this.properties = properties;
    }

    public StagedTradeBill download(ResolvedPaymentConfig config, LocalDate billDate)
            throws IOException {
        Path path = Files.createTempFile("shop-wechat-trade-bill-", ".csv");
        boolean completed = false;
        try {
            MessageDigest sha256 = sha256();
            long sizeBytes;
            boolean providerHashVerified;
            try (WechatTradeBillDownload download = provider.openTradeBill(config, billDate);
                    InputStream input = new BufferedInputStream(download.inputStream());
                    OutputStream output = new DigestOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(path)), sha256)) {
                sizeBytes = copyWithLimit(input, output, properties.maxSourceSize().toBytes());
                output.flush();
                providerHashVerified = download.verifyProviderHash();
            }
            if (!providerHashVerified) {
                throw new IOException("WeChat trade bill provider hash verification failed");
            }
            if (Files.size(path) != sizeBytes) {
                throw new IOException("WeChat trade bill staged size changed unexpectedly");
            }
            completed = true;
            return new StagedTradeBill(
                    path,
                    sizeBytes,
                    HexFormat.of().formatHex(sha256.digest()),
                    true
            );
        } finally {
            if (!completed) {
                Files.deleteIfExists(path);
            }
        }
    }

    private long copyWithLimit(InputStream input, OutputStream output, long maxBytes)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > maxBytes - read) {
                throw new IOException("WeChat trade bill exceeds configured decompressed size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
