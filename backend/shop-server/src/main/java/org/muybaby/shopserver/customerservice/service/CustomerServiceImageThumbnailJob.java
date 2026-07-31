package org.muybaby.shopserver.customerservice.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CustomerServiceImageThumbnailJob {

    private final CustomerServiceImageThumbnailService thumbnailService;

    public CustomerServiceImageThumbnailJob(
            CustomerServiceImageThumbnailService thumbnailService
    ) {
        this.thumbnailService = thumbnailService;
    }

    @Scheduled(
            fixedDelayString = "${shop.storage.customer-service-thumbnail.fixed-delay:30s}",
            initialDelayString = "${shop.storage.customer-service-thumbnail.initial-delay:10s}"
    )
    public void generatePendingThumbnails() {
        thumbnailService.processPendingThumbnails();
    }
}
