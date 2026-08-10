package org.muybaby.shopserver.aftersale.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AfterSaleStatusLogService {

    private final JdbcClient jdbcClient;

    public AfterSaleStatusLogService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void record(
            long afterSaleId,
            String fromStatus,
            String toStatus,
            String eventType,
            String operatorType,
            Long operatorId,
            String description,
            LocalDateTime createdAt
    ) {
        jdbcClient.sql("""
                        insert into after_sale_status_log (
                            after_sale_id, from_status, to_status, event_type,
                            operator_type, operator_id, description, created_at
                        ) values (
                            :afterSaleId, :fromStatus, :toStatus, :eventType,
                            :operatorType, :operatorId, :description, :createdAt
                        )
                        """)
                .param("afterSaleId", afterSaleId)
                .param("fromStatus", empty(fromStatus))
                .param("toStatus", toStatus)
                .param("eventType", eventType)
                .param("operatorType", operatorType)
                .param("operatorId", operatorId)
                .param("description", truncate(description))
                .param("createdAt", createdAt)
                .update();
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value) {
        String normalized = empty(value);
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }
}
