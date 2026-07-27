package org.muybaby.shopserver.auth.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "shop.auth.admin-session-reconciliation.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AdminSessionLimitReconciliationJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdminSessionLimitReconciliationJob.class);

    private final AdminSessionLimitReconciler reconciler;

    public AdminSessionLimitReconciliationJob(AdminSessionLimitReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(
            initialDelayString =
                    "${shop.auth.admin-session-reconciliation.initial-delay-ms:30000}",
            fixedDelayString =
                    "${shop.auth.admin-session-reconciliation.fixed-delay-ms:60000}"
    )
    public void reconcileSessionLimits() {
        List<Long> userIds;
        try {
            userIds = reconciler.limitedUserIds();
        } catch (RuntimeException ex) {
            LOGGER.error("Failed to load admin session limits for reconciliation", ex);
            return;
        }

        for (Long userId : userIds) {
            try {
                reconciler.reconcileUser(userId);
            } catch (RuntimeException ex) {
                LOGGER.error("Failed to reconcile admin session limit for user {}", userId, ex);
            }
        }
    }
}
