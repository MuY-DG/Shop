package org.muybaby.shopserver.storage.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("!test")
@ConditionalOnProperty(
        name = "shop.storage.public-image-variants.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PublicImageVariantJob {
    private final PublicImageVariantService service;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public PublicImageVariantJob(
            PublicImageVariantService service,
            @Qualifier("publicImageVariantExecutor") TaskExecutor executor
    ) {
        this.service = service;
        this.executor = executor;
    }

    @Scheduled(
            fixedDelayString = "${shop.storage.public-image-variants.fixed-delay:30s}",
            initialDelayString = "${shop.storage.public-image-variants.initial-delay:30s}"
    )
    public void backfill() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    service.backfillBatch(2);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }
}
