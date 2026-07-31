package org.muybaby.shopserver.storage;

import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.provider.InMemoryStorageProvider;
import org.muybaby.shopserver.storage.provider.RoutingStorageProvider;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.service.StorageObjectKeyGenerator;
import org.muybaby.shopserver.storage.service.UploadPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class StorageConfiguration {

    @Bean
    @Profile("!test")
    StorageProvider cosStorageProvider(StorageRuntimeConfigService storageRuntimeConfigService) {
        return new RoutingStorageProvider(storageRuntimeConfigService);
    }

    @Bean
    @Profile("test")
    StorageProvider testStorageProvider() {
        return new InMemoryStorageProvider();
    }

    @Bean
    UploadPolicy uploadPolicy(StorageProperties storageProperties) {
        return new UploadPolicy(storageProperties);
    }

    @Bean
    StorageObjectKeyGenerator storageObjectKeyGenerator() {
        return new StorageObjectKeyGenerator();
    }

    @Bean("customerServiceThumbnailExecutor")
    @Profile("!test")
    TaskExecutor customerServiceThumbnailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("customer-service-thumbnail-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean("customerServiceThumbnailExecutor")
    @Profile("test")
    TaskExecutor testCustomerServiceThumbnailExecutor() {
        return new SyncTaskExecutor();
    }
}
