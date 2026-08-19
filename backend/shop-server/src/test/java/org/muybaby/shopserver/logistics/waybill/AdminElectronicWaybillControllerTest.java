package org.muybaby.shopserver.logistics.waybill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillAddRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillCancelRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillGetRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillProvider;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillResult;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillTestUpdateRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminElectronicWaybillControllerTest {

    private static final AtomicLong ORDER_SEQUENCE = new AtomicLong(8_300_000L);
    private static final String MANAGE = "order:waybill:manage";
    private static final String PRINT = "order:waybill:print";
    private static final String TEST = "order:waybill:test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private RecordingWaybillProvider provider;

    @BeforeEach
    void setUp() {
        provider.reset();
        enableSandbox();
    }

    @Test
    void contextUsesExactPermissionAndReportsEveryTrustedInputBlocker() throws Exception {
        long orderId = insertPaidOrder("CONTEXT");
        String manageToken = token(List.of(MANAGE));
        String printToken = token(List.of(PRINT));

        mockMvc.perform(get("/admin/orders/{orderId}/waybills/context", orderId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/orders/{orderId}/waybills/context", orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(printToken)))
                .andExpect(status().isForbidden());

        JsonNode ready = context(manageToken, orderId);
        assertThat(ready.path("mode").asText()).isEqualTo("SANDBOX");
        assertThat(ready.path("canCreate").asBoolean()).isTrue();
        assertThat(ready.path("blockers")).isEmpty();
        assertThat(ready.path("sender").path("detailAddress").asText()).isEqualTo("科技园1号");
        assertThat(ready.path("receiver").path("locationName").asText()).isEqualTo("南山智园");
        assertThat(ready.path("defaultParcel").path("count").asInt()).isOne();
        assertThat(ready.path("currentAttempt").isNull()).isTrue();
        assertThat(ready.path("sandboxActions")).hasSize(4);
        assertThat(ready.path("sandboxActions").path(0).size()).isEqualTo(2);
        assertThat(ready.path("sandboxActions").path(0).path("actionType").asInt())
                .isEqualTo(100001);
        assertThat(ready.path("sandboxActions").path(0).path("actionMessage").asText())
                .isEqualTo("快递员已揽件");

        jdbcClient.sql("update wechat_express_setting set mode = 'DISABLED' where id = 1").update();
        assertBlocker(manageToken, orderId, "未启用");
        enableSandbox();

        jdbcClient.sql("update shop_order set status = 'CREATED' where id = :id")
                .param("id", orderId).update();
        assertBlocker(manageToken, orderId, "待发货");
        jdbcClient.sql("update shop_order set status = 'PAID' where id = :id")
                .param("id", orderId).update();

        jdbcClient.sql("""
                        insert into after_sale_request(
                            after_sale_no, order_id, user_id, after_sale_type, status, reason,
                            requested_amount_cent, created_at, updated_at)
                        values (:no, :orderId, 900001, 'REFUND_ONLY', 'REQUESTED', '测试',
                                100, current_timestamp, current_timestamp)
                        """)
                .param("no", "AS-WB-" + orderId)
                .param("orderId", orderId)
                .update();
        assertBlocker(manageToken, orderId, "售后");
        jdbcClient.sql("delete from after_sale_request where order_id = :id")
                .param("id", orderId).update();

        jdbcClient.sql("update shop_order set receiver_city = '' where id = :id")
                .param("id", orderId).update();
        assertBlocker(manageToken, orderId, "结构化");
        jdbcClient.sql("update shop_order set receiver_city = '深圳市' where id = :id")
                .param("id", orderId).update();

        jdbcClient.sql("update payment_order set payer_openid = '' where order_id = :id")
                .param("id", orderId).update();
        assertBlocker(manageToken, orderId, "OpenID");
        jdbcClient.sql("update payment_order set payer_openid = :openid where order_id = :id")
                .param("openid", "openid-" + orderId).param("id", orderId).update();

        jdbcClient.sql("update payment_order set transaction_id = '' where order_id = :id")
                .param("id", orderId).update();
        assertBlocker(manageToken, orderId, "交易号");
        jdbcClient.sql("update payment_order set transaction_id = :tx where order_id = :id")
                .param("tx", "wx-" + orderId).param("id", orderId).update();

        jdbcClient.sql("""
                        update order_item
                        set main_image = 'http://private.test/item.jpg',
                            sku_image = 'http://private.test/item.jpg',
                            display_image = 'http://private.test/item.jpg'
                        where order_id = :id
                        """).param("id", orderId).update();
        assertBlocker(manageToken, orderId, "HTTPS");
        jdbcClient.sql("""
                        update order_item
                        set main_image = 'https://cdn.example.test/item.jpg',
                            sku_image = 'https://cdn.example.test/item.jpg',
                            display_image = 'https://cdn.example.test/item.jpg'
                        where order_id = :id
                        """).param("id", orderId).update();

        insertShipment(orderId);
        assertThat(context(manageToken, orderId).path("canCreate").asBoolean()).isTrue();
        jdbcClient.sql("delete from order_shipment where order_id = :id")
                .param("id", orderId).update();

        create(manageToken, orderId, UUID.randomUUID().toString(), "1.000")
                .andExpect(status().isOk());
        assertBlocker(manageToken, orderId, "待处理的电子面单");
    }

    @Test
    void createUsesTrustedSnapshotsIsIdempotentAndDoesNotShip() throws Exception {
        long orderId = insertPaidOrder("CREATE");
        String token = token(List.of(MANAGE));
        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult first = create(token, orderId, idempotencyKey, "1.000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environment").value("SANDBOX"))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.deliveryId").value("TEST"))
                .andExpect(jsonPath("$.data.bizIdMasked").value("test_biz_id"))
                .andExpect(jsonPath("$.data.waybillNo").isNotEmpty())
                .andExpect(jsonPath("$.data.canPrint").value(true))
                .andExpect(jsonPath("$.data.canConfirmShipment").value(true))
                .andReturn();
        long recordId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        assertThat(provider.addRequests).hasSize(1);
        WechatElectronicWaybillAddRequest upstream = provider.addRequests.getFirst();
        assertThat(upstream.localRecordId()).isEqualTo(recordId);
        assertThat(upstream.openid()).isEqualTo("openid-" + orderId);
        assertThat(upstream.sender().address()).isEqualTo("科技园1号");
        assertThat(upstream.receiver().address()).contains("学苑大道", "南山智园", "A座101");
        assertThat(upstream.shopItems().getFirst().goodsImageUrl())
                .isEqualTo("https://cdn.example.test/item.jpg");
        assertThat(upstream.miniProgramOrderPath())
                .isEqualTo("pages/order/detail/detail?order_id=" + orderId);
        assertThat(provider.sawCommittedCreating).isTrue();
        assertThat(provider.networkTransactionActive).isFalse();

        create(token, orderId, idempotencyKey, "1.0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(recordId));
        assertThat(provider.addRequests).hasSize(1);

        create(token, orderId, idempotencyKey, "2.000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.WECHAT_WAYBILL_CONFLICT.code()));
        create(token, orderId, UUID.randomUUID().toString(), "1.000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.WECHAT_WAYBILL_CONFLICT.code()));

        assertThat(jdbcClient.sql("select status from shop_order where id = :id")
                .param("id", orderId).query(String.class).single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :id")
                .param("id", orderId).query(Integer.class).single()).isZero();
    }

    @Test
    void concurrentDoubleCreateMakesOneUpstreamAddCall() throws Exception {
        long orderId = insertPaidOrder("DOUBLE");
        String token = token(List.of(MANAGE));
        String key = UUID.randomUUID().toString();
        provider.blockAdd();

        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return create(token, orderId, key, "1.000")
                        .andExpect(status().isOk()).andReturn();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(provider.addEntered.await(5, TimeUnit.SECONDS)).isTrue();

        create(token, orderId, key, "1.000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATING"));
        assertThat(provider.addRequests).hasSize(1);

        provider.releaseAdd.countDown();
        JsonNode completed = objectMapper.readTree(first.get(5, TimeUnit.SECONDS)
                .getResponse().getContentAsString());
        assertThat(completed.path("data").path("status").asText()).isEqualTo("CREATED");
        assertThat(jdbcClient.sql("select count(*) from order_electronic_waybill where order_id = :id")
                .param("id", orderId).query(Integer.class).single()).isOne();
    }

    @Test
    void staleRefreshGenerationPreventsLateProviderResultFromOverwritingRecovery() throws Exception {
        long orderId = insertPaidOrder("REFRESH-CAS");
        String token = token(List.of(MANAGE));
        long recordId = data(create(token, orderId, UUID.randomUUID().toString(), "1.000")
                .andExpect(status().isOk()).andReturn()).path("id").asLong();
        jdbcClient.sql("""
                        update order_electronic_waybill
                        set status = 'UNKNOWN', pending_operation = 'REFRESH',
                            last_attempt_at = timestamp '2020-01-01 00:00:00'
                        where id = :id
                        """).param("id", recordId).update();
        provider.blockNextGet(WechatElectronicWaybillResult.success(null, null, null, 1, null));

        CompletableFuture<MvcResult> stale = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post(
                                "/admin/orders/{orderId}/waybills/{recordId}/refresh", orderId, recordId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                        .andExpect(status().isOk()).andReturn();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(provider.getEntered.await(5, TimeUnit.SECONDS)).isTrue();

        jdbcClient.sql("""
                        update order_electronic_waybill
                        set last_attempt_at = timestamp '2020-01-01 00:00:00'
                        where id = :id
                        """).param("id", recordId).update();
        provider.getResult = WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.UNKNOWN, "SECOND_RESULT_UNKNOWN", "New recovery is unknown");
        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/refresh", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));

        provider.releaseGet.countDown();
        assertThat(data(stale.get(5, TimeUnit.SECONDS)).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbcClient.sql("""
                        select concat(status, ':', pending_operation, ':', last_error_code)
                        from order_electronic_waybill where id = :id
                        """).param("id", recordId).query(String.class).single())
                .isEqualTo("UNKNOWN:REFRESH:SECOND_RESULT_UNKNOWN");
        assertThat(provider.getRequests).hasSize(2);
    }

    @Test
    void refreshInFlightDisablesActionsAndCannotRaceShipmentConfirmation() throws Exception {
        long orderId = insertPaidOrder("REFRESH-CONFIRM");
        String manageToken = token(List.of(MANAGE));
        String printToken = token(List.of(PRINT));
        String testToken = token(List.of(TEST));
        String confirmToken = token(List.of(MANAGE, "order:ship"));
        long recordId = data(create(manageToken, orderId, UUID.randomUUID().toString(), "1.000")
                .andExpect(status().isOk()).andReturn()).path("id").asLong();
        provider.blockNextGet(WechatElectronicWaybillResult.success(null, null, null, 1, null));

        CompletableFuture<MvcResult> refresh = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post(
                                "/admin/orders/{orderId}/waybills/{recordId}/refresh", orderId, recordId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(manageToken)))
                        .andExpect(status().isOk()).andReturn();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(provider.getEntered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            mockMvc.perform(get("/admin/orders/{orderId}/waybills", orderId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(manageToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].status").value("CREATED"))
                    .andExpect(jsonPath("$.data[0].canRefresh").value(false))
                    .andExpect(jsonPath("$.data[0].canCancel").value(false))
                    .andExpect(jsonPath("$.data[0].canPrint").value(false))
                    .andExpect(jsonPath("$.data[0].canConfirmShipment").value(false))
                    .andExpect(jsonPath("$.data[0].canSimulate").value(false));

            mockMvc.perform(post(
                            "/admin/orders/{orderId}/waybills/{recordId}/confirm-shipment",
                            orderId, recordId
                    ).header(HttpHeaders.AUTHORIZATION, bearer(confirmToken)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_STATE_CONFLICT.code()));
            mockMvc.perform(post(
                            "/admin/orders/{orderId}/waybills/{recordId}/cancel", orderId, recordId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(manageToken)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.WECHAT_WAYBILL_CONFLICT.code()));
            mockMvc.perform(get(
                            "/admin/orders/{orderId}/waybills/{recordId}/print", orderId, recordId)
                            .param("printType", "0")
                            .header(HttpHeaders.AUTHORIZATION, bearer(printToken)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.WECHAT_WAYBILL_CONFLICT.code()));
            mockMvc.perform(post(
                            "/admin/orders/{orderId}/waybills/{recordId}/sandbox-events", orderId, recordId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(testToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actionType\":100001,\"actionMessage\":\"已揽件\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.WECHAT_WAYBILL_CONFLICT.code()));

            assertThat(jdbcClient.sql("select status from shop_order where id = :id")
                    .param("id", orderId).query(String.class).single()).isEqualTo("PAID");
            assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :id")
                    .param("id", orderId).query(Integer.class).single()).isZero();
            assertThat(provider.getRequests).hasSize(1);
            assertThat(provider.cancelRequests).isEmpty();
            assertThat(provider.testRequests).isEmpty();
        } finally {
            provider.releaseGet.countDown();
        }

        assertThat(data(refresh.get(5, TimeUnit.SECONDS)).path("status").asText())
                .isEqualTo("CANCELED");
        mockMvc.perform(post(
                        "/admin/orders/{orderId}/waybills/{recordId}/confirm-shipment",
                        orderId, recordId
                ).header(HttpHeaders.AUTHORIZATION, bearer(confirmToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_STATE_CONFLICT.code()));
        assertThat(jdbcClient.sql("select concat(status, ':', pending_operation) "
                        + "from order_electronic_waybill where id = :id")
                .param("id", recordId).query(String.class).single()).isEqualTo("CANCELED:NONE");
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :id")
                .param("id", orderId).query(Integer.class).single()).isZero();
    }

    @Test
    void unknownCreateAndCancelAreRecoveredOnlyThroughGet() throws Exception {
        long orderId = insertPaidOrder("RECOVER");
        String token = token(List.of(MANAGE));
        provider.addResult = WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.UNKNOWN, "REQUEST_AMBIGUOUS", "Result unknown");

        MvcResult created = create(token, orderId, UUID.randomUUID().toString(), "1.000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andReturn();
        long recordId = data(created).path("id").asLong();
        assertThat(provider.addRequests).hasSize(1);

        jdbcClient.sql("""
                        update order_electronic_waybill
                        set pending_operation = 'REFRESH',
                            last_attempt_at = timestamp '2020-01-01 00:00:00'
                        where id = :id
                        """).param("id", recordId).update();
        provider.getResult = WechatElectronicWaybillResult.success(
                null, null, null, 0, null);
        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/refresh", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"));
        assertThat(provider.addRequests).hasSize(1);
        assertThat(provider.getRequests).hasSize(1);

        provider.cancelResult = WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.REJECTED, "DELIVERY_REJECTED", "Carrier rejected cancellation");
        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/cancel", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.canCancel").value(true));

        provider.cancelResult = WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.UNKNOWN, "REQUEST_AMBIGUOUS", "Result unknown");
        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/cancel", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
        provider.getResult = WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.REJECTED, "ORDER_QUERY_REJECTED", "Query rejected");
        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/refresh", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.canCancel").value(false));
        provider.getResult = WechatElectronicWaybillResult.success(
                null, null, null, 1, null);
        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/refresh", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());

        assertThat(provider.cancelRequests).hasSize(2);
        assertThat(provider.getRequests).hasSize(3);
        assertThat(provider.networkTransactionActive).isFalse();
    }

    @Test
    void printFetchesSameProviderOrderReturnsNoStoreHtmlAndStoresNoLabel() throws Exception {
        long orderId = insertPaidOrder("PRINT");
        String manageToken = token(List.of(MANAGE));
        String printToken = token(List.of(PRINT));
        long recordId = data(create(manageToken, orderId, UUID.randomUUID().toString(), "1.000")
                .andExpect(status().isOk()).andReturn()).path("id").asLong();
        provider.printHtml = "<html><body>TEST LABEL</body></html>";

        mockMvc.perform(get("/admin/orders/{orderId}/waybills/{recordId}/print", orderId, recordId)
                        .param("printType", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(manageToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/orders/{orderId}/waybills/{recordId}/print", orderId, recordId)
                        .param("printType", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(printToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(provider.printHtml));

        assertThat(provider.addRequests).hasSize(1);
        assertThat(provider.getRequests).hasSize(1);
        assertThat(provider.getRequests.getFirst().printType()).isZero();
        assertThat(jdbcClient.sql("select print_request_count from order_electronic_waybill where id = :id")
                .param("id", recordId).query(Integer.class).single()).isOne();

        provider.blockNextGet(WechatElectronicWaybillResult.success(null, null, null, 0, null));
        CompletableFuture<MvcResult> racingPrint = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(get(
                                "/admin/orders/{orderId}/waybills/{recordId}/print", orderId, recordId)
                                .param("printType", "0")
                                .header(HttpHeaders.AUTHORIZATION, bearer(printToken)))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code")
                                .value(ErrorCode.WECHAT_WAYBILL_CONFLICT.code()))
                        .andReturn();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(provider.getEntered.await(5, TimeUnit.SECONDS)).isTrue();
        jdbcClient.sql("""
                        update order_electronic_waybill
                        set pending_operation = 'REFRESH', updated_at = current_timestamp
                        where id = :id
                        """).param("id", recordId).update();
        provider.releaseGet.countDown();
        racingPrint.get(5, TimeUnit.SECONDS);
        assertThat(jdbcClient.sql("select print_request_count from order_electronic_waybill where id = :id")
                .param("id", recordId).query(Integer.class).single()).isOne();
        assertThat(jdbcClient.sql("""
                        select count(*) from information_schema.columns
                        where lower(table_name) = 'order_electronic_waybill'
                          and lower(column_name) like '%html%'
                        """).query(Integer.class).single()).isZero();
        assertThat(provider.networkTransactionActive).isFalse();
    }

    @Test
    void sandboxEventsRequireDedicatedPermissionWhitelistAndEffectiveTestIdentity() throws Exception {
        long orderId = insertPaidOrder("SANDBOX");
        String manageToken = token(List.of(MANAGE));
        String testToken = token(List.of(TEST));
        long recordId = data(create(manageToken, orderId, UUID.randomUUID().toString(), "1.000")
                .andExpect(status().isOk()).andReturn()).path("id").asLong();

        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/sandbox-events", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(manageToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":100001,\"actionMessage\":\"已揽件\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/sandbox-events", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(testToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":999999,\"actionMessage\":\"伪造事件\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/sandbox-events", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(testToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":100001,\"actionMessage\":\"已揽件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canSimulate").value(true));
        assertThat(provider.testRequests).hasSize(1);
        assertThat(provider.testRequests.getFirst().deliveryId()).isEqualTo("TEST");
        assertThat(provider.testRequests.getFirst().bizId()).isEqualTo("test_biz_id");

        jdbcClient.sql("update wechat_express_setting set mode = 'DISABLED' where id = 1").update();
        mockMvc.perform(post("/admin/orders/{orderId}/waybills/{recordId}/sandbox-events", orderId, recordId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(testToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":200001,\"actionMessage\":\"运输中\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.WECHAT_WAYBILL_CONFLICT.code()));
        assertThat(provider.testRequests).hasSize(1);
    }

    @Test
    void listAndAdminOrderDetailExposeSafePreShipmentSummary() throws Exception {
        long orderId = insertPaidOrder("SUMMARY");
        String manageToken = token(List.of(MANAGE));
        String readToken = token(List.of("order:read"));
        long recordId = data(create(manageToken, orderId, UUID.randomUUID().toString(), "1.000")
                .andExpect(status().isOk()).andReturn()).path("id").asLong();

        mockMvc.perform(get("/admin/orders/{orderId}/waybills", orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(manageToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(recordId))
                .andExpect(jsonPath("$.data[0].waybillNo").isNotEmpty());
        mockMvc.perform(get("/admin/orders/{orderId}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(readToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment").doesNotExist())
                .andExpect(jsonPath("$.data.electronicWaybill.id").value(recordId))
                .andExpect(jsonPath("$.data.electronicWaybill.status").value("CREATED"));
    }

    private JsonNode context(String token, long orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/orders/{orderId}/waybills/context", orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private void assertBlocker(String token, long orderId, String expectedText) throws Exception {
        JsonNode blockers = context(token, orderId).path("blockers");
        assertThat(blockers.toString()).contains(expectedText);
    }

    private org.springframework.test.web.servlet.ResultActions create(
            String token,
            long orderId,
            String idempotencyKey,
            String weight
    ) throws Exception {
        return mockMvc.perform(post("/admin/orders/{orderId}/waybills", orderId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "idempotencyKey":"%s",
                          "count":1,
                          "weightKg":%s,
                          "lengthCm":20.00,
                          "widthCm":15.00,
                          "heightCm":10.00,
                          "remark":"请轻放",
                          "expectTime":1783504800
                        }
                        """.formatted(idempotencyKey, weight)));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void enableSandbox() {
        jdbcClient.sql("""
                        update wechat_express_setting
                        set mode = 'SANDBOX',
                            sender_name = '沐宝仓库', sender_mobile = '13800138000',
                            sender_company = '沐宝商城', sender_province = '广东省',
                            sender_city = '深圳市', sender_district = '南山区',
                            sender_detail_address = '科技园1号',
                            default_weight_kg = 1.000, default_length_cm = 20.00,
                            default_width_cm = 15.00, default_height_cm = 10.00
                        where id = 1
                        """).update();
    }

    private long insertPaidOrder(String suffix) {
        long orderId = ORDER_SEQUENCE.incrementAndGet();
        String orderNo = "WB-" + suffix + "-" + orderId;
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            product_original_amount_cent, product_amount_cent,
                            coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent,
                            receiver_name, receiver_phone, receiver_address,
                            receiver_province, receiver_city, receiver_district,
                            receiver_detail_address, receiver_location_name, receiver_doorplate,
                            payment_transaction_id, merchant_trade_no, paid_at, created_at, updated_at)
                        values(
                            :id, :orderNo, 900001, 'PAID', 'CART', :key,
                            3980, 3980, 0, 0, 3980, 3980,
                            '测试买家', '13900139000', '广东省深圳市南山区学苑大道南山智园A座101',
                            '广东省', '深圳市', '南山区', '学苑大道', '南山智园', 'A座101',
                            :transactionId, :outTradeNo, current_timestamp,
                            current_timestamp, current_timestamp)
                        """)
                .param("id", orderId)
                .param("orderNo", orderNo)
                .param("key", "checkout-" + orderId)
                .param("transactionId", "wx-" + orderId)
                .param("outTradeNo", "MCH-" + orderId)
                .update();
        jdbcClient.sql("""
                        insert into order_item(
                            order_id, sku_id, spu_id, product_title, product_subtitle,
                            main_image, sku_image, display_image, sku_code, spec_text,
                            original_price_cent, unit_price_cent, quantity,
                            line_original_amount_cent, line_amount_cent, created_at)
                        values(
                            :orderId, 1, 1, '婴儿湿巾', '温和无香',
                            'https://cdn.example.test/item.jpg',
                            'https://cdn.example.test/item.jpg',
                            'https://cdn.example.test/item.jpg',
                            :skuCode, '80抽', 3980, 3980, 1, 3980, 3980, current_timestamp)
                        """)
                .param("orderId", orderId)
                .param("skuCode", "SKU-" + orderId)
                .update();
        jdbcClient.sql("""
                        insert into payment_order(
                            order_id, payment_config_id, out_trade_no, prepay_id, transaction_id,
                            payer_openid, status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values(
                            :orderId, null, :outTradeNo, :prepayId, :transactionId,
                            :openid, 'PAID', 3980, current_timestamp, current_timestamp,
                            current_timestamp, current_timestamp)
                        """)
                .param("orderId", orderId)
                .param("outTradeNo", "MCH-" + orderId)
                .param("prepayId", "PREPAY-" + orderId)
                .param("transactionId", "wx-" + orderId)
                .param("openid", "openid-" + orderId)
                .update();
        return orderId;
    }

    private void insertShipment(long orderId) {
        jdbcClient.sql("""
                        insert into order_shipment(
                            order_id, express_company_name, express_company_code, tracking_no,
                            shipment_note, status, wechat_upload_status, shipped_at)
                        values(:orderId, '测试快递', 'TEST', 'TEST-TRACKING', '',
                               'SHIPPED', 'SKIPPED', current_timestamp)
                        """).param("orderId", orderId).update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {

        @Bean
        @Primary
        RecordingWaybillProvider recordingWaybillProvider(JdbcClient jdbcClient) {
            return new RecordingWaybillProvider(jdbcClient);
        }
    }

    static class RecordingWaybillProvider implements WechatElectronicWaybillProvider {

        private final JdbcClient jdbcClient;
        private final List<WechatElectronicWaybillAddRequest> addRequests = new CopyOnWriteArrayList<>();
        private final List<WechatElectronicWaybillGetRequest> getRequests = new CopyOnWriteArrayList<>();
        private final List<WechatElectronicWaybillCancelRequest> cancelRequests = new CopyOnWriteArrayList<>();
        private final List<WechatElectronicWaybillTestUpdateRequest> testRequests = new CopyOnWriteArrayList<>();
        private WechatElectronicWaybillResult addResult;
        private WechatElectronicWaybillResult getResult;
        private WechatElectronicWaybillResult cancelResult;
        private WechatElectronicWaybillResult testResult;
        private String printHtml;
        private boolean networkTransactionActive;
        private boolean sawCommittedCreating;
        private CountDownLatch addEntered;
        private CountDownLatch releaseAdd;
        private CountDownLatch getEntered;
        private CountDownLatch releaseGet;
        private WechatElectronicWaybillResult blockedGetResult;

        RecordingWaybillProvider(JdbcClient jdbcClient) {
            this.jdbcClient = jdbcClient;
            reset();
        }

        void reset() {
            addRequests.clear();
            getRequests.clear();
            cancelRequests.clear();
            testRequests.clear();
            addResult = null;
            getResult = null;
            cancelResult = null;
            testResult = null;
            printHtml = "<html><body>MOCK LABEL</body></html>";
            networkTransactionActive = false;
            sawCommittedCreating = false;
            addEntered = new CountDownLatch(0);
            releaseAdd = new CountDownLatch(0);
            getEntered = new CountDownLatch(0);
            releaseGet = new CountDownLatch(0);
            blockedGetResult = null;
        }

        void blockAdd() {
            addEntered = new CountDownLatch(1);
            releaseAdd = new CountDownLatch(1);
        }

        synchronized void blockNextGet(WechatElectronicWaybillResult result) {
            blockedGetResult = result;
            getEntered = new CountDownLatch(1);
            releaseGet = new CountDownLatch(1);
        }

        @Override
        public WechatElectronicWaybillResult add(WechatElectronicWaybillAddRequest request) {
            recordTransactionState();
            addRequests.add(request);
            sawCommittedCreating = "CREATING".equals(jdbcClient.sql(
                            "select status from order_electronic_waybill where id = :id")
                    .param("id", request.localRecordId()).query(String.class).single());
            addEntered.countDown();
            awaitRelease();
            return addResult == null
                    ? WechatElectronicWaybillResult.success(
                    request.providerOrderId(), request.deliveryId(),
                    "TEST-WAYBILL-" + request.localRecordId(), 0, null)
                    : addResult;
        }

        @Override
        public WechatElectronicWaybillResult get(WechatElectronicWaybillGetRequest request) {
            recordTransactionState();
            getRequests.add(request);
            WechatElectronicWaybillResult configured = getResult;
            CountDownLatch entered = null;
            CountDownLatch release = null;
            synchronized (this) {
                if (blockedGetResult != null) {
                    configured = blockedGetResult;
                    blockedGetResult = null;
                    entered = getEntered;
                    release = releaseGet;
                }
            }
            if (entered != null) {
                entered.countDown();
                await(release, "get");
            }
            if (configured != null && configured.outcome() != WechatProviderOutcome.SUCCESS) {
                return configured;
            }
            Integer orderStatus = configured == null ? 0 : configured.orderStatus();
            String waybillId = request.waybillId() == null || request.waybillId().isBlank()
                    ? "TEST-WAYBILL-" + request.localRecordId()
                    : request.waybillId();
            return WechatElectronicWaybillResult.success(
                    request.providerOrderId(), request.deliveryId(), waybillId, orderStatus,
                    request.printType() == null ? null : encode(printHtml));
        }

        @Override
        public WechatElectronicWaybillResult cancel(WechatElectronicWaybillCancelRequest request) {
            recordTransactionState();
            cancelRequests.add(request);
            return cancelResult == null
                    ? WechatElectronicWaybillResult.success(
                    request.providerOrderId(), request.deliveryId(), request.waybillId(), 1, null)
                    : cancelResult;
        }

        @Override
        public WechatElectronicWaybillResult testUpdate(WechatElectronicWaybillTestUpdateRequest request) {
            recordTransactionState();
            testRequests.add(request);
            return testResult == null
                    ? WechatElectronicWaybillResult.success(null, null, null, null, null)
                    : testResult;
        }

        private void recordTransactionState() {
            networkTransactionActive |= TransactionSynchronizationManager.isActualTransactionActive();
        }

        private void awaitRelease() {
            await(releaseAdd, "add");
        }

        private void await(CountDownLatch release, String operation) {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(operation + " barrier timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(operation + " interrupted", ex);
            }
        }

        private String encode(String html) {
            return Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        }
    }
}
