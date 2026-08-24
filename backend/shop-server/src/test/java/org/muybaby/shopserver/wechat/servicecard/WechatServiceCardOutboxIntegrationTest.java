package org.muybaby.shopserver.wechat.servicecard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "shop.wechat.service-card-2001.enabled=true",
        "shop.wechat.service-card-2001.worker-enabled=false",
        "shop.wechat.service-card-2001.account-template-record-id=template-record",
        "shop.wechat.service-card-2001.fallback-product-image=https://admin.junxiangshiping.cn/wechat/service-card-placeholder.png",
        "shop.wechat.service-card-2001.allowed-image-hosts=admin.junxiangshiping.cn"
})
@ActiveProfiles("test")
class WechatServiceCardOutboxIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(9_400_000L);

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    OrderStatusLogService orderStatusLogService;

    @Autowired
    WechatServiceCardOutboxHook outboxHook;

    @Autowired
    WechatServiceCardRepairUnit repairUnit;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void afterSaleCountEdgesEnqueueSevenOnceAndRestorePreviousState() {
        long orderId = paidOrder(true, "PAID");
        LocalDateTime now = now();
        transactionTemplate.executeWithoutResult(status -> outboxHook.onOrderFact(orderId, now));
        long afterSaleId = IDS.incrementAndGet();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, after_sale_no, order_id, user_id, after_sale_type, status, reason,
                             requested_amount_cent, source_order_status, created_at, updated_at)
                        values
                            (:id, :afterSaleNo, :orderId, 1, 'REFUND_ONLY', 'REQUESTED', 'test',
                             100, 'PAID', :now, :now)
                        """)
                .param("id", afterSaleId)
                .param("afterSaleNo", "AS" + afterSaleId)
                .param("orderId", orderId)
                .param("now", now)
                .update();

        transactionTemplate.executeWithoutResult(status -> outboxHook.onAfterSaleFact(afterSaleId, now));
        transactionTemplate.executeWithoutResult(status -> outboxHook.onAfterSaleFact(afterSaleId, now));
        assertThat(targets(orderId)).containsExactly(2, 7);

        jdbcClient.sql("""
                        update after_sale_request set status = 'REFUND_FAILED', updated_at = :now
                        where id = :id
                        """)
                .param("now", now.plusSeconds(1)).param("id", afterSaleId).update();
        transactionTemplate.executeWithoutResult(
                status -> outboxHook.onAfterSaleFact(afterSaleId, now.plusSeconds(1))
        );
        assertThat(targets(orderId)).containsExactly(2, 7);

        jdbcClient.sql("""
                        update after_sale_request set status = 'CANCELLED', updated_at = :now
                        where id = :id
                        """)
                .param("now", now.plusSeconds(2)).param("id", afterSaleId).update();
        repairUnit.repair(orderId, now.plusSeconds(2));
        assertThat(targets(orderId)).containsExactly(2, 7, 2);
    }

    @Test
    void repairConvergesMissedShipmentReceiptAndFullRefundFacts() {
        long orderId = paidOrder(true, "PAID");
        LocalDateTime now = now();
        repairUnit.repair(orderId, now);

        jdbcClient.sql("""
                        update shop_order set status = 'SHIPPED', shipped_at = :time, updated_at = :time
                        where id = :orderId
                        """)
                .param("time", now.plusSeconds(1)).param("orderId", orderId).update();
        repairUnit.repair(orderId, now.plusSeconds(1));
        assertThat(targets(orderId)).containsExactly(2, 4);

        jdbcClient.sql("""
                        update shop_order set status = 'COMPLETED', completed_at = :time, updated_at = :time
                        where id = :orderId
                        """)
                .param("time", now.plusSeconds(2)).param("orderId", orderId).update();
        repairUnit.repair(orderId, now.plusSeconds(2));
        assertThat(targets(orderId)).containsExactly(2, 4, 6);

        long afterSaleId = IDS.incrementAndGet();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, after_sale_no, order_id, user_id, after_sale_type, status, reason,
                             requested_amount_cent, source_order_status, created_at, updated_at)
                        values
                            (:id, :afterSaleNo, :orderId, 1, 'REFUND_ONLY', 'REQUESTED', 'test',
                             100, 'COMPLETED', :time, :time)
                        """)
                .param("id", afterSaleId).param("afterSaleNo", "AS" + afterSaleId)
                .param("orderId", orderId)
                .param("time", now.plusSeconds(3)).update();
        repairUnit.repair(orderId, now.plusSeconds(3));
        assertThat(targets(orderId)).containsExactly(2, 4, 6, 7);

        jdbcClient.sql("""
                        update after_sale_request set status = 'REFUNDED', updated_at = :time where id = :id
                        """)
                .param("time", now.plusSeconds(4)).param("id", afterSaleId).update();
        jdbcClient.sql("""
                        update shop_order
                        set status = 'REFUNDED', refunded_amount_cent = paid_amount_cent,
                            refunded_at = :time, updated_at = :time
                        where id = :orderId
                        """)
                .param("time", now.plusSeconds(4)).param("orderId", orderId).update();
        repairUnit.repair(orderId, now.plusSeconds(4));
        assertThat(targets(orderId)).containsExactly(2, 4, 6, 7, 9);
    }

    @Test
    void nestedFailureCommitsCoreLogRollsBackPartialCardAndRepairLaterBackfills() {
        long orderId = paidOrder(false, "PAID");
        LocalDateTime now = now();

        transactionTemplate.executeWithoutResult(status -> orderStatusLogService.record(
                orderId, "PAYING", "PAID", "PAYMENT_SUCCEEDED",
                "WECHAT", null, "test", now
        ));

        assertThat(jdbcClient.sql(
                        "select count(*) from order_status_log where order_id = :orderId")
                .param("orderId", orderId).query(Long.class).single()).isOne();
        assertThat(cardCount(orderId)).isZero();

        insertItem(orderId);
        repairUnit.repair(orderId, now.plusSeconds(1));

        assertThat(cardCount(orderId)).isOne();
        assertThat(targets(orderId)).containsExactly(2);
    }

    @Test
    void repairDoesNotCreateAnActivationAfterTheWechatTwentyFourHourWindow() {
        long orderId = paidOrder(true, "PAID");
        LocalDateTime eventTime = now();
        jdbcClient.sql("""
                        update payment_order
                        set paid_at = :paidAt, updated_at = :paidAt
                        where order_id = :orderId
                        """)
                .param("paidAt", eventTime.minusHours(25))
                .param("orderId", orderId)
                .update();

        repairUnit.repair(orderId, eventTime);
        repairUnit.repair(orderId, eventTime.plusMinutes(5));

        assertThat(cardCount(orderId)).isZero();
        assertThat(targets(orderId)).isEmpty();
    }

    @Test
    void activationPayloadUsesSnapshotFieldsSafePathsAndJsonCheck() throws Exception {
        long orderId = paidOrder(true, "PAID");
        transactionTemplate.executeWithoutResult(status -> outboxHook.onOrderFact(orderId, now()));

        PayloadRow payload = jdbcClient.sql("""
                        select delivery.content_json, delivery.check_json
                        from wechat_service_card_delivery delivery
                        join wechat_service_card card on card.id = delivery.card_id
                        where card.order_id = :orderId and delivery.sequence_no = 1
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new PayloadRow(
                        rs.getString("content_json"), rs.getString("check_json")
                ))
                .single();
        JsonNode content = objectMapper.readTree(payload.contentJson());
        JsonNode check = objectMapper.readTree(payload.checkJson());

        assertThat(content.path("wxa_path_query").asText())
                .isEqualTo("pages/order/detail/detail?order_id=" + orderId)
                .doesNotStartWith("/");
        JsonNode product = content.path("product_list").path("info_list").get(0);
        assertThat(product.path("product_name").asText()).isEqualTo("测试商品");
        assertThat(product.path("product_path_query").asText())
                .isEqualTo("pages/product/detail/detail?id=123")
                .doesNotStartWith("/");
        assertThat(product.path("product_img").asText())
                .isEqualTo("https://admin.junxiangshiping.cn/wechat/service-card-placeholder.png");
        assertThat(check.path("pay_amount").longValue()).isEqualTo(100L);
        assertThat(check.path("pay_time").longValue()).isPositive();
    }

    @Test
    void productCountCoversWholeOrderWhileDisclosureListIsCappedAtTenSnapshots() throws Exception {
        long orderId = paidOrder(false, "PAID");
        for (int index = 1; index <= 11; index++) {
            jdbcClient.sql("""
                            insert into order_item
                                (order_id, sku_id, spu_id, product_title, sku_code,
                                 unit_price_cent, quantity, line_amount_cent)
                            values
                                (:orderId, :skuId, :spuId, :title, :skuCode, 10, 2, 20)
                            """)
                    .param("orderId", orderId)
                    .param("skuId", 1_000L + index)
                    .param("spuId", 2_000L + index)
                    .param("title", "快照商品" + index)
                    .param("skuCode", "SNAPSHOT-" + index)
                    .update();
        }

        transactionTemplate.executeWithoutResult(status -> outboxHook.onOrderFact(orderId, now()));

        String contentJson = jdbcClient.sql("""
                        select delivery.content_json
                        from wechat_service_card_delivery delivery
                        join wechat_service_card card on card.id = delivery.card_id
                        where card.order_id = :orderId and delivery.sequence_no = 1
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
        JsonNode content = objectMapper.readTree(contentJson);
        assertThat(content.path("product_count").longValue()).isEqualTo(22L);
        assertThat(content.path("product_list").path("info_list").size()).isEqualTo(10);
    }

    private long paidOrder(boolean withItem, String status) {
        long orderId = IDS.incrementAndGet();
        long paymentId = IDS.incrementAndGet();
        LocalDateTime paidAt = now().minusMinutes(1);
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             payable_amount_cent, paid_amount_cent, paid_at, created_at, updated_at)
                        values
                            (:id, :orderNo, 1, :status, 'DIRECT', :key,
                             100, 100, :paidAt, :paidAt, :paidAt)
                        """)
                .param("id", orderId)
                .param("orderNo", "SC" + orderId)
                .param("status", status)
                .param("key", "service-card-" + orderId)
                .param("paidAt", paidAt)
                .update();
        if (withItem) {
            insertItem(orderId);
        }
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, out_trade_no, transaction_id, payer_openid,
                             status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values
                            (:id, :orderId, :outTradeNo, :transactionId, :openid,
                             'PAID', 100, :expiresAt, :paidAt, :paidAt, :paidAt)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("outTradeNo", "OUT" + orderId)
                .param("transactionId", "4200" + orderId)
                .param("openid", "openid-" + orderId)
                .param("expiresAt", paidAt.plusMinutes(15))
                .param("paidAt", paidAt)
                .update();
        return orderId;
    }

    private void insertItem(long orderId) {
        jdbcClient.sql("""
                        insert into order_item
                            (order_id, sku_id, spu_id, product_title, sku_code,
                             unit_price_cent, quantity, line_amount_cent)
                        values (:orderId, 456, 123, '测试商品', 'SKU-TEST', 100, 1, 100)
                        """)
                .param("orderId", orderId)
                .update();
    }

    private List<Integer> targets(long orderId) {
        return jdbcClient.sql("""
                        select delivery.target_status
                        from wechat_service_card_delivery delivery
                        join wechat_service_card card on card.id = delivery.card_id
                        where card.order_id = :orderId
                        order by delivery.sequence_no
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .list();
    }

    private long cardCount(long orderId) {
        return jdbcClient.sql("select count(*) from wechat_service_card where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record PayloadRow(String contentJson, String checkJson) {
    }
}
