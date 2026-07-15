package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AfterSaleFulfillmentPolicy {

    private static final List<String> BLOCKING_STATUSES = List.of(
            AfterSaleStatus.REQUESTED.name(),
            AfterSaleStatus.APPROVED.name(),
            AfterSaleStatus.REFUNDING.name(),
            AfterSaleStatus.REFUND_FAILED.name()
    );

    private final JdbcClient jdbcClient;

    public AfterSaleFulfillmentPolicy(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<String> blockingStatuses() {
        return BLOCKING_STATUSES;
    }

    public Optional<BlockingAfterSale> findBlocking(long orderId) {
        return jdbcClient.sql("""
                        select id,
                               after_sale_type,
                               status,
                               requested_amount_cent,
                               created_at
                        from after_sale_request
                        where order_id = :orderId
                          and status in (:statuses)
                        order by created_at desc, id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .param("statuses", BLOCKING_STATUSES)
                .query((rs, rowNum) -> new BlockingAfterSale(
                        rs.getLong("id"),
                        rs.getString("after_sale_type"),
                        rs.getString("status"),
                        rs.getLong("requested_amount_cent"),
                        rs.getObject("created_at", LocalDateTime.class)
                ))
                .optional();
    }

    public void rejectIfBlocked(long orderId) {
        if (findBlocking(orderId).isPresent()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    public record BlockingAfterSale(
            Long afterSaleId,
            String afterSaleType,
            String status,
            Long requestedAmountCent,
            LocalDateTime createdAt
    ) {
    }
}
