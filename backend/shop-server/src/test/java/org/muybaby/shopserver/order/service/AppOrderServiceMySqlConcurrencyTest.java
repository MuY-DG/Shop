package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;
import org.muybaby.shopserver.order.dto.OrderReceiptResponse;
import org.muybaby.shopserver.order.dto.OrderSubmitResponse;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(AppOrderServiceMySqlConcurrencyTest.RaceConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppOrderServiceMySqlConcurrencyTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(86_000L);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("order_idempotency")
            .withUsername("shop_test")
            .withPassword("shop_test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private AppOrderService appOrderService;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OrderRaceProbe raceProbe;

    @BeforeEach
    void clearState() {
        raceProbe.reset();
        jdbcClient.sql("delete from stock_lock").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from stock_log").update();
        jdbcClient.sql("delete from cart_item").update();
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_template").update();
        jdbcClient.sql("delete from user_address").update();
        jdbcClient.sql("delete from product_sku").update();
        jdbcClient.sql("delete from product_spu_image").update();
        jdbcClient.sql("delete from product_spu").update();
        jdbcClient.sql("delete from product_category").update();
    }

    @AfterEach
    void disarmProbe() {
        raceProbe.reset();
    }

    @Test
    void sameKeyAndDigestThreadsReturnOneOrderAfterWinnerDeletesCartRows() throws Exception {
        Fixture fixture = fixture("MYSQL-SAME", true, 2);
        AppOrderSubmitRequest request = cartRequest(
                fixture.cartItemId(), fixture.addressId(), fixture.userCouponId(), "mysql-same-key");
        raceProbe.armUserGateBarrier();

        List<Attempt> attempts = runRacing(
                () -> attempt(fixture.userId(), request),
                () -> attempt(fixture.userId(), request)
        );

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.error()).isNull();
            assertThat(attempt.response()).isNotNull();
        });
        assertThat(attempts.get(0).response().orderId()).isEqualTo(attempts.get(1).response().orderId());
        assertSingleCommittedCheckout(fixture, 2);
        assertThat(jdbcClient.sql("select status from user_coupon where id = :couponId")
                .param("couponId", fixture.userCouponId()).query(String.class).single()).isEqualTo("LOCKED");
    }

    @Test
    void concurrentReceiptConfirmationsSerializeAndReturnOnePersistedCompletionTime() throws Exception {
        long userId = insertUser("mysql-receipt-race");
        long orderId = SEQUENCE.incrementAndGet();
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 10, 14, 0);
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent,
                             receiver_name, receiver_phone, receiver_address, shipped_at, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, 'SHIPPED', 'CART', :idempotencyKey,
                             1000, 1000, 0, 0, 1000, 1000,
                             'Race Receiver', '13800138000', 'Race Address', :shippedAt, :createdAt, :createdAt)
                        """)
                .param("orderId", orderId)
                .param("orderNo", "MYSQL-RECEIPT-" + orderId)
                .param("userId", userId)
                .param("idempotencyKey", "mysql-receipt-" + orderId)
                .param("shippedAt", createdAt.plusHours(1))
                .param("createdAt", createdAt)
                .update();

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OrderReceiptResponse> first = executor.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return appOrderService.confirmReceipt(principal(userId), orderId);
            });
            Future<OrderReceiptResponse> second = executor.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return appOrderService.confirmReceipt(principal(userId), orderId);
            });

            OrderReceiptResponse firstResult = first.get(30, TimeUnit.SECONDS);
            OrderReceiptResponse secondResult = second.get(30, TimeUnit.SECONDS);
            LocalDateTime stored = jdbcClient.sql("select completed_at from shop_order where id = :orderId")
                    .param("orderId", orderId)
                    .query(LocalDateTime.class)
                    .single();

            assertThat(firstResult.status()).isEqualTo("COMPLETED");
            assertThat(secondResult.status()).isEqualTo("COMPLETED");
            assertThat(firstResult.completedAt()).isEqualTo(stored);
            assertThat(secondResult.completedAt()).isEqualTo(stored);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void sameKeyAndDifferentDigestsProduceOneOrderAndOneConflict() throws Exception {
        long userId = insertUser("mysql-different");
        long addressId = insertAddress(userId, "Different Digest");
        long skuA = insertSku("MYSQL-DIFFERENT-A", 12);
        long skuB = insertSku("MYSQL-DIFFERENT-B", 12);
        long cartA = insertCartItem(userId, skuA, 1);
        long cartB = insertCartItem(userId, skuB, 3);
        long couponId = insertCoupon(userId, "MySQL Different Coupon", 500L);
        raceProbe.armUserGateBarrier();

        List<Attempt> attempts = runRacing(
                () -> attempt(userId, cartRequest(cartA, addressId, couponId, "mysql-different-key")),
                () -> attempt(userId, cartRequest(cartB, addressId, couponId, "mysql-different-key"))
        );

        assertThat(attempts.stream().filter(attempt -> attempt.response() != null)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> attempt.error() instanceof BusinessException businessException
                && businessException.errorCode() == ErrorCode.ORDER_STATE_CONFLICT)).hasSize(1);
        assertThat(jdbcClient.sql("select count(*) from shop_order where user_id = :userId and idempotency_key = 'mysql-different-key'")
                .param("userId", userId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from order_item").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from stock_lock").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from stock_log where change_type = 'ORDER_LOCK'")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from cart_item where user_id = :userId")
                .param("userId", userId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select status from user_coupon where id = :couponId")
                .param("couponId", couponId).query(String.class).single()).isEqualTo("LOCKED");
    }

    @Test
    void waitingUserGateTakesOwnershipWhenFirstTransactionRollsBackBeforeSelectionCompletes() throws Exception {
        Fixture fixture = fixture("MYSQL-ROLLBACK", false, 2);
        AppOrderSubmitRequest request = cartRequest(
                fixture.cartItemId(), fixture.addressId(), null, "mysql-rollback-key");
        raceProbe.armRollbackTakeover();

        ExecutorService ownerExecutor = namedSingleThread("rollback-owner");
        ExecutorService waiterExecutor = namedSingleThread("rollback-waiter");
        try {
            Future<Attempt> owner = ownerExecutor.submit(() -> attempt(fixture.userId(), request));
            raceProbe.awaitOwnerAtSelection();
            Future<Attempt> waiter = waiterExecutor.submit(() -> attempt(fixture.userId(), request));

            Attempt failedOwner = owner.get(30, TimeUnit.SECONDS);
            Attempt completedWaiter = waiter.get(30, TimeUnit.SECONDS);

            assertThat(failedOwner.response()).isNull();
            assertThat(failedOwner.error()).isNotNull();
            assertThat(completedWaiter.error()).isNull();
            assertThat(completedWaiter.response()).isNotNull();
            assertSingleCommittedCheckout(fixture, 2);
            assertThat(jdbcClient.sql("""
                            select checkout_request_digest
                            from shop_order
                            where user_id = :userId and idempotency_key = 'mysql-rollback-key'
                            """)
                    .param("userId", fixture.userId()).query(String.class).single()).hasSize(64);
        } finally {
            shutdown(ownerExecutor);
            shutdown(waiterExecutor);
        }
    }

    @Test
    void cartSubmitUsesQuantityCommittedBeforeCartLockAndDeletesOnlySelectedRow() throws Exception {
        Fixture fixture = fixture("MYSQL-CART-REFRESH", false, 2);
        long untouchedSkuId = insertSku("MYSQL-CART-UNTOUCHED", 9);
        long untouchedCartItemId = insertCartItem(fixture.userId(), untouchedSkuId, 1);
        AppOrderSubmitRequest request = cartRequest(
                fixture.cartItemId(), fixture.addressId(), null, "mysql-cart-refresh-key");
        raceProbe.armCartQuantityRefresh();

        ExecutorService ownerExecutor = namedSingleThread("cart-quantity-owner");
        try {
            Future<Attempt> owner = ownerExecutor.submit(() -> attempt(fixture.userId(), request));
            raceProbe.awaitCartLockAttempt();
            jdbcClient.sql("update cart_item set quantity = 4 where id = :cartItemId")
                    .param("cartItemId", fixture.cartItemId())
                    .update();
            raceProbe.releaseCartLock();

            Attempt completed = owner.get(30, TimeUnit.SECONDS);

            assertThat(completed.error()).isNull();
            assertThat(completed.response()).isNotNull();
            assertThat(jdbcClient.sql("select quantity from order_item where order_id = :orderId")
                    .param("orderId", completed.response().orderId()).query(Integer.class).single()).isEqualTo(4);
            assertThat(jdbcClient.sql("select product_amount_cent from shop_order where id = :orderId")
                    .param("orderId", completed.response().orderId()).query(Long.class).single()).isEqualTo(15_960L);
            assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                    .param("skuId", fixture.skuId()).query(Integer.class).single()).isEqualTo(8);
            assertThat(jdbcClient.sql("select count(*) from cart_item where id = :cartItemId")
                    .param("cartItemId", fixture.cartItemId()).query(Long.class).single()).isZero();
            assertThat(jdbcClient.sql("select quantity from cart_item where id = :cartItemId")
                    .param("cartItemId", untouchedCartItemId).query(Integer.class).single()).isEqualTo(1);
        } finally {
            raceProbe.releaseCartLock();
            shutdown(ownerExecutor);
        }
    }

    @Test
    void distinctOrdersForSameSkuWriteContinuousStockLogChain() throws Exception {
        long firstUserId = insertUser("mysql-stock-chain-a");
        long secondUserId = insertUser("mysql-stock-chain-b");
        long firstAddressId = insertAddress(firstUserId, "Stock Chain A");
        long secondAddressId = insertAddress(secondUserId, "Stock Chain B");
        long skuId = insertSku("MYSQL-STOCK-CHAIN", 12);
        raceProbe.armInitialReadBarrier();

        List<Attempt> attempts = runRacing(
                () -> attempt(firstUserId, directRequest(skuId, 2, firstAddressId, "mysql-stock-chain-a")),
                () -> attempt(secondUserId, directRequest(skuId, 2, secondAddressId, "mysql-stock-chain-b"))
        );

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.error()).isNull();
            assertThat(attempt.response()).isNotNull();
        });
        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", skuId).query(Integer.class).single()).isEqualTo(8);
        List<StockLogRow> logs = jdbcClient.sql("""
                        select quantity_before, quantity_delta, quantity_after
                        from stock_log
                        where sku_id = :skuId and change_type = 'ORDER_LOCK'
                        order by id asc
                        """)
                .param("skuId", skuId)
                .query((rs, rowNum) -> new StockLogRow(
                        rs.getInt("quantity_before"),
                        rs.getInt("quantity_delta"),
                        rs.getInt("quantity_after")))
                .list();
        assertThat(logs).containsExactly(
                new StockLogRow(12, -2, 10),
                new StockLogRow(10, -2, 8)
        );
    }

    @Test
    void checkoutAndAdminSpuUpdateCommitInGlobalLockOrderAndCheckoutUsesCurrentProductState() throws Exception {
        Fixture fixture = fixture("MYSQL-PRODUCT-LOCK-ORDER", false, 2);
        long spuId = jdbcClient.sql("select spu_id from product_sku where id = :skuId")
                .param("skuId", fixture.skuId()).query(Long.class).single();
        long categoryId = jdbcClient.sql("select category_id from product_spu where id = :spuId")
                .param("spuId", spuId).query(Long.class).single();
        String skuCode = jdbcClient.sql("select sku_code from product_sku where id = :skuId")
                .param("skuId", fixture.skuId()).query(String.class).single();
        AdminSpuUpsertRequest updateRequest = new AdminSpuUpsertRequest(
                categoryId,
                "Admin Updated Product",
                "Current checkout state",
                "https://example.test/admin-updated.jpg",
                null,
                "updated",
                "<p>updated</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(
                        fixture.skuId(), skuCode, "{}", "300g", 4_590L, 5_490L,
                        12, 300, "", null, "ENABLED", 1))
        );
        AppOrderSubmitRequest checkoutRequest = cartRequest(
                fixture.cartItemId(), fixture.addressId(), null, "mysql-product-lock-order");
        raceProbe.armProductLockOrderRace();

        ExecutorService adminExecutor = namedSingleThread("product-admin");
        ExecutorService checkoutExecutor = namedSingleThread("product-checkout");
        try {
            Future<Throwable> admin = adminExecutor.submit(() -> updateSpu(spuId, updateRequest));
            raceProbe.awaitAdminSpuLock();
            Future<Attempt> checkout = checkoutExecutor.submit(() -> attempt(fixture.userId(), checkoutRequest));
            raceProbe.awaitCheckoutProductLock();
            raceProbe.releaseAdminSpuLock();

            Throwable adminError = admin.get(30, TimeUnit.SECONDS);
            Attempt completedCheckout = checkout.get(30, TimeUnit.SECONDS);

            assertThat(adminError).isNull();
            assertThat(completedCheckout.error()).isNull();
            assertThat(completedCheckout.response()).isNotNull();
            assertThat(jdbcClient.sql("select title from product_spu where id = :spuId")
                    .param("spuId", spuId).query(String.class).single()).isEqualTo("Admin Updated Product");
            assertThat(jdbcClient.sql("""
                            select product_title, unit_price_cent, quantity, line_amount_cent
                            from order_item
                            where order_id = :orderId
                            """)
                    .param("orderId", completedCheckout.response().orderId())
                    .query()
                    .singleRow())
                    .containsEntry("PRODUCT_TITLE", "Admin Updated Product")
                    .containsEntry("UNIT_PRICE_CENT", 4_590L)
                    .containsEntry("QUANTITY", 2)
                    .containsEntry("LINE_AMOUNT_CENT", 9_180L);
            assertThat(jdbcClient.sql("select product_amount_cent from shop_order where id = :orderId")
                    .param("orderId", completedCheckout.response().orderId()).query(Long.class).single()).isEqualTo(9_180L);
            assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                    .param("skuId", fixture.skuId()).query(Integer.class).single()).isEqualTo(10);
            assertThat(jdbcClient.sql("select count(*) from cart_item where id = :cartItemId")
                    .param("cartItemId", fixture.cartItemId()).query(Long.class).single()).isZero();
        } finally {
            raceProbe.releaseAdminSpuLock();
            shutdown(adminExecutor);
            shutdown(checkoutExecutor);
        }
    }

    private List<Attempt> runRacing(ThrowingSupplier<Attempt> first, ThrowingSupplier<Attempt> second) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2,
                runnable -> new Thread(runnable, "mysql-race-" + counter.incrementAndGet()));
        try {
            CompletableFuture<Attempt> firstFuture = CompletableFuture.supplyAsync(() -> unchecked(first), executor);
            CompletableFuture<Attempt> secondFuture = CompletableFuture.supplyAsync(() -> unchecked(second), executor);
            return List.of(
                    firstFuture.get(30, TimeUnit.SECONDS),
                    secondFuture.get(30, TimeUnit.SECONDS)
            );
        } finally {
            shutdown(executor);
        }
    }

    private Attempt attempt(long userId, AppOrderSubmitRequest request) {
        try {
            return new Attempt(appOrderService.submit(principal(userId), request), null);
        } catch (Throwable throwable) {
            return new Attempt(null, throwable);
        }
    }

    private Throwable updateSpu(long spuId, AdminSpuUpsertRequest request) {
        try {
            adminProductService.updateSpu(spuId, request, 7L);
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private Attempt unchecked(ThrowingSupplier<Attempt> supplier) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertSingleCommittedCheckout(Fixture fixture, int quantity) {
        assertThat(jdbcClient.sql("select count(*) from shop_order where user_id = :userId")
                .param("userId", fixture.userId()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from order_item").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from stock_lock").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from stock_log where change_type = 'ORDER_LOCK'")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", fixture.skuId()).query(Integer.class).single()).isEqualTo(12 - quantity);
        assertThat(jdbcClient.sql("select count(*) from cart_item where id = :cartItemId")
                .param("cartItemId", fixture.cartItemId()).query(Long.class).single()).isZero();
    }

    private Fixture fixture(String suffix, boolean withCoupon, int quantity) {
        long userId = insertUser(suffix);
        long addressId = insertAddress(userId, suffix);
        long skuId = insertSku(suffix, 12);
        long cartItemId = insertCartItem(userId, skuId, quantity);
        Long couponId = withCoupon ? insertCoupon(userId, suffix + " Coupon", 500L) : null;
        return new Fixture(userId, addressId, skuId, cartItemId, couponId);
    }

    private AppOrderSubmitRequest cartRequest(long cartItemId, long addressId, Long couponId, String key) {
        return new AppOrderSubmitRequest(
                CheckoutSource.CART, List.of(cartItemId), null, null, addressId, couponId, key);
    }

    private AppOrderSubmitRequest directRequest(long skuId, int quantity, long addressId, String key) {
        return new AppOrderSubmitRequest(
                CheckoutSource.DIRECT, List.of(), skuId, quantity, addressId, null, key);
    }

    private AuthenticatedPrincipal principal(long userId) {
        return new AuthenticatedPrincipal(TokenKind.APP, userId, "mysql-user-" + userId, List.of(), List.of());
    }

    private long insertUser(String suffix) {
        long id = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into app_user (id, openid, unionid, status, last_login_at, created_at, updated_at)
                        values (:id, :openid, :unionid, 'ENABLED', :now, :now, :now)
                        """)
                .param("id", id).param("openid", suffix + id).param("unionid", suffix + "-union-" + id)
                .param("now", now).update();
        return id;
    }

    private long insertAddress(long userId, String suffix) {
        jdbcClient.sql("""
                        insert into user_address
                            (user_id, receiver_name, receiver_phone, province, city, district, detail_address, is_default)
                        values (:userId, :name, '13800138000', '北京市', '', '朝阳区', :detail, true)
                        """)
                .param("userId", userId).param("name", "Receiver " + suffix)
                .param("detail", "Hotpot road " + suffix).update();
        return jdbcClient.sql("select max(id) from user_address where user_id = :userId")
                .param("userId", userId).query(Long.class).single();
    }

    private long insertSku(String suffix, int stock) {
        long sequence = SEQUENCE.incrementAndGet();
        jdbcClient.sql("insert into product_category (parent_id, name, icon, sort_order, status) values (0, :name, '', 1, 'ENABLED')")
                .param("name", "MySQL Category " + suffix + sequence).update();
        long categoryId = jdbcClient.sql("select max(id) from product_category").query(Long.class).single();
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html,
                             compliance_type, sort_order, status)
                        values (:categoryId, :title, '', '', '', '', 'NON_FOOD', 1, 'ON_SALE')
                        """)
                .param("categoryId", categoryId).param("title", "MySQL Product " + suffix).update();
        long spuId = jdbcClient.sql("select max(id) from product_spu").query(Long.class).single();
        String skuCode = suffix + "-" + sequence;
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, image, status, sort_order)
                        values (:spuId, :skuCode, '{}', '300g', 3990, 4990,
                                :stock, 300, '', 'ENABLED', 1)
                        """)
                .param("spuId", spuId).param("skuCode", skuCode).param("stock", stock).update();
        return jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode).query(Long.class).single();
    }

    private long insertCartItem(long userId, long skuId, int quantity) {
        jdbcClient.sql("insert into cart_item (user_id, sku_id, quantity) values (:userId, :skuId, :quantity)")
                .param("userId", userId).param("skuId", skuId).param("quantity", quantity).update();
        return jdbcClient.sql("select id from cart_item where user_id = :userId and sku_id = :skuId")
                .param("userId", userId).param("skuId", skuId).query(Long.class).single();
    }

    private long insertCoupon(long userId, String name, long discountCent) {
        String uniqueName = name + SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values (:name, '', 'NO_THRESHOLD', 'AMOUNT_OFF', 0, :discount,
                                'ALL', '', 'coupon.amount-off.v1', 10, 1, 1,
                                :startAt, :endAt, 'ENABLED', 1)
                        """)
                .param("name", uniqueName).param("discount", discountCent)
                .param("startAt", now.minusDays(1)).param("endAt", now.plusDays(1)).update();
        long templateId = jdbcClient.sql("select id from coupon_template where name = :name")
                .param("name", uniqueName).query(Long.class).single();
        jdbcClient.sql("""
                        insert into user_coupon
                            (user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value,
                             valid_start_at, valid_end_at, status, claimed_at)
                        values (:userId, :templateId, :name, 'NO_THRESHOLD', 'AMOUNT_OFF',
                                0, :discount, 'ALL', '', :startAt, :endAt, 'CLAIMED', :now)
                        """)
                .param("userId", userId).param("templateId", templateId).param("name", uniqueName)
                .param("discount", discountCent).param("startAt", now.minusDays(1))
                .param("endAt", now.plusDays(1)).param("now", now).update();
        return jdbcClient.sql("select id from user_coupon where user_id = :userId and template_id = :templateId")
                .param("userId", userId).param("templateId", templateId).query(Long.class).single();
    }

    private ExecutorService namedSingleThread(String name) {
        return Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, name));
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceConfiguration {

        @Bean
        OrderRaceProbe orderRaceProbe() {
            return new OrderRaceProbe();
        }

        @Bean
        static BeanPostProcessor orderRaceDataSourcePostProcessor(OrderRaceProbe probe) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && !(bean instanceof ProbeDataSource)) {
                        return new ProbeDataSource(dataSource, probe);
                    }
                    return bean;
                }
            };
        }
    }

    static final class OrderRaceProbe {

        private enum Mode {
            NONE,
            USER_GATE_BARRIER,
            INITIAL_READ_BARRIER,
            ROLLBACK_TAKEOVER,
            CART_QUANTITY_REFRESH,
            PRODUCT_LOCK_ORDER_RACE
        }

        private volatile Mode mode = Mode.NONE;
        private volatile CyclicBarrier userGateBarrier = new CyclicBarrier(2);
        private volatile CyclicBarrier initialReadBarrier = new CyclicBarrier(2);
        private volatile CountDownLatch ownerAtSelection = new CountDownLatch(0);
        private volatile CountDownLatch waiterUserGateAttempted = new CountDownLatch(0);
        private volatile CountDownLatch cartLockAttempted = new CountDownLatch(0);
        private volatile CountDownLatch cartLockReleased = new CountDownLatch(0);
        private volatile CountDownLatch adminSpuLocked = new CountDownLatch(0);
        private volatile CountDownLatch adminSpuReleased = new CountDownLatch(0);
        private volatile CountDownLatch checkoutProductLockReached = new CountDownLatch(0);
        private final AtomicBoolean ownerFailureInjected = new AtomicBoolean();

        void armUserGateBarrier() {
            userGateBarrier = new CyclicBarrier(2);
            mode = Mode.USER_GATE_BARRIER;
        }

        void armInitialReadBarrier() {
            initialReadBarrier = new CyclicBarrier(2);
            mode = Mode.INITIAL_READ_BARRIER;
        }

        void armRollbackTakeover() {
            ownerAtSelection = new CountDownLatch(1);
            waiterUserGateAttempted = new CountDownLatch(1);
            ownerFailureInjected.set(false);
            mode = Mode.ROLLBACK_TAKEOVER;
        }

        void armCartQuantityRefresh() {
            cartLockAttempted = new CountDownLatch(1);
            cartLockReleased = new CountDownLatch(1);
            mode = Mode.CART_QUANTITY_REFRESH;
        }

        void armProductLockOrderRace() {
            adminSpuLocked = new CountDownLatch(1);
            adminSpuReleased = new CountDownLatch(1);
            checkoutProductLockReached = new CountDownLatch(1);
            mode = Mode.PRODUCT_LOCK_ORDER_RACE;
        }

        void reset() {
            mode = Mode.NONE;
            userGateBarrier.reset();
            initialReadBarrier.reset();
            if (ownerAtSelection.getCount() > 0) {
                ownerAtSelection.countDown();
            }
            if (waiterUserGateAttempted.getCount() > 0) {
                waiterUserGateAttempted.countDown();
            }
            releaseCartLock();
            releaseAdminSpuLock();
        }

        void awaitOwnerAtSelection() throws Exception {
            if (!ownerAtSelection.await(15, TimeUnit.SECONDS)) {
                throw new TimeoutException("Owner did not reach locked cart selection");
            }
        }

        void awaitCartLockAttempt() throws Exception {
            if (!cartLockAttempted.await(15, TimeUnit.SECONDS)) {
                throw new TimeoutException("Order owner did not reach cart lock");
            }
        }

        void releaseCartLock() {
            if (cartLockReleased.getCount() > 0) {
                cartLockReleased.countDown();
            }
        }

        void awaitAdminSpuLock() throws Exception {
            if (!adminSpuLocked.await(15, TimeUnit.SECONDS)) {
                throw new TimeoutException("Admin update did not acquire the SPU lock");
            }
        }

        void awaitCheckoutProductLock() throws Exception {
            if (!checkoutProductLockReached.await(15, TimeUnit.SECONDS)) {
                throw new TimeoutException("Checkout did not reach its first product lock");
            }
        }

        void releaseAdminSpuLock() {
            if (adminSpuReleased.getCount() > 0) {
                adminSpuReleased.countDown();
            }
        }

        Object execute(PreparedStatement statement, String sql, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            boolean enabledAppUserLock = isEnabledAppUserLock(normalized);
            if (mode == Mode.USER_GATE_BARRIER && enabledAppUserLock) {
                userGateBarrier.await(15, TimeUnit.SECONDS);
            }
            if (mode == Mode.ROLLBACK_TAKEOVER
                    && Thread.currentThread().getName().equals("rollback-waiter")
                    && enabledAppUserLock) {
                waiterUserGateAttempted.countDown();
            }
            if (mode == Mode.ROLLBACK_TAKEOVER
                    && Thread.currentThread().getName().equals("rollback-owner")
                    && normalized.contains("select id as cart_item_id")
                    && normalized.contains("from cart_item")
                    && ownerFailureInjected.compareAndSet(false, true)) {
                ownerAtSelection.countDown();
                if (!waiterUserGateAttempted.await(15, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Waiter did not attempt the app-user gate");
                }
                throw new SQLException("Injected selection failure after ownership insert");
            }
            if (mode == Mode.CART_QUANTITY_REFRESH
                    && Thread.currentThread().getName().equals("cart-quantity-owner")
                    && normalized.contains("select id as cart_item_id")
                    && normalized.contains("from cart_item")
                    && normalized.contains("for update")) {
                cartLockAttempted.countDown();
                if (!cartLockReleased.await(15, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Cart quantity refresh did not complete");
                }
            }

            boolean checkoutNewParentLock = mode == Mode.PRODUCT_LOCK_ORDER_RACE
                    && Thread.currentThread().getName().equals("product-checkout")
                    && normalized.contains("from product_spu")
                    && normalized.contains("for update");
            if (checkoutNewParentLock) {
                checkoutProductLockReached.countDown();
            }

            Object result;
            try {
                result = method.invoke(statement, args);
            } catch (InvocationTargetException exception) {
                throw exception.getTargetException();
            }

            boolean checkoutLegacySkuLock = mode == Mode.PRODUCT_LOCK_ORDER_RACE
                    && Thread.currentThread().getName().equals("product-checkout")
                    && normalized.startsWith("select id")
                    && normalized.contains("from product_sku")
                    && normalized.contains("for update");
            if (checkoutLegacySkuLock) {
                checkoutProductLockReached.countDown();
            }
            if (mode == Mode.PRODUCT_LOCK_ORDER_RACE
                    && Thread.currentThread().getName().equals("product-admin")
                    && normalized.startsWith("update product_spu")) {
                adminSpuLocked.countDown();
                if (!adminSpuReleased.await(15, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Checkout did not attempt its product lock");
                }
            }

            if (mode == Mode.INITIAL_READ_BARRIER
                    && normalized.contains("select id as order_id")
                    && normalized.contains("from shop_order")
                    && normalized.contains("idempotency_key")
                    && !normalized.contains("for update")) {
                initialReadBarrier.await(15, TimeUnit.SECONDS);
            }

            return result;
        }

        private boolean isEnabledAppUserLock(String sql) {
            return sql.contains("from app_user")
                    && sql.contains("where id = ? and status = ?")
                    && sql.contains("for update");
        }
    }

    static final class ProbeDataSource extends AbstractDataSource {

        private final DataSource delegate;
        private final OrderRaceProbe probe;

        ProbeDataSource(DataSource delegate, OrderRaceProbe probe) {
            this.delegate = delegate;
            this.probe = probe;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            InvocationHandler handler = (proxy, method, args) -> {
                try {
                    if ("prepareStatement".equals(method.getName())
                            && args != null && args.length > 0 && args[0] instanceof String sql) {
                        PreparedStatement statement = (PreparedStatement) method.invoke(connection, args);
                        return wrap(statement, sql);
                    }
                    return method.invoke(connection, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getTargetException();
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement wrap(PreparedStatement statement, String sql) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().startsWith("execute")) {
                    return probe.execute(statement, sql, method, args);
                }
                try {
                    return method.invoke(statement, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getTargetException();
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, handler);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Attempt(OrderSubmitResponse response, Throwable error) {
    }

    private record StockLogRow(int quantityBefore, int quantityDelta, int quantityAfter) {
    }

    private record Fixture(long userId, long addressId, long skuId, long cartItemId, Long userCouponId) {
    }
}
