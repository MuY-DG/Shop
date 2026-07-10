package org.muybaby.shopserver.user.address.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.user.address.dto.AddressResponse;
import org.muybaby.shopserver.user.address.dto.AddressUpsertRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppAddressServiceTest {

    @Autowired
    private AppAddressService appAddressService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearAddresses() {
        jdbcClient.sql("delete from user_address").update();
    }

    @Test
    void simultaneousFirstAddressCreatesLeaveExactlyOneDefault() throws Exception {
        long userId = insertAppUser(91001L, "address-first-race");

        runTogether(
                () -> appAddressService.create(userId, request("张三", false)),
                () -> appAddressService.create(userId, request("李四", false))
        );

        assertExactlyOneDefault(userId, 2);
    }

    @Test
    void simultaneousDefaultSwitchesLeaveExactlyOneDefault() throws Exception {
        long userId = insertAppUser(91002L, "address-default-race");
        appAddressService.create(userId, request("张三", false));
        AddressResponse second = appAddressService.create(userId, request("李四", false));
        AddressResponse third = appAddressService.create(userId, request("王五", false));

        runTogether(
                () -> appAddressService.setDefault(userId, second.id()),
                () -> appAddressService.setDefault(userId, third.id())
        );

        assertExactlyOneDefault(userId, 3);
        long defaultId = appAddressService.list(userId).stream()
                .filter(AddressResponse::isDefault)
                .findFirst()
                .orElseThrow()
                .id();
        assertThat(defaultId).isIn(second.id(), third.id());
    }

    @Test
    void deletingDefaultRacingWithCreateLeavesExactlyOneDefault() throws Exception {
        long userId = insertAppUser(91003L, "address-delete-create-race");
        AddressResponse original = appAddressService.create(userId, request("张三", false));

        runTogether(
                () -> appAddressService.delete(userId, original.id()),
                () -> appAddressService.create(userId, request("李四", false))
        );

        assertExactlyOneDefault(userId, 1);
        assertThat(appAddressService.list(userId).getFirst().receiverName()).isEqualTo("李四");
    }

    @Test
    void updateCannotRemoveTheOnlyDefaultAndOwnedLookupDoesNotEnumerate() {
        long userA = insertAppUser(91004L, "address-owner-a");
        long userB = insertAppUser(91005L, "address-owner-b");
        AddressResponse first = appAddressService.create(userA, request("张三", false));
        appAddressService.create(userA, request("李四", false));

        AddressResponse updated = appAddressService.update(userA, first.id(), request("张三更新", false));

        assertThat(updated.isDefault()).isTrue();
        assertExactlyOneDefault(userA, 2);
        OwnedAddress owned = appAddressService.requireOwnedForUpdate(userA, first.id());
        assertThat(owned.receiverName()).isEqualTo("张三更新");
        assertThat(owned.formattedAddress()).isEqualTo("北京市北京市朝阳区火锅路张三更新号");
        assertThatThrownBy(() -> appAddressService.requireOwnedForUpdate(userB, first.id()))
                .isInstanceOf(BusinessException.class);
    }

    private void assertExactlyOneDefault(long userId, int expectedCount) {
        List<AddressResponse> addresses = appAddressService.list(userId);
        assertThat(addresses).hasSize(expectedCount);
        assertThat(addresses.stream().filter(AddressResponse::isDefault).count()).isEqualTo(1);
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

    private void runTogether(ThrowingAction first, ThrowingAction second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> runAfterBarrier(barrier, first));
            Future<?> secondFuture = executor.submit(() -> runAfterBarrier(barrier, second));
            firstFuture.get(10, TimeUnit.SECONDS);
            secondFuture.get(10, TimeUnit.SECONDS);
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
