package org.muybaby.shopserver.customerservice.service;

import org.muybaby.shopserver.customerservice.CustomerServiceImageThumbnailRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CustomerServiceImageThumbnailListener {

    private static final Logger log =
            LoggerFactory.getLogger(CustomerServiceImageThumbnailListener.class);

    private final TaskExecutor taskExecutor;
    private final CustomerServiceImageThumbnailService thumbnailService;

    public CustomerServiceImageThumbnailListener(
            @Qualifier("customerServiceThumbnailExecutor") TaskExecutor taskExecutor,
            CustomerServiceImageThumbnailService thumbnailService
    ) {
        this.taskExecutor = taskExecutor;
        this.thumbnailService = thumbnailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onThumbnailRequested(CustomerServiceImageThumbnailRequestedEvent event) {
        try {
            taskExecutor.execute(() -> thumbnailService.generate(event.assetId()));
        } catch (TaskRejectedException ex) {
            log.warn(
                    "Customer-service thumbnail task queue is full; scheduled recovery will retry: assetId={}",
                    event.assetId()
            );
        }
    }
}
