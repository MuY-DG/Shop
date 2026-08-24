package org.muybaby.shopserver.finance.reconciliation.download;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.provider.WechatTradeBillDownload;
import org.muybaby.shopserver.finance.reconciliation.provider.WechatTradeBillProvider;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeBillDownloadServiceTest {

    @Test
    void verifiesProviderHashOnlyAfterReadingToEofAndCalculatesLocalSha256() throws Exception {
        TrackingDownload download = new TrackingDownload("verified-source", true);
        TradeBillDownloadService service = service(download, DataSize.ofKilobytes(1));

        try (StagedTradeBill staged = service.download(null, LocalDate.of(2026, 8, 1))) {
            assertThat(Files.readString(staged.path())).isEqualTo("verified-source");
            assertThat(staged.sizeBytes()).isEqualTo(15L);
            assertThat(staged.contentSha256())
                    .isEqualTo("d960d85bf2c638a894aab1dff95493aaba1e3c8ab99cb49ae27397104724ae69");
            assertThat(download.verifyCalls).isOne();
            assertThat(download.verifiedAtEof).isTrue();
        }
        assertThat(download.closed).isTrue();
    }

    @Test
    void sizeLimitInterruptNeverAcceptsProviderHash() {
        TrackingDownload download = new TrackingDownload("too-large", true);
        TradeBillDownloadService service = service(download, DataSize.ofBytes(3));

        assertThatThrownBy(() -> service.download(null, LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("decompressed size limit");
        assertThat(download.verifyCalls).isZero();
        assertThat(download.closed).isTrue();
    }

    @Test
    void providerHashMismatchFailsClosed() {
        TrackingDownload download = new TrackingDownload("bad-hash", false);
        TradeBillDownloadService service = service(download, DataSize.ofKilobytes(1));

        assertThatThrownBy(() -> service.download(null, LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("provider hash verification failed");
        assertThat(download.verifyCalls).isOne();
        assertThat(download.verifiedAtEof).isTrue();
        assertThat(download.closed).isTrue();
    }

    private TradeBillDownloadService service(TrackingDownload download, DataSize limit) {
        WechatTradeBillProvider provider = (config, date) -> download;
        return new TradeBillDownloadService(provider, new FinanceReconciliationProperties(
                null, null, null, null, null,
                8, 90, limit, 200_000, 4_096, 31, 50_000));
    }

    private static final class TrackingDownload implements WechatTradeBillDownload {

        private final TrackingInputStream input;
        private final boolean hashResult;
        private int verifyCalls;
        private boolean verifiedAtEof;
        private boolean closed;

        private TrackingDownload(String content, boolean hashResult) {
            this.input = new TrackingInputStream(content.getBytes(StandardCharsets.UTF_8));
            this.hashResult = hashResult;
        }

        @Override
        public InputStream inputStream() {
            return input;
        }

        @Override
        public boolean verifyProviderHash() {
            verifyCalls++;
            verifiedAtEof = input.eof;
            return hashResult && verifiedAtEof;
        }

        @Override
        public void close() throws IOException {
            input.close();
            closed = true;
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean eof;

        private TrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int read = super.read(buffer, offset, length);
            if (read < 0) {
                eof = true;
            }
            return read;
        }

        @Override
        public synchronized int read() {
            int read = super.read();
            if (read < 0) {
                eof = true;
            }
            return read;
        }
    }
}
