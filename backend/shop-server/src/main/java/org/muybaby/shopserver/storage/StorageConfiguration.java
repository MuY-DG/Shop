package org.muybaby.shopserver.storage;

import org.muybaby.shopserver.storage.provider.LocalStorageProvider;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.service.StorageObjectKeyGenerator;
import org.muybaby.shopserver.storage.service.UploadPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class StorageConfiguration {

    @Bean
    StorageProvider storageProvider(StorageProperties storageProperties) {
        return new LocalStorageProvider(Path.of(storageProperties.local().root()));
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
