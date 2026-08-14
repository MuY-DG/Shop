package org.muybaby.shopserver.payment.config;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PaymentConfigMutationLock {

    private static final String CHECKPOINT_NAME = "payment-config";

    private final JdbcClient jdbcClient;

    public PaymentConfigMutationLock(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment config mutation lock requires an active transaction");
        }
        String checkpointName = jdbcClient.sql("""
                        select checkpoint_name
                        from payment_secret_rotation_checkpoint
                        where checkpoint_name = :checkpointName
                        for update
                        """)
                .param("checkpointName", CHECKPOINT_NAME)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing payment secret rotation checkpoint: " + CHECKPOINT_NAME));
        if (!CHECKPOINT_NAME.equals(checkpointName)) {
            throw new IllegalStateException("Invalid payment secret rotation checkpoint");
        }
    }
}
