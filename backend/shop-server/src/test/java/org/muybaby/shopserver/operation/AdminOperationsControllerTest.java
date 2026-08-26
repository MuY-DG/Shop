package org.muybaby.shopserver.operation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.Granularity;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ReportQuery;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TrendPoint;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TrendSeries;
import org.muybaby.shopserver.operation.service.OperationsStatisticsService;
import org.muybaby.shopserver.operation.query.CommerceTrendQueryRepository;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminOperationsControllerTest {

    private static final Map<String, String> ENDPOINT_PERMISSIONS = Map.of(
            "/admin/operations/overview", "operation:overview:read",
            "/admin/operations/trade-statistics", "operation:trade:read",
            "/admin/operations/product-statistics", "operation:product:read",
            "/admin/operations/user-statistics", "operation:user:read",
            "/admin/operations/traffic-statistics", "operation:traffic:read",
            "/admin/operations/marketing-statistics", "operation:marketing:read",
            "/admin/operations/service-statistics", "operation:service:read"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private OperationsStatisticsService operationsStatisticsService;

    @Autowired
    private CommerceTrendQueryRepository commerceTrendQueryRepository;

    @BeforeEach
    void clearStatisticsFacts() {
        jdbcClient.sql("delete from analytics_event").update();
        jdbcClient.sql("delete from app_user_daily_activity").update();
        jdbcClient.sql("delete from payment_attempt").update();
        jdbcClient.sql("delete from refund_order").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_template").update();
        jdbcClient.sql("delete from customer_service_message").update();
        jdbcClient.sql("delete from customer_service_assignment_log").update();
        jdbcClient.sql("delete from customer_service_conversation").update();
        jdbcClient.sql("delete from product_sku").update();
        jdbcClient.sql("delete from product_spu").update();
        jdbcClient.sql("delete from product_category").update();
        jdbcClient.sql("delete from app_user").update();
    }

    @Test
    void allStatisticsEndpointsRequireTheirOwnPermission() throws Exception {
        mockMvc.perform(get("/admin/operations/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        for (Map.Entry<String, String> endpoint : ENDPOINT_PERMISSIONS.entrySet()) {
            String wrongPermission = endpoint.getValue().equals("operation:overview:read")
                    ? "operation:trade:read"
                    : "operation:overview:read";
            String deniedToken = token(wrongPermission);
            mockMvc.perform(get(endpoint.getKey())
                            .header("Authorization", "Bearer " + deniedToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(100003));

            String allowedToken = token(endpoint.getValue());
            mockMvc.perform(get(endpoint.getKey())
                            .header("Authorization", "Bearer " + allowedToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.meta.timezone").value("Asia/Shanghai"));
        }
    }

    @Test
    void reportQueryUsesInclusiveShanghaiDatesAndRejectsInvalidRanges() throws Exception {
        String token = token("operation:overview:read");

        mockMvc.perform(get("/admin/operations/overview")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-07")
                        .param("granularity", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.range.startDate").value("2026-01-01"))
                .andExpect(jsonPath("$.data.meta.range.endDate").value("2026-01-07"))
                .andExpect(jsonPath("$.data.meta.comparisonRange.startDate").value("2025-12-25"))
                .andExpect(jsonPath("$.data.meta.comparisonRange.endDate").value("2025-12-31"))
                .andExpect(jsonPath("$.data.meta.granularity").value("DAY"));

        mockMvc.perform(get("/admin/operations/overview")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-01-01")
                        .param("endDate", "2026-01-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(get("/admin/operations/overview")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    @Test
    void overviewTradeProductAndUserStatisticsUseBusinessFactTimes() throws Exception {
        seedBusinessFacts();

        mockMvc.perform(get("/admin/operations/overview")
                        .header("Authorization", "Bearer " + token("operation:overview:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07")
                        .param("granularity", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trade.paidAmountCent.value").value(34500))
                .andExpect(jsonPath("$.data.trade.refundAmountCent.value").value(20000))
                .andExpect(jsonPath("$.data.trade.netReceiptAmountCent.value").value(14500))
                .andExpect(jsonPath("$.data.trade.paidOrderCount.value").value(3))
                .andExpect(jsonPath("$.data.trade.paidBuyerCount.value").value(2))
                .andExpect(jsonPath("$.data.users.newUserCount.value").value(2))
                .andExpect(jsonPath("$.data.todos.data[?(@.key == 'unpaidOrders')].count").value(1))
                .andExpect(jsonPath("$.data.todos.data[?(@.key == 'pendingAfterSales')].count").value(1));

        mockMvc.perform(get("/admin/operations/trade-statistics")
                        .header("Authorization", "Bearer " + token("operation:trade:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.createdOrderCount.value").value(4))
                .andExpect(jsonPath("$.data.summary.paidOrderCount.value").value(3))
                .andExpect(jsonPath("$.data.summary.paidAmountCent.value").value(34500))
                .andExpect(jsonPath("$.data.summary.successfulRefundAmountCent.value").value(20000))
                .andExpect(jsonPath("$.data.summary.orderPaymentConversionRate.value").value(7500))
                .andExpect(jsonPath("$.data.summary.createToPaySeconds.value").value(1800))
                .andExpect(jsonPath("$.data.summary.payToShipSeconds.value").value(84600))
                .andExpect(jsonPath("$.data.summary.shipToCompleteSeconds.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.shipToCompleteSeconds.availability").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.orderStatuses.data[?(@.key == 'paid')].label").value("待发货"))
                .andExpect(jsonPath("$.data.paymentStatuses.data[?(@.key == 'paid')].label").value("支付成功"))
                .andExpect(jsonPath("$.data.refundStatuses.data[?(@.key == 'success')].label").value("退款成功"));

        mockMvc.perform(get("/admin/operations/product-statistics")
                        .header("Authorization", "Bearer " + token("operation:product:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.soldQuantity.value").value(4))
                .andExpect(jsonPath("$.data.summary.paidItemAmountCent.value").value(35000))
                .andExpect(jsonPath("$.data.summary.costCoverageRate.value").value(8571))
                .andExpect(jsonPath("$.data.summary.grossProfitAmountCent.value").value(13500))
                .andExpect(jsonPath("$.data.summary.outOfStockSkuCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.lowStockSkuCount.value").value(0))
                .andExpect(jsonPath("$.data.summary.virtualSalesExcluded.value").value(1))
                .andExpect(jsonPath("$.data.topProducts.data[0].id").value("92001"));

        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token("operation:user:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalUserCount.value").value(3))
                .andExpect(jsonPath("$.data.summary.newUserCount.value").value(2))
                .andExpect(jsonPath("$.data.summary.activeUserCount.value").value(2))
                .andExpect(jsonPath("$.data.summary.paidBuyerCount.value").value(2))
                .andExpect(jsonPath("$.data.summary.repeatBuyerCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.repeatBuyerRate.value").value(5000));
    }

    @Test
    void statusLabelsAndCatalogAlertsUseTheirOwnBusinessContexts() throws Exception {
        seedBusinessFacts();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, payment_config_id, payment_config_fingerprint,
                             notification_route_token, out_trade_no, status, amount_cent,
                             expires_at, created_at, updated_at)
                        values
                            (96004, 94004, 9999001,
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             'dddddddddddddddddddddddddddddddd', 'OPS-PAY-FAILED', 'FAILED', 5000,
                             timestamp '2026-07-05 12:45:00', timestamp '2026-07-05 12:00:00',
                             timestamp '2026-07-05 12:30:00')
                        """).update();
        jdbcClient.sql("""
                        insert into refund_order
                            (id, after_sale_id, order_id, payment_order_id,
                             notification_route_token, out_refund_no,
                             refund_amount_cent, status, requested_at, created_at, updated_at)
                        values
                            (98002, 97002, 94003, 96003,
                             'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', 'OPS-REFUND-FAILED', 5000, 'FAILED',
                             timestamp '2026-07-07 11:00:00', timestamp '2026-07-07 11:00:00',
                             timestamp '2026-07-07 11:30:00')
                        """).update();

        mockMvc.perform(get("/admin/operations/trade-statistics")
                        .header("Authorization", "Bearer " + token("operation:trade:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatuses.data[?(@.key == 'paid')].label").value("待发货"))
                .andExpect(jsonPath("$.data.paymentStatuses.data[?(@.key == 'paid')].label").value("支付成功"))
                .andExpect(jsonPath("$.data.paymentStatuses.data[?(@.key == 'failed')].label").value("支付失败"))
                .andExpect(jsonPath("$.data.refundStatuses.data[?(@.key == 'success')].label").value("退款成功"))
                .andExpect(jsonPath("$.data.refundStatuses.data[?(@.key == 'failed')].label").value("退款失败"));

        mockMvc.perform(get("/admin/operations/product-statistics")
                        .header("Authorization", "Bearer " + token("operation:product:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.outOfStockSkuCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.lowStockSkuCount.value").value(0));
    }

    @Test
    void trafficMarketingAndServiceStatisticsExposeCollectedAndOperationalFacts() throws Exception {
        seedBusinessFacts();

        mockMvc.perform(get("/admin/operations/traffic-statistics")
                        .header("Authorization", "Bearer " + token("operation:traffic:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.collectionStartedAt").value("2026-07-02T08:00:00Z"))
                .andExpect(jsonPath("$.data.summary.pageViewCount.value").value(2))
                .andExpect(jsonPath("$.data.summary.visitorCount.value").value(2))
                .andExpect(jsonPath("$.data.summary.sessionCount.value").value(2))
                .andExpect(jsonPath("$.data.entryScenes.data[?(@.key == 'home')].value").value(1))
                .andExpect(jsonPath("$.data.entryScenes.data[?(@.key == 'search')].value").value(1))
                .andExpect(jsonPath("$.data.funnel.data[0].key").value("homeVisit"))
                .andExpect(jsonPath("$.data.funnel.data[0].users").value(2));

        mockMvc.perform(get("/admin/operations/marketing-statistics")
                        .header("Authorization", "Bearer " + token("operation:marketing:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.issuedCouponCount.value").value(2))
                .andExpect(jsonPath("$.data.summary.usedCouponCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.couponUsageRate.value").value(5000))
                .andExpect(jsonPath("$.data.summary.couponDiscountCent.value").value(500));

        mockMvc.perform(get("/admin/operations/service-statistics")
                        .header("Authorization", "Bearer " + token("operation:service:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.toShipOrderCount.value").value(2))
                .andExpect(jsonPath("$.data.summary.afterSaleApplicationCount.value").value(2))
                .andExpect(jsonPath("$.data.summary.successfulRefundCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.refundSuccessRate.value").value(10000))
                .andExpect(jsonPath("$.data.summary.averageRefundProcessingSeconds.value").value(90000))
                .andExpect(jsonPath("$.data.summary.waitingConversationCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.activeConversationCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.adminUnreadMessageCount.value").value(5))
                .andExpect(jsonPath("$.data.wechatShippingStatuses.data[?(@.key == 'failed')].label")
                        .value("上传失败"));
    }

    @Test
    void repeatBuyerIncludesAUserWhosePreviousPurchaseWasBeforeThePeriod() throws Exception {
        jdbcClient.sql("""
                        insert into app_user (id, openid, status, created_at, updated_at)
                        values (88001, 'operations-repeat-user', 'ENABLED',
                                timestamp '2026-06-01 08:00:00', timestamp '2026-06-01 08:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, idempotency_key, checkout_request_digest,
                             payable_amount_cent, paid_amount_cent, paid_at, created_at, updated_at)
                        values
                            (88011, 'OPS-REPEAT-OLD', 88001, 'COMPLETED', 'ops-repeat-old',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             1000, 1000, timestamp '2026-06-20 09:00:00',
                             timestamp '2026-06-20 08:55:00', timestamp '2026-06-20 09:00:00'),
                            (88012, 'OPS-REPEAT-CURRENT', 88001, 'PAID', 'ops-repeat-current',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             2000, 2000, timestamp '2026-07-02 09:00:00',
                             timestamp '2026-07-02 08:55:00', timestamp '2026-07-02 09:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into app_user_daily_activity
                            (user_id, activity_date, first_active_at)
                        values (88001, date '2026-06-20', timestamp '2026-06-20 08:00:00')
                        """).update();

        String token = token("operation:user:read");
        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.paidBuyerCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.firstPurchaseUserCount.value").value(0))
                .andExpect(jsonPath("$.data.summary.repeatBuyerCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.repeatBuyerRate.value").value(10000))
                .andExpect(jsonPath("$.data.summary.activeUserCount.value").value(0))
                .andExpect(jsonPath("$.data.summary.activeUserCount.availability").value("AVAILABLE"));

        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.activeUserCount.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.activeUserCount.availability").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.data.summary.phoneAuthorizedUserCount.availability").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.data.trend.availability").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].windows[?(@.dayOffset == 1)].retentionRateBasisPoints")
                        .doesNotExist());
    }

    @Test
    void retentionCohortsSeparatePreCollectionImmatureAndMatureZeroWindows() throws Exception {
        jdbcClient.sql("""
                        insert into app_user (id, openid, status, created_at, updated_at)
                        values
                            (88600, 'operations-retention-baseline', 'ENABLED',
                             timestamp '2025-11-01 08:00:00', timestamp '2025-11-01 08:00:00'),
                            (88601, 'operations-retention-a', 'ENABLED',
                             timestamp '2026-01-01 08:00:00', timestamp '2026-01-01 08:00:00'),
                            (88602, 'operations-retention-b', 'ENABLED',
                             timestamp '2026-01-01 09:00:00', timestamp '2026-01-01 09:00:00'),
                            (88603, 'operations-retention-zero', 'ENABLED',
                             timestamp '2026-01-02 08:00:00', timestamp '2026-01-02 08:00:00'),
                            (88604, 'operations-retention-before-collection', 'ENABLED',
                             timestamp '2025-12-01 08:00:00', timestamp '2025-12-01 08:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into app_user_daily_activity
                            (user_id, activity_date, first_active_at)
                        values
                            (88600, date '2026-01-01', timestamp '2026-01-01 00:00:00'),
                            (88601, date '2026-01-02', timestamp '2026-01-02 08:00:00'),
                            (88601, date '2026-01-08', timestamp '2026-01-08 08:00:00'),
                            (88601, date '2026-01-31', timestamp '2026-01-31 08:00:00'),
                            (88602, date '2026-01-02', timestamp '2026-01-02 09:00:00')
                        """).update();
        String token = token("operation:user:read");

        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-12-01")
                        .param("endDate", "2025-12-01")
                        .param("granularity", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retentionCohorts.availability").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].windows[0].retentionRateBasisPoints")
                        .doesNotExist());

        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-02")
                        .param("granularity", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retentionCohorts.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].cohort").value("2026-01-01"))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].registeredUserCount").value(2))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].windows[?(@.dayOffset == 1)].retainedUserCount")
                        .value(2))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].windows[?(@.dayOffset == 1)].retentionRateBasisPoints")
                        .value(10000))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].windows[?(@.dayOffset == 7)].retentionRateBasisPoints")
                        .value(5000))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].windows[?(@.dayOffset == 30)].retentionRateBasisPoints")
                        .value(5000))
                .andExpect(jsonPath("$.data.retentionCohorts.data[1].cohort").value("2026-01-02"))
                .andExpect(jsonPath("$.data.retentionCohorts.data[1].windows[?(@.dayOffset == 1)].retainedUserCount")
                        .value(0))
                .andExpect(jsonPath("$.data.retentionCohorts.data[1].windows[?(@.dayOffset == 1)].retentionRateBasisPoints")
                        .value(0));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        jdbcClient.sql("""
                        insert into app_user (id, openid, status, created_at, updated_at)
                        values (88605, 'operations-retention-immature', 'ENABLED', :createdAt, :createdAt)
                        """)
                .param("createdAt", today.atTime(12, 0))
                .update();
        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", today.toString())
                        .param("endDate", today.toString())
                        .param("granularity", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retentionCohorts.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].windows[0].eligibleUserCount").value(0))
                .andExpect(jsonPath("$.data.retentionCohorts.data[0].windows[0].retentionRateBasisPoints")
                        .doesNotExist());
    }

    @Test
    void phoneAuthorizationUsesItsCapturedBusinessTimeInsteadOfTheCurrentFlag() throws Exception {
        jdbcClient.sql("""
                        insert into app_user
                            (id, openid, phone_number, phone_authorized, phone_authorized_at,
                             status, created_at, updated_at)
                        values (88701, 'operations-phone-time', '13800008870', true,
                                timestamp '2026-07-02 09:00:00', 'ENABLED',
                                timestamp '2026-06-01 08:00:00', timestamp '2026-07-02 09:00:00')
                        """).update();
        String token = token("operation:user:read");

        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.phoneAuthorizedUserCount.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.phoneAuthorizedUserCount.availability").value("NOT_COLLECTED"));

        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.phoneAuthorizedUserCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.phoneAuthorizedUserCount.comparisonValue").doesNotExist())
                .andExpect(jsonPath("$.data.summary.phoneAuthorizationRate.value").value(10000))
                .andExpect(jsonPath("$.data.summary.phoneAuthorizationRate.comparisonValue").doesNotExist());
    }

    @Test
    void paymentAttemptAvailabilityDistinguishesAQuietPeriodFromPreCollectionHistory() throws Exception {
        jdbcClient.sql("""
                        insert into payment_attempt
                            (id, order_id, out_trade_no, status, amount_cent, started_at, created_at, updated_at)
                        values (88021, 88022, 'OPS-ATTEMPT-START', 'PREPAY_FAILED', 1000,
                                timestamp '2026-07-01 09:00:00', timestamp '2026-07-01 09:00:00',
                                timestamp '2026-07-01 09:00:01')
                        """).update();
        String token = token("operation:trade:read");

        mockMvc.perform(get("/admin/operations/trade-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-07-02")
                        .param("endDate", "2026-07-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.paymentAttemptCount.value").value(0))
                .andExpect(jsonPath("$.data.summary.paymentAttemptCount.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.summary.paymentAttemptSuccessRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.paymentAttemptSuccessRate.availability")
                        .value("NOT_APPLICABLE"));

        mockMvc.perform(get("/admin/operations/trade-statistics")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-06-30")
                        .param("endDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.paymentAttemptCount.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.paymentAttemptCount.availability").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.data.summary.paymentAttemptSuccessRate.availability").value("NOT_COLLECTED"));
    }

    @Test
    void generationTwoInstallationMarksZeroFactPeriodsAsCollected() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();

        mockMvc.perform(get("/admin/operations/trade-statistics")
                        .header("Authorization", "Bearer " + token("operation:trade:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.paymentAttemptCount.value").value(0))
                .andExpect(jsonPath("$.data.summary.paymentAttemptCount.availability").value("AVAILABLE"));

        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token("operation:user:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.activeUserCount.value").value(0))
                .andExpect(jsonPath("$.data.summary.activeUserCount.availability").value("AVAILABLE"));

        mockMvc.perform(get("/admin/operations/traffic-statistics")
                        .header("Authorization", "Bearer " + token("operation:traffic:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.pageViewCount.value").value(0))
                .andExpect(jsonPath("$.data.summary.pageViewCount.availability").value("AVAILABLE"));
    }

    @Test
    void zeroDenominatorMetricsAreNotApplicableAndOmitNullWireValues() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();

        mockMvc.perform(get("/admin/operations/overview")
                        .header("Authorization", "Bearer " + token("operation:overview:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trade.customerUnitPriceCent.value").doesNotExist())
                .andExpect(jsonPath("$.data.trade.customerUnitPriceCent.comparisonValue").doesNotExist())
                .andExpect(jsonPath("$.data.trade.customerUnitPriceCent.changeRateBasisPoints").doesNotExist())
                .andExpect(jsonPath("$.data.trade.customerUnitPriceCent.availability")
                        .value("NOT_APPLICABLE"));

        mockMvc.perform(get("/admin/operations/trade-statistics")
                        .header("Authorization", "Bearer " + token("operation:trade:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.averageOrderAmountCent.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.averageOrderAmountCent.availability")
                        .value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.summary.customerUnitPriceCent.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.customerUnitPriceCent.availability")
                        .value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.summary.orderPaymentConversionRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.orderPaymentConversionRate.comparisonValue").doesNotExist())
                .andExpect(jsonPath("$.data.summary.orderPaymentConversionRate.changeRateBasisPoints").doesNotExist())
                .andExpect(jsonPath("$.data.summary.orderPaymentConversionRate.availability")
                        .value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.summary.paymentAttemptSuccessRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.paymentAttemptSuccessRate.availability")
                        .value("NOT_APPLICABLE"));

        mockMvc.perform(get("/admin/operations/product-statistics")
                        .header("Authorization", "Bearer " + token("operation:product:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.refundRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.refundRate.availability").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.summary.costCoverageRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.costCoverageRate.availability")
                        .value("NOT_APPLICABLE"));

        mockMvc.perform(get("/admin/operations/user-statistics")
                        .header("Authorization", "Bearer " + token("operation:user:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.repeatBuyerRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.repeatBuyerRate.availability")
                        .value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.summary.phoneAuthorizationRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.phoneAuthorizationRate.availability")
                        .value("NOT_APPLICABLE"));

        mockMvc.perform(get("/admin/operations/marketing-statistics")
                        .header("Authorization", "Bearer " + token("operation:marketing:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.couponUsageRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.couponUsageRate.availability")
                        .value("NOT_APPLICABLE"));

        mockMvc.perform(get("/admin/operations/service-statistics")
                        .header("Authorization", "Bearer " + token("operation:service:read"))
                        .param("startDate", today)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.afterSaleApprovalRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.afterSaleApprovalRate.availability")
                        .value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.summary.refundSuccessRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.refundSuccessRate.availability")
                        .value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.summary.conversationCloseRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.conversationCloseRate.availability")
                        .value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.summary.conversationTransferRate.value").doesNotExist())
                .andExpect(jsonPath("$.data.summary.conversationTransferRate.availability")
                        .value("NOT_APPLICABLE"));
    }

    @Test
    void trafficFunnelRequiresEachVisitorToReachStagesInTimeOrder() throws Exception {
        jdbcClient.sql("""
                        insert into analytics_event
                            (id, client_event_id, payload_digest, visitor_id, session_id,
                             event_source, event_type, page_path, occurred_at, received_at, business_date)
                        values
                            (88501, 'funnel-a-home', 'digest-a1', 'funnel-a', 'session-a',
                             'CLIENT', 'PAGE_VIEW', '/pages/home/home',
                             timestamp '2026-07-02 08:00:00', timestamp '2026-07-02 08:00:00', date '2026-07-02'),
                            (88502, 'funnel-a-product', 'digest-a2', 'funnel-a', 'session-a',
                             'CLIENT', 'PRODUCT_VIEW', '/pages/product/detail',
                             timestamp '2026-07-02 08:05:00', timestamp '2026-07-02 08:05:00', date '2026-07-02'),
                            (88503, 'funnel-a-cart', 'digest-a3', 'funnel-a', 'session-a',
                             'SERVER', 'CART_ADD', '/pages/product/detail',
                             timestamp '2026-07-02 08:10:00', timestamp '2026-07-02 08:10:00', date '2026-07-02'),
                            (88504, 'funnel-a-checkout', 'digest-a4', 'funnel-a', 'session-a',
                             'CLIENT', 'CHECKOUT_START', '/pages/order/preview',
                             timestamp '2026-07-02 08:15:00', timestamp '2026-07-02 08:15:00', date '2026-07-02'),
                            (88505, 'funnel-b-product', 'digest-b1', 'funnel-b', 'session-b',
                             'CLIENT', 'PRODUCT_VIEW', '/pages/product/detail',
                             timestamp '2026-07-02 08:00:00', timestamp '2026-07-02 08:00:00', date '2026-07-02'),
                            (88506, 'funnel-b-cart', 'digest-b2', 'funnel-b', 'session-b',
                             'SERVER', 'CART_ADD', '/pages/product/detail',
                             timestamp '2026-07-02 08:05:00', timestamp '2026-07-02 08:05:00', date '2026-07-02'),
                            (88507, 'funnel-b-checkout', 'digest-b3', 'funnel-b', 'session-b',
                             'CLIENT', 'CHECKOUT_START', '/pages/order/preview',
                             timestamp '2026-07-02 08:10:00', timestamp '2026-07-02 08:10:00', date '2026-07-02'),
                            (88508, 'funnel-b-home', 'digest-b4', 'funnel-b', 'session-b',
                             'CLIENT', 'PAGE_VIEW', '/pages/home/home',
                             timestamp '2026-07-02 08:15:00', timestamp '2026-07-02 08:15:00', date '2026-07-02'),
                            (88509, 'funnel-c-home', 'digest-c1', 'funnel-c', 'session-c',
                             'CLIENT', 'PAGE_VIEW', '/pages/home/home',
                             timestamp '2026-07-02 08:00:00', timestamp '2026-07-02 08:00:00', date '2026-07-02'),
                            (88510, 'funnel-c-product', 'digest-c2', 'funnel-c', 'session-c',
                             'CLIENT', 'PRODUCT_VIEW', '/pages/product/detail',
                             timestamp '2026-07-02 08:05:00', timestamp '2026-07-02 08:05:00', date '2026-07-02'),
                            (88511, 'funnel-c-checkout', 'digest-c3', 'funnel-c', 'session-c',
                             'CLIENT', 'CHECKOUT_START', '/pages/order/preview',
                             timestamp '2026-07-02 08:10:00', timestamp '2026-07-02 08:10:00', date '2026-07-02'),
                            (88512, 'funnel-c-cart', 'digest-c4', 'funnel-c', 'session-c',
                             'SERVER', 'CART_ADD', '/pages/product/detail',
                             timestamp '2026-07-02 08:15:00', timestamp '2026-07-02 08:15:00', date '2026-07-02')
                        """).update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, idempotency_key, checkout_request_digest,
                             payable_amount_cent,
                             paid_amount_cent, analytics_visitor_id, analytics_session_id,
                             paid_at, created_at, updated_at)
                        values
                            (88521, 'OPS-FUNNEL-A', 88531, 'PAID', 'ops-funnel-a',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', 1000,
                             1000, 'funnel-a', 'session-a', timestamp '2026-07-02 08:25:00',
                             timestamp '2026-07-02 08:20:00', timestamp '2026-07-02 08:25:00'),
                            (88522, 'OPS-FUNNEL-B', 88532, 'PAID', 'ops-funnel-b',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', 1000,
                             1000, 'funnel-b', 'session-b', timestamp '2026-07-02 08:25:00',
                             timestamp '2026-07-02 08:20:00', timestamp '2026-07-02 08:25:00')
                        """).update();

        mockMvc.perform(get("/admin/operations/traffic-statistics")
                        .header("Authorization", "Bearer " + token("operation:traffic:read"))
                        .param("startDate", "2026-07-02")
                        .param("endDate", "2026-07-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.funnel.data[0].users").value(3))
                .andExpect(jsonPath("$.data.funnel.data[1].users").value(2))
                .andExpect(jsonPath("$.data.funnel.data[2].users").value(2))
                .andExpect(jsonPath("$.data.funnel.data[3].users").value(1))
                .andExpect(jsonPath("$.data.funnel.data[4].users").value(1))
                .andExpect(jsonPath("$.data.funnel.data[5].users").value(1))
                .andExpect(jsonPath("$.data.funnel.data[3].conversionRateBasisPoints").value(5000));
    }

    @Test
    void trafficTrendAggregatesDistinctValuesAcrossPartialCalendarMonths() {
        jdbcClient.sql("""
                        insert into analytics_event
                            (id, client_event_id, payload_digest, visitor_id, session_id,
                             event_source, event_type, page_path, occurred_at, received_at, business_date)
                        values
                            (88701, 'trend-july-first', 'trend-july-first-digest',
                             'trend-visitor-a', 'trend-session-a', 'CLIENT', 'PAGE_VIEW', '/first',
                             timestamp '2026-07-14 16:00:00', timestamp '2026-07-14 16:00:01', date '2026-07-15'),
                            (88702, 'trend-july-repeat', 'trend-july-repeat-digest',
                             'trend-visitor-a', 'trend-session-a', 'CLIENT', 'SEARCH', '/search',
                             timestamp '2026-07-31 15:59:59', timestamp '2026-07-31 15:59:59', date '2026-07-31'),
                            (88703, 'trend-july-second', 'trend-july-second-digest',
                             'trend-visitor-b', 'trend-session-b', 'CLIENT', 'PAGE_VIEW', '/second',
                             timestamp '2026-07-31 04:00:00', timestamp '2026-07-31 04:00:01', date '2026-07-31'),
                            (88704, 'trend-august-first', 'trend-august-first-digest',
                             'trend-visitor-a', 'trend-session-a', 'CLIENT', 'PAGE_VIEW', '/first',
                             timestamp '2026-07-31 16:00:00', timestamp '2026-07-31 16:00:01', date '2026-08-01'),
                            (88705, 'trend-august-session', 'trend-august-session-digest',
                             'trend-visitor-a', 'trend-session-c', 'CLIENT', 'SEARCH', '/search',
                             timestamp '2026-08-02 15:59:59', timestamp '2026-08-02 15:59:59', date '2026-08-02')
                        """).update();

        var report = operationsStatisticsService.trafficStatistics(new ReportQuery(
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 8, 2),
                Granularity.MONTH));
        Map<String, List<TrendPoint>> pointsBySeries = report.trend().data().stream()
                .collect(Collectors.toMap(TrendSeries::key, TrendSeries::points));

        assertThat(pointsBySeries.get("pageViewCount"))
                .extracting(TrendPoint::bucket, TrendPoint::value)
                .containsExactly(tuple("2026-07", 2L), tuple("2026-08", 1L));
        assertThat(pointsBySeries.get("visitorCount"))
                .extracting(TrendPoint::bucket, TrendPoint::value)
                .containsExactly(tuple("2026-07", 2L), tuple("2026-08", 1L));
        assertThat(pointsBySeries.get("sessionCount"))
                .extracting(TrendPoint::bucket, TrendPoint::value)
                .containsExactly(tuple("2026-07", 2L), tuple("2026-08", 2L));
    }

    @Test
    void productTrendIsAggregatedIntoDatabaseBuckets() {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, idempotency_key, checkout_request_digest,
                             paid_at, created_at, updated_at)
                        values
                            (88901, 'OPS-PRODUCT-TREND-JULY', 88900, 'PAID', 'ops-product-trend-july',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             timestamp '2026-07-31 15:00:00', timestamp '2026-07-31 14:00:00',
                             timestamp '2026-07-31 15:00:00'),
                            (88902, 'OPS-PRODUCT-TREND-AUGUST', 88900, 'PAID', 'ops-product-trend-august',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             timestamp '2026-07-31 17:00:00', timestamp '2026-07-31 16:30:00',
                             timestamp '2026-07-31 17:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, sku_code, quantity,
                             line_amount_cent, created_at)
                        values
                            (88911, 88901, 88921, 88931, '七月商品 A', 'OPS-JULY-A', 2, 2000,
                             timestamp '2026-07-31 14:00:00'),
                            (88912, 88901, 88922, 88932, '七月商品 B', 'OPS-JULY-B', 1, 1500,
                             timestamp '2026-07-31 14:00:00'),
                            (88913, 88902, 88923, 88933, '八月商品', 'OPS-AUGUST', 4, 5000,
                             timestamp '2026-07-31 16:30:00')
                        """).update();

        var buckets = commerceTrendQueryRepository.loadProductTrendBuckets(
                LocalDate.of(2026, 7, 15),
                org.muybaby.shopserver.common.time.TimePolicy.businessDayStartUtc(LocalDate.of(2026, 7, 15)),
                org.muybaby.shopserver.common.time.TimePolicy.businessDayStartUtc(LocalDate.of(2026, 8, 3)),
                Granularity.MONTH
        );

        assertThat(buckets)
                .extracting(
                        CommerceTrendQueryRepository.ProductTrendBucket::bucketOrdinal,
                        CommerceTrendQueryRepository.ProductTrendBucket::soldQuantity,
                        CommerceTrendQueryRepository.ProductTrendBucket::paidItemAmountCent)
                .containsExactly(tuple(0, 3L, 3500L), tuple(1, 4L, 5000L));
    }

    @Test
    void trafficFunnelUsesEventIdToOrderStagesWithTheSameTimestamp() {
        jdbcClient.sql("""
                        insert into analytics_event
                            (id, client_event_id, payload_digest, visitor_id, session_id,
                             event_source, event_type, page_path, occurred_at, received_at, business_date)
                        values
                            (88801, 'tie-before-product', 'tie-before-product-digest',
                             'tie-before', 'tie-before-session', 'CLIENT', 'PRODUCT_VIEW', '/product',
                             timestamp '2026-07-02 08:00:00', timestamp '2026-07-02 08:00:00', date '2026-07-02'),
                            (88802, 'tie-before-home', 'tie-before-home-digest',
                             'tie-before', 'tie-before-session', 'CLIENT', 'PAGE_VIEW', '/pages/home/home',
                             timestamp '2026-07-02 08:00:00', timestamp '2026-07-02 08:00:00', date '2026-07-02'),
                            (88811, 'tie-after-home', 'tie-after-home-digest',
                             'tie-after', 'tie-after-session', 'CLIENT', 'PAGE_VIEW', '/pages/home/home',
                             timestamp '2026-07-02 09:00:00', timestamp '2026-07-02 09:00:00', date '2026-07-02'),
                            (88812, 'tie-after-product', 'tie-after-product-digest',
                             'tie-after', 'tie-after-session', 'CLIENT', 'PRODUCT_VIEW', '/product',
                             timestamp '2026-07-02 09:00:00', timestamp '2026-07-02 09:00:00', date '2026-07-02'),
                            (88813, 'tie-after-cart', 'tie-after-cart-digest',
                             'tie-after', 'tie-after-session', 'SERVER', 'CART_ADD', '/product',
                             timestamp '2026-07-02 09:00:00', timestamp '2026-07-02 09:00:00', date '2026-07-02'),
                            (88814, 'tie-after-checkout', 'tie-after-checkout-digest',
                             'tie-after', 'tie-after-session', 'CLIENT', 'CHECKOUT_START', '/checkout',
                             timestamp '2026-07-02 09:00:00', timestamp '2026-07-02 09:00:00', date '2026-07-02')
                        """).update();

        var funnel = operationsStatisticsService.trafficStatistics(new ReportQuery(
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 2),
                        Granularity.HOUR))
                .funnel()
                .data();

        assertThat(funnel)
                .extracting("key", "users")
                .containsExactly(
                        tuple("homeVisit", 2L),
                        tuple("productView", 1L),
                        tuple("cartAdd", 1L),
                        tuple("checkoutStart", 1L),
                        tuple("orderSubmit", 0L),
                        tuple("paymentSuccess", 0L));
    }

    @Test
    void naturalCouponExpiryAndAfterSaleReviewsUseTheirOwnBusinessTimes() throws Exception {
        jdbcClient.sql("""
                        insert into coupon_template
                            (id, name, coupon_type, discount_type, discount_cent, total_stock,
                             valid_start_at, valid_end_at, status)
                        values (88101, '自然过期券', 'NO_THRESHOLD', 'AMOUNT_OFF', 500, 10,
                                timestamp '2026-06-01 00:00:00', timestamp '2026-07-03 12:00:00', 'ENABLED')
                        """).update();
        jdbcClient.sql("""
                        insert into user_coupon
                            (id, user_id, template_id, template_name, coupon_type, discount_type,
                             discount_cent, valid_start_at, valid_end_at, status, claimed_at)
                        values (88102, 88100, 88101, '自然过期券', 'NO_THRESHOLD', 'AMOUNT_OFF', 500,
                                timestamp '2026-06-01 00:00:00', timestamp '2026-07-03 12:00:00',
                                'CLAIMED', timestamp '2026-06-01 08:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, after_sale_no, order_id, user_id, after_sale_type, status, reason,
                             requested_amount_cent, approved_amount_cent, reviewed_at, created_at, updated_at)
                        values
                            (88201, 'ASFIX88201', 88211, 88100, 'REFUND_ONLY', 'REFUNDED', '跨期通过',
                             1000, 1000, timestamp '2026-07-02 10:00:00',
                             timestamp '2026-06-30 10:00:00', timestamp '2026-07-02 10:00:00'),
                            (88202, 'ASFIX88202', 88212, 88100, 'REFUND_ONLY', 'REJECTED', '跨期拒绝',
                             1000, null, timestamp '2026-07-03 10:00:00',
                             timestamp '2026-06-30 11:00:00', timestamp '2026-07-03 10:00:00'),
                            (88203, 'ASFIX88203', 88213, 88100, 'REFUND_ONLY', 'REQUESTED', '本期申请',
                             1000, null, null,
                             timestamp '2026-07-04 10:00:00', timestamp '2026-07-04 10:00:00')
                        """).update();

        mockMvc.perform(get("/admin/operations/marketing-statistics")
                        .header("Authorization", "Bearer " + token("operation:marketing:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.expiredCouponCount.value").value(1));

        mockMvc.perform(get("/admin/operations/service-statistics")
                        .header("Authorization", "Bearer " + token("operation:service:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.afterSaleApplicationCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.approvedAfterSaleCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.rejectedAfterSaleCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.afterSaleApprovalRate.value").value(5000))
                .andExpect(jsonPath("$.data.summary.averageAfterSaleReviewSeconds.value").value(214200));
    }

    @Test
    void agentFirstResponseUsesTheFirstAdminMessageWithinTheSelectedPeriod() throws Exception {
        jdbcClient.sql("""
                        insert into app_user (id, openid, status, created_at, updated_at)
                        values
                            (88301, 'operations-service-current', 'ENABLED', current_timestamp, current_timestamp),
                            (88302, 'operations-service-old', 'ENABLED', current_timestamp, current_timestamp)
                        """).update();
        jdbcClient.sql("""
                        insert into customer_service_conversation
                            (id, app_user_id, status, assigned_admin_user_id, consultation_no,
                             activated_at, claimed_at, created_at, updated_at)
                        values
                            (88311, 88301, 'CLOSED', 1, 1,
                             timestamp '2026-07-02 09:00:00', timestamp '2026-07-02 09:05:00',
                             timestamp '2026-07-02 09:00:00', timestamp '2026-07-02 09:20:00'),
                            (88312, 88302, 'ACTIVE', 1, 1,
                             timestamp '2026-06-02 09:00:00', timestamp '2026-06-02 09:30:00',
                             timestamp '2026-06-02 09:00:00', timestamp '2026-06-02 09:31:00')
                        """).update();
        jdbcClient.sql("""
                        insert into customer_service_message
                            (id, conversation_id, consultation_no, sender_type, sender_id,
                             message_type, content, created_at)
                        values
                            (88321, 88311, 1, 'APP_USER', 88301, 'TEXT', '需要帮助',
                             timestamp '2026-07-02 09:00:00'),
                            (88322, 88311, 1, 'ADMIN', 1, 'TEXT', '您好',
                             timestamp '2026-07-02 09:20:00'),
                            (88323, 88312, 1, 'ADMIN', 1, 'TEXT', '旧周期回复',
                             timestamp '2026-06-02 09:31:00')
                        """).update();
        jdbcClient.sql("""
                        update customer_service_conversation
                        set closed_at = timestamp '2026-07-02 10:00:00'
                        where id = 88311
                        """).update();
        jdbcClient.sql("""
                        insert into customer_service_assignment_log
                            (id, conversation_id, action, from_admin_user_id, to_admin_user_id,
                             operator_type, operator_id, created_at)
                        values
                            (88331, 88311, 'TRANSFER', 2, 1, 'ADMIN', 1,
                             timestamp '2026-07-02 09:10:00'),
                            (88332, 88311, 'CLOSE', 1, null, 'ADMIN', 1,
                             timestamp '2026-07-02 10:00:00')
                        """).update();

        mockMvc.perform(get("/admin/operations/service-statistics")
                        .header("Authorization", "Bearer " + token("operation:service:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentLoads.data[0].adminUserId").value("1"))
                .andExpect(jsonPath("$.data.agentLoads.data[0].firstResponseSeconds").value(1200))
                .andExpect(jsonPath("$.data.summary.conversationCount.value").value(1))
                .andExpect(jsonPath("$.data.summary.averageFirstResponseSeconds.value").value(1200))
                .andExpect(jsonPath("$.data.summary.averageResolutionSeconds.value").value(3600))
                .andExpect(jsonPath("$.data.summary.conversationCloseRate.value").value(10000))
                .andExpect(jsonPath("$.data.summary.conversationTransferRate.value").value(10000));
    }

    @Test
    void legacyOrderItemsRemainAvailableAsPaidItemGrossAmount() throws Exception {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, idempotency_key, checkout_request_digest,
                             payable_amount_cent, paid_amount_cent, paid_at, created_at, updated_at)
                        values (88401, 'OPS-LEGACY-AMOUNT', 88400, 'PAID', 'ops-legacy-amount',
                                'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                                9000, 9000, timestamp '2026-07-02 09:00:00',
                                timestamp '2026-07-02 08:55:00', timestamp '2026-07-02 09:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, sku_code, quantity,
                             line_original_amount_cent, line_amount_cent, paid_amount_allocated_cent, created_at)
                        values (88402, 88401, 88403, 88404, '历史商品', 'OPS-LEGACY-SKU', 1,
                                10000, 10000, null, timestamp '2026-07-02 08:55:00')
                        """).update();

        mockMvc.perform(get("/admin/operations/product-statistics")
                        .header("Authorization", "Bearer " + token("operation:product:read"))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.paidItemAmountCent.value").value(10000))
                .andExpect(jsonPath("$.data.summary.paidItemAmountCent.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.summary.refundRate.value").value(0))
                .andExpect(jsonPath("$.data.summary.refundRate.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.summary.costCoverageRate.value").value(0))
                .andExpect(jsonPath("$.data.summary.costCoverageRate.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.summary.grossProfitAmountCent.availability").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.data.topProducts.data[0].secondaryValue").value(10000));
    }

    private String token(String permission) {
        return AdminTokenTestSupport.issueAdminToken(jdbcClient, opaqueTokenService, List.of(permission));
    }

    private void seedBusinessFacts() {
        jdbcClient.sql("""
                        insert into app_user
                            (id, openid, nickname, phone_number, phone_authorized, phone_authorized_at,
                             status, created_at, updated_at)
                        values
                            (90001, 'operations-user-1', '用户一', '13800000001', true,
                             timestamp '2026-07-01 08:00:00', 'ENABLED',
                             timestamp '2026-06-20 10:00:00', timestamp '2026-06-20 10:00:00'),
                            (90002, 'operations-user-2', '用户二', null, false, null, 'ENABLED',
                             timestamp '2026-07-02 11:00:00', timestamp '2026-07-02 11:00:00'),
                            (90003, 'operations-user-3', '用户三', null, false, null, 'ENABLED',
                             timestamp '2026-07-05 12:00:00', timestamp '2026-07-05 12:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into app_user_daily_activity
                            (user_id, activity_date, first_active_at)
                        values
                            (90001, date '2026-07-02', timestamp '2026-07-02 09:00:00'),
                            (90002, date '2026-07-03', timestamp '2026-07-03 09:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, status)
                        values (91001, 0, '统计分类', 'ENABLED')
                        """).update();
        jdbcClient.sql("""
                        insert into product_spu
                            (id, category_id, title, main_image, selling_points, detail_html,
                             status, freight_template_id, virtual_sales)
                        values
                            (92001, 91001, '统计商品A', 'https://example.test/a.png', '', '', 'ON_SALE', 1, 999),
                            (92002, 91001, '统计商品B', 'https://example.test/b.png', '', '', 'ON_SALE', 1, 0),
                            (92003, 91001, '下架商品', 'https://example.test/off-sale.png', '', '', 'OFF_SALE', 1, 0)
                        """).update();
        jdbcClient.sql("""
                        insert into product_sku
                            (id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, image, status, combination_key,
                             is_default, low_stock_threshold)
                        values
                            (93001, 92001, 'OPS-SKU-A', '{}', '默认', 5000, 5000, 0, 100, '', 'ENABLED', 'ops-a', true, 10),
                            (93002, 92002, 'OPS-SKU-B', '{}', '默认', 5000, 5000, 20, 100, '', 'ENABLED', 'ops-b', true, 10),
                            (93003, 92003, 'OPS-SKU-OFF', '{}', '默认', 5000, 5000, 0, 100, '', 'ENABLED', 'ops-off', true, 10)
                        """).update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent,
                             paid_at, shipped_at, completed_at, refunded_at, created_at, updated_at)
                        values
                            (94001, 'OPS-ORDER-1', 90001, 'PAID', 'CART', 'ops-order-1',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             10000, 10000, 500, 0, 9500, 9500,
                             timestamp '2026-07-02 09:30:00', null, null, null,
                             timestamp '2026-07-02 09:00:00', timestamp '2026-07-02 09:30:00'),
                            (94002, 'OPS-ORDER-2', 90001, 'REFUNDED', 'BUY_NOW', 'ops-order-2',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             20000, 20000, 0, 0, 20000, 20000,
                             timestamp '2026-07-03 10:30:00', timestamp '2026-07-04 10:00:00', null,
                             timestamp '2026-07-06 12:00:00',
                             timestamp '2026-07-03 10:00:00', timestamp '2026-07-06 12:00:00'),
                            (94003, 'OPS-ORDER-3', 90002, 'PAID', 'CART', 'ops-order-3',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             5000, 5000, 0, 0, 5000, 5000,
                             timestamp '2026-07-04 11:30:00', null, null, null,
                             timestamp '2026-07-04 11:00:00', timestamp '2026-07-04 11:30:00'),
                            (94004, 'OPS-ORDER-4', 90003, 'CREATED', 'CART', 'ops-order-4',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             5000, 5000, 0, 0, 5000, 0,
                             null, null, null, null,
                             timestamp '2026-07-05 12:00:00', timestamp '2026-07-05 12:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, sku_code, spec_text,
                             original_price_cent, unit_price_cent, quantity,
                             line_original_amount_cent, line_amount_cent,
                             unit_cost_cent, line_cost_cent, coupon_discount_allocated_cent,
                             freight_allocated_cent, paid_amount_allocated_cent, created_at)
                        values
                            (95001, 94001, 93001, 92001, '统计商品A', 'OPS-SKU-A', '默认',
                             5000, 5000, 2, 10000, 10000, 3000, 6000, 500, 0, 9500,
                             timestamp '2026-07-02 09:00:00'),
                            (95002, 94002, 93001, 92001, '统计商品A', 'OPS-SKU-A', '默认',
                             20000, 20000, 1, 20000, 20000, 10000, 10000, 0, 0, 20000,
                             timestamp '2026-07-03 10:00:00'),
                            (95003, 94003, 93002, 92002, '统计商品B', 'OPS-SKU-B', '默认',
                             5000, 5000, 1, 5000, 5000, null, null, 0, 0, 5000,
                             timestamp '2026-07-04 11:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, payment_config_id, payment_config_fingerprint,
                             notification_route_token, out_trade_no, status, amount_cent,
                             expires_at, paid_at, created_at, updated_at)
                        values
                            (96001, 94001, 9999001,
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'OPS-PAY-1', 'PAID', 9500, timestamp '2026-07-02 09:45:00',
                             timestamp '2026-07-02 09:30:00', timestamp '2026-07-02 09:00:00', timestamp '2026-07-02 09:30:00'),
                            (96002, 94002, 9999001,
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'OPS-PAY-2', 'PAID', 20000, timestamp '2026-07-03 10:45:00',
                             timestamp '2026-07-03 10:30:00', timestamp '2026-07-03 10:00:00', timestamp '2026-07-03 10:30:00'),
                            (96003, 94003, 9999001,
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             'cccccccccccccccccccccccccccccccc', 'OPS-PAY-3', 'PAID', 5000, timestamp '2026-07-04 11:45:00',
                             timestamp '2026-07-04 11:30:00', timestamp '2026-07-04 11:00:00', timestamp '2026-07-04 11:30:00')
                        """).update();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, after_sale_no, order_id, user_id, after_sale_type, status, reason,
                             requested_amount_cent, approved_amount_cent, created_at, updated_at)
                        values
                            (97001, 'ASFIX97001', 94002, 90001, 'REFUND_ONLY', 'REFUNDED', '不喜欢',
                             20000, 20000,
                             timestamp '2026-07-05 10:00:00', timestamp '2026-07-06 12:00:00'),
                            (97002, 'ASFIX97002', 94003, 90002, 'REFUND_ONLY', 'REQUESTED', '其他',
                             5000, null,
                             timestamp '2026-07-07 10:00:00', timestamp '2026-07-07 10:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into refund_order
                            (id, after_sale_id, order_id, payment_order_id,
                             notification_route_token, out_refund_no,
                             refund_amount_cent, status, requested_at, success_at, created_at, updated_at)
                        values
                            (98001, 97001, 94002, 96002,
                             'ffffffffffffffffffffffffffffffff', 'OPS-REFUND-1', 20000, 'SUCCESS',
                             timestamp '2026-07-05 11:00:00', timestamp '2026-07-06 12:00:00',
                             timestamp '2026-07-05 11:00:00', timestamp '2026-07-06 12:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into order_shipment
                            (id, order_id, express_company_name, tracking_no, status, wechat_upload_status,
                             shipped_at, created_at, updated_at)
                        values
                            (99001, 94002, '顺丰速运', 'SF-OPS-1', 'SHIPPED', 'FAILED',
                             timestamp '2026-07-04 10:00:00', timestamp '2026-07-04 10:00:00',
                             timestamp '2026-07-04 10:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into coupon_template
                            (id, name, coupon_type, discount_type, discount_cent, total_stock,
                             valid_start_at, valid_end_at, status)
                        values
                            (99101, '统计优惠券', 'NO_THRESHOLD', 'AMOUNT_OFF', 500, 100,
                             timestamp '2026-07-01 00:00:00', timestamp '2026-07-31 23:59:59', 'ENABLED')
                        """).update();
        jdbcClient.sql("""
                        insert into user_coupon
                            (id, user_id, template_id, template_name, coupon_type, discount_type,
                             discount_cent, valid_start_at, valid_end_at, status, claimed_at, used_order_id, used_at)
                        values
                            (99201, 90001, 99101, '统计优惠券', 'NO_THRESHOLD', 'AMOUNT_OFF',
                             500, timestamp '2026-07-01 00:00:00', timestamp '2026-07-31 23:59:59', 'USED',
                             timestamp '2026-07-02 08:00:00', 94001, timestamp '2026-07-02 09:30:00'),
                            (99202, 90002, 99101, '统计优惠券', 'NO_THRESHOLD', 'AMOUNT_OFF',
                             500, timestamp '2026-07-01 00:00:00', timestamp '2026-07-31 23:59:59', 'CLAIMED',
                             timestamp '2026-07-03 08:00:00', null, null)
                        """).update();
        jdbcClient.sql("""
                        insert into coupon_claim_record
                            (id, template_id, user_id, user_coupon_id, claimed_at, issue_source)
                        values
                            (99301, 99101, 90001, 99201, timestamp '2026-07-02 08:00:00', 'SELF_CLAIM'),
                            (99302, 99101, 90002, 99202, timestamp '2026-07-03 08:00:00', 'ADMIN_ISSUE')
                        """).update();
        jdbcClient.sql("""
                        insert into customer_service_conversation
                            (id, app_user_id, status, assigned_admin_user_id, last_message_at,
                             admin_unread_count, activated_at, created_at, updated_at)
                        values
                            (99401, 90001, 'WAITING', null, timestamp '2026-07-06 08:00:00', 2,
                             timestamp '2026-07-06 08:00:00', timestamp '2026-07-06 08:00:00',
                             timestamp '2026-07-06 08:00:00'),
                            (99402, 90002, 'ACTIVE', 1, timestamp '2026-07-06 09:00:00', 3,
                             timestamp '2026-07-06 09:00:00', timestamp '2026-07-06 09:00:00',
                             timestamp '2026-07-06 09:00:00')
                        """).update();
        jdbcClient.sql("""
                        insert into analytics_event
                            (id, client_event_id, payload_digest, visitor_id, session_id, user_id,
                             event_source, event_type, page_path, entry_scene, occurred_at,
                             received_at, business_date)
                        values
                            (99500, 'ops-launch-1', 'digest-launch-1', 'visitor-1', 'session-1', 90001,
                             'CLIENT', 'APP_LAUNCH', '/app', 'HOME',
                             timestamp '2026-07-02 07:59:00', timestamp '2026-07-02 08:00:00', date '2026-07-02'),
                            (99501, 'ops-event-1', 'digest-1', 'visitor-1', 'session-1', 90001,
                             'CLIENT', 'PAGE_VIEW', '/pages/home/home', 'HOME',
                             timestamp '2026-07-02 08:00:00', timestamp '2026-07-02 08:00:00', date '2026-07-02'),
                            (99502, 'ops-event-2', 'digest-2', 'visitor-1', 'session-1', 90001,
                             'CLIENT', 'PRODUCT_VIEW', '/pages/product/detail', 'HOME',
                             timestamp '2026-07-02 08:10:00', timestamp '2026-07-02 08:10:00', date '2026-07-02'),
                            (99503, 'ops-event-3', 'digest-3', 'visitor-2', 'session-2', 90002,
                             'CLIENT', 'PAGE_VIEW', '/pages/home/home', 'SEARCH',
                             timestamp '2026-07-03 08:00:00', timestamp '2026-07-03 08:00:00', date '2026-07-03'),
                            (99505, 'ops-launch-2', 'digest-launch-2', 'visitor-2', 'session-2', 90002,
                             'CLIENT', 'APP_LAUNCH', '/app', 'SEARCH',
                             timestamp '2026-07-03 07:59:00', timestamp '2026-07-03 08:00:00', date '2026-07-03'),
                            (99504, 'ops-event-4', 'digest-4', 'visitor-2', 'session-2', 90002,
                             'CLIENT', 'CHECKOUT_START', '/pages/order/confirm', 'SEARCH',
                             timestamp '2026-07-03 08:20:00', timestamp '2026-07-03 08:20:00', date '2026-07-03')
                        """).update();
    }
}
