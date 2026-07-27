package org.muybaby.shopserver.auth.session;

import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AdminSessionPolicyChangedListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminSessionPolicyChangedListener.class);

    private final OpaqueTokenService opaqueTokenService;
    private final AdminSessionLimitReconciler sessionLimitReconciler;

    public AdminSessionPolicyChangedListener(
            OpaqueTokenService opaqueTokenService,
            AdminSessionLimitReconciler sessionLimitReconciler
    ) {
        this.opaqueTokenService = opaqueTokenService;
        this.sessionLimitReconciler = sessionLimitReconciler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPolicyChanged(AdminSessionPolicyChangedEvent event) {
        try {
            if (event.revokeAll()) {
                opaqueTokenService.revokeSubjectSessions(TokenKind.ADMIN, event.userId());
            } else {
                sessionLimitReconciler.reconcileUser(event.userId());
            }
        } catch (RuntimeException ex) {
            // auth_version is the fail-safe for revoke-all events. Limit enforcement is
            // retried from the durable database policy by AdminSessionLimitReconciliationJob.
            LOGGER.error("Failed to clean admin sessions after policy change for user {}", event.userId(), ex);
        }
    }
}
