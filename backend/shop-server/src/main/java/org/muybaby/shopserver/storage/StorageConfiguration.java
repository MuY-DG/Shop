package org.muybaby.shopserver.storage;

import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.provider.RoutingStorageProvider;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.service.StorageObjectKeyGenerator;
import org.muybaby.shopserver.storage.service.UploadPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfiguration {

    @Bean
    StorageProvider storageProvider(StorageRuntimeConfigService storageRuntimeConfigService) {
        return new RoutingStorageProvider(storageRuntimeConfigService);
    }

    @Bean
    UploadPolicy uploadPolicy(StorageProperties storageProperties) {
        return new UploadPolicy(storageProperties);
    }

    @Bean
    StorageObjectKeyGenerator storageObjectKeyGenerator() {
        return new StorageObjectKeyGenerator();
    }
}
