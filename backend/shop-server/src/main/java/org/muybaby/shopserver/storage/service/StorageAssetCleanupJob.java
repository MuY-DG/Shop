package org.muybaby.shopserver.storage.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StorageAssetCleanupJob {

    private final StorageAssetCleanupService cleanupService;

    public StorageAssetCleanupJob(StorageAssetCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(
            fixedDelayString = "${shop.storage.cleanup.fixed-delay:10m}",
            initialDelayString = "${shop.storage.cleanup.initial-delay:10m}"
    )
    public void cleanupExpiredAssets() {
        cleanupService.cleanupExpiredAssets();
    }
}
