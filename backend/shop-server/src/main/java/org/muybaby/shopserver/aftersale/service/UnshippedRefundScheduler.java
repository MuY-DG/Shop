package org.muybaby.shopserver.aftersale.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** The queued audit event is persisted with the application, so restart cannot lose the work. */
@Component
@ConditionalOnProperty(name = "shop.pay.auto-refund.enabled", havingValue = "true", matchIfMissing = true)
public class UnshippedRefundScheduler {
    private static final Logger log = LoggerFactory.getLogger(UnshippedRefundScheduler.class);
    private final JdbcClient jdbcClient;
    private final AdminAfterSaleService refundService;
    private final AfterSaleStatusLogService statusLog;
    private final TransactionTemplate transaction;

    public UnshippedRefundScheduler(JdbcClient jdbcClient, AdminAfterSaleService refundService,
                                   AfterSaleStatusLogService statusLog, PlatformTransactionManager manager) {
        this.jdbcClient = jdbcClient;
        this.refundService = refundService;
        this.statusLog = statusLog;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${shop.pay.auto-refund.delay:5s}",
            initialDelayString = "${shop.pay.auto-refund.initial-delay:5s}")
    public void runOnce() {
        var ids = jdbcClient.sql("""
                        select r.id from after_sale_request r
                        where r.status = 'REQUESTED'
                          and exists (select 1 from after_sale_status_log l where l.after_sale_id = r.id
                                      and l.event_type = 'AUTO_REFUND_QUEUED')
                          and not exists (select 1 from after_sale_status_log l where l.after_sale_id = r.id
                                          and l.event_type = 'AUTO_REFUND_REVIEW_REQUIRED')
                        order by r.id limit 20
                        """).query(Long.class).list();
        for (long id : ids) {
            try {
                refundService.approveUnshippedAutomatically(id);
            } catch (RuntimeException ex) {
                // Submitted/uncertain refunds are already durable and owned by refund recovery.
                // A failure before submission leaves an auditable request for merchant review.
                transaction.executeWithoutResult(status -> {
                    String current = jdbcClient.sql("select status from after_sale_request where id = :id for update")
                            .param("id", id).query(String.class).single();
                    if ("REQUESTED".equals(current)) {
                        statusLog.record(id, current, current, "AUTO_REFUND_REVIEW_REQUIRED", "SYSTEM", null,
                                "自动退款未能完成，请商家核查后处理", LocalDateTime.now(ZoneOffset.UTC));
                    }
                });
                log.warn("Automatic refund needs follow-up (afterSaleId={}, type={})", id, ex.getClass().getSimpleName());
            }
        }
    }
}
