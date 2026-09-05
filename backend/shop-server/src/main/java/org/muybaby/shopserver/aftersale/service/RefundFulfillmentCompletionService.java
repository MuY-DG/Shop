package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.logistics.service.WechatShippingUploadStateStore;
import org.muybaby.shopserver.order.repository.OrderItemFulfillmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class RefundFulfillmentCompletionService {

    private final JdbcClient jdbcClient;
    private final OrderItemFulfillmentRepository fulfillmentRepository;
    private final WechatShippingUploadStateStore uploadStateStore;

    public RefundFulfillmentCompletionService(
            JdbcClient jdbcClient,
            OrderItemFulfillmentRepository fulfillmentRepository,
            WechatShippingUploadStateStore uploadStateStore
    ) {
        this.jdbcClient = jdbcClient;
        this.fulfillmentRepository = fulfillmentRepository;
        this.uploadStateStore = uploadStateStore;
    }

    /** Called after successful item refunds, while the refund transaction owns the order lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LocalDateTime> markFinalShipmentIfFulfilled(long orderId, LocalDateTime now) {
        if (!fulfillmentRepository.isFullyShipped(orderId)) {
            return Optional.empty();
        }
        List<ShippedPackage> shipments = jdbcClient.sql("""
                        select id, shipped_at from order_shipment
                        where order_id = :orderId and status = 'SHIPPED' and shipped_at is not null
                        order by package_no desc, id desc
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ShippedPackage(
                        rs.getLong("id"), rs.getObject("shipped_at", LocalDateTime.class)))
                .list();
        if (shipments.isEmpty()) {
            return Optional.empty();
        }
        uploadStateStore.requestFinalShipmentRefresh(orderId, shipments.getFirst().id(), now);
        return shipments.stream().map(ShippedPackage::shippedAt).max(LocalDateTime::compareTo);
    }

    private record ShippedPackage(long id, LocalDateTime shippedAt) {
    }
}
