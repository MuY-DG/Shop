package org.muybaby.shopserver.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "shop.storage")
public record StorageProperties(
        StorageProviderKind provider,
        String publicBaseUrl,
        Local local,
        TencentCos tencentCos,
        Limits limits
) {
    public record Local(String root) {
    }

    public record TencentCos(
            String region,
            String bucket,
            String secretId,
            String secretKey,
            String publicBaseUrl
    ) {
    }

    public record Limits(
            DataSize imageMaxSize,
            DataSize videoMaxSize,
            DataSize privateFileMaxSize
    ) {
        @ConstructorBinding
        public Limits {
        }

        public Limits(DataSize imageMaxSize, DataSize privateFileMaxSize) {
            this(imageMaxSize, DataSize.ofMegabytes(50), privateFileMaxSize);
        }
    }
}
