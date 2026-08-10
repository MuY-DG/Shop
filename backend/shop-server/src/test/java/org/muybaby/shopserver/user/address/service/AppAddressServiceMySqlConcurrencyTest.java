package org.muybaby.shopserver.user.address.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.user.address.dto.AddressResponse;
import org.muybaby.shopserver.user.address.dto.AddressUpsertRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppAddressServiceMySqlConcurrencyTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("address_concurrency")
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
    private AppAddressService appAddressService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearAddresses() {
        jdbcClient.sql("delete from user_address").update();
    }

    @Test
    void simultaneousFirstCreatesSerializeThroughTheAppUserLock() throws Exception {
        long userId = insertAppUser(92001L, "mysql-address-first-race");

        runTogether(
                () -> appAddressService.create(userId, request("张三", false)),
                () -> appAddressService.create(userId, request("李四", false))
        );

        assertExactlyOneDefault(userId, 2);
    }

    @Test
    void simultaneousDefaultSwitchesKeepOneDefaultWithoutDeadlock() throws Exception {
        long userId = insertAppUser(92002L, "mysql-address-default-race");
        appAddressService.create(userId, request("张三", false));
        AddressResponse second = appAddressService.create(userId, request("李四", false));
        AddressResponse third = appAddressService.create(userId, request("王五", false));

        runTogether(
                () -> appAddressService.setDefault(userId, second.id()),
                () -> appAddressService.setDefault(userId, third.id())
        );

        assertExactlyOneDefault(userId, 3);
        assertThat(defaultAddressId(userId)).isIn(second.id(), third.id());
    }

    @Test
    void deleteDefaultRacingWithCreatePromotesExactlyOneDefault() throws Exception {
        long userId = insertAppUser(92003L, "mysql-address-delete-create-race");
        AddressResponse original = appAddressService.create(userId, request("张三", false));

        runTogether(
                () -> appAddressService.delete(userId, original.id()),
                () -> appAddressService.create(userId, request("李四", false))
        );

        assertExactlyOneDefault(userId, 1);
        assertThat(appAddressService.list(userId).getFirst().receiverName()).isEqualTo("李四");
    }

    private void assertExactlyOneDefault(long userId, int expectedCount) {
        List<AddressResponse> addresses = appAddressService.list(userId);
        assertThat(addresses).hasSize(expectedCount);
        assertThat(addresses.stream().filter(AddressResponse::isDefault)).hasSize(1);
        Long persistedDefaults = jdbcClient.sql("""
                        select count(*)
                        from user_address
                        where user_id = :userId and is_default = true
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
        assertThat(persistedDefaults).isEqualTo(1L);
    }

    private long defaultAddressId(long userId) {
        return appAddressService.list(userId).stream()
                .filter(AddressResponse::isDefault)
                .findFirst()
                .orElseThrow()
                .id();
    }

    private void runTogether(ThrowingAction first, ThrowingAction second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> runAfterBarrier(barrier, first));
            Future<?> secondFuture = executor.submit(() -> runAfterBarrier(barrier, second));
            firstFuture.get(20, TimeUnit.SECONDS);
            secondFuture.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void runAfterBarrier(CyclicBarrier barrier, ThrowingAction action) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            action.run();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AddressUpsertRequest request(String receiverName, boolean isDefault) {
        return new AddressUpsertRequest(
                receiverName,
                "13800138000",
                "北京市",
                "北京市",
                "朝阳区",
                "火锅路" + receiverName + "号",
                "",
                "",
                isDefault
        );
    }

    private long insertAppUser(long userId, String openid) {
        jdbcClient.sql("""
                        insert into app_user (id, openid, unionid, status, last_login_at, created_at, updated_at)
                        values (:id, :openid, :unionid, 'ENABLED', :now, :now, :now)
                        """)
                .param("id", userId)
                .param("openid", openid)
                .param("unionid", openid + "-unionid")
                .param("now", LocalDateTime.now())
                .update();
        return userId;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
