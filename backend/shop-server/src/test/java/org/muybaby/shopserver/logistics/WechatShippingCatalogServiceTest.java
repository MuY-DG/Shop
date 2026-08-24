package org.muybaby.shopserver.logistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.logistics.provider.WechatDeliveryCompanyResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingCapabilityResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult;
import org.muybaby.shopserver.logistics.service.WechatShippingCatalogService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WechatShippingCatalogServiceTest {

    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            TokenKind.ADMIN, 1L, "shipping-admin", List.of("R_SUPER"), List.of("order:ship")
    );
    private static final AuthenticatedPrincipal APP = new AuthenticatedPrincipal(
            TokenKind.APP, 2L, "app-user", List.of(), List.of()
    );

    @Autowired
    private WechatShippingCatalogService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ControllableCatalogProvider provider;

    @BeforeEach
    void resetState() {
        jdbcClient.sql("delete from wechat_delivery_company").update();
        setUploadEnabled(false);
        provider.reset();
    }

    @Test
    void catalogOperationsRequireAdminPrincipal() {
        assertThatThrownBy(() -> service.capability(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.capability(APP)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.list(APP)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.sync(APP)).isInstanceOf(RuntimeException.class);
        assertThat(provider.calls()).isZero();
    }

    @Test
    void disabledConfigurationReportsDisabledWithoutQueryingProvider() {
        provider.capability = WechatShippingCapabilityResult.available();

        var response = service.capability(ADMIN);

        assertThat(response.uploadEnabled()).isFalse();
        assertThat(response.providerMode()).isEqualTo(WechatProviderMode.DISABLED);
        assertThat(response.state()).isEqualTo(WechatShippingCapabilityState.UNAVAILABLE);
        assertThat(response.tradeManaged()).isNull();
        assertThat(response.errorCode()).isEqualTo("UPLOAD_DISABLED");
        assertThat(response.checkedAt()).isNotNull();
        assertThat(provider.capabilityCalls).isZero();
    }

    @Test
    void enabledRealConfigurationExposesProviderCapability() {
        setUploadEnabled(true);
        provider.mode = WechatProviderMode.REAL;
        provider.capability = WechatShippingCapabilityResult.available();

        var response = service.capability(ADMIN);

        assertThat(response.uploadEnabled()).isTrue();
        assertThat(response.providerMode()).isEqualTo(WechatProviderMode.REAL);
        assertThat(response.state()).isEqualTo(WechatShippingCapabilityState.AVAILABLE);
        assertThat(response.tradeManaged()).isTrue();
        assertThat(response.errorCode()).isNull();
        assertThat(response.errorMessage()).isNull();
        assertThat(provider.capabilityCalls).isEqualTo(1);
    }

    @Test
    void mockModeCannotBePresentedAsAvailableEvenIfProviderMisbehaves() {
        setUploadEnabled(true);
        provider.mode = WechatProviderMode.MOCK;
        provider.capability = WechatShippingCapabilityResult.available();

        var response = service.capability(ADMIN);

        assertThat(response.providerMode()).isEqualTo(WechatProviderMode.MOCK);
        assertThat(response.state()).isEqualTo(WechatShippingCapabilityState.UNAVAILABLE);
        assertThat(response.tradeManaged()).isNull();
        assertThat(response.errorCode()).isEqualTo("MOCK_PROVIDER");
    }

    @Test
    void syncFetchesBeforeTransactionThenUpsertsAndDisablesMissingRows() {
        insertCarrier("OLD", "旧物流", true, LocalDateTime.of(2026, 7, 1, 10, 0));
        insertCarrier("SF", "顺丰旧名称", true, LocalDateTime.of(2026, 7, 1, 10, 0));
        provider.companies = List.of(
                new WechatDeliveryCompanyResult("SF", "顺丰速运"),
                new WechatDeliveryCompanyResult("JD", "京东物流")
        );

        var result = service.sync(ADMIN);

        assertThat(provider.transactionActiveDuringFetch).isFalse();
        assertThat(result)
                .extracting(item -> item.deliveryId() + ":" + item.deliveryName())
                .containsExactly("JD:京东物流", "SF:顺丰速运");
        assertThat(enabled("OLD")).isFalse();
        assertThat(enabled("SF")).isTrue();
        assertThat(enabled("JD")).isTrue();
        assertThat(name("SF")).isEqualTo("顺丰速运");
        assertThat(syncedAt("SF")).isEqualTo(syncedAt("JD"));
    }

    @Test
    void successfulEmptyOfficialListDisablesAllCachedRows() {
        insertCarrier("SF", "顺丰速运", true, LocalDateTime.of(2026, 7, 1, 10, 0));
        insertCarrier("JD", "京东物流", true, LocalDateTime.of(2026, 7, 1, 10, 0));
        provider.companies = List.of();

        var result = service.sync(ADMIN);

        assertThat(result).isEmpty();
        assertThat(enabled("SF")).isFalse();
        assertThat(enabled("JD")).isFalse();
    }

    @Test
    void providerFailureLeavesCarrierCacheUnchanged() {
        LocalDateTime originalSync = LocalDateTime.of(2026, 7, 1, 10, 0);
        insertCarrier("SF", "顺丰速运", true, originalSync);
        insertCarrier("OLD", "旧物流", false, originalSync);
        provider.failure = new IllegalStateException("provider failed with synthetic-access-token-never-persist");

        assertThatThrownBy(() -> service.sync(ADMIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WeChat delivery company lookup failed")
                .hasMessageNotContaining("synthetic-access-token-never-persist");

        assertThat(name("SF")).isEqualTo("顺丰速运");
        assertThat(enabled("SF")).isTrue();
        assertThat(syncedAt("SF")).isEqualTo(originalSync);
        assertThat(enabled("OLD")).isFalse();
        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    void nonemptyAllInvalidProviderRowsCannotDisableCachedCarriers() {
        LocalDateTime originalSync = LocalDateTime.of(2026, 7, 1, 10, 0);
        insertCarrier("SF", "顺丰速运", true, originalSync);
        provider.companies = List.of(
                new WechatDeliveryCompanyResult("", "blank id"),
                new WechatDeliveryCompanyResult("BLANK_NAME", "  ")
        );

        assertThatThrownBy(() -> service.sync(ADMIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WeChat delivery company lookup failed");

        assertThat(enabled("SF")).isTrue();
        assertThat(name("SF")).isEqualTo("顺丰速运");
        assertThat(syncedAt("SF")).isEqualTo(originalSync);
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void listReturnsOnlyEnabledRowsInStableNameAndIdOrder() {
        LocalDateTime syncedAt = LocalDateTime.of(2026, 7, 1, 10, 0);
        insertCarrier("Z2", "Beta Logistics", true, syncedAt);
        insertCarrier("Z1", "Beta Logistics", true, syncedAt);
        insertCarrier("A", "Alpha Logistics", true, syncedAt);
        insertCarrier("OFF", "Disabled Logistics", false, syncedAt);

        var result = service.list(ADMIN);

        assertThat(result).extracting(item -> item.deliveryId())
                .containsExactly("A", "Z1", "Z2");
    }

    private void insertCarrier(String id, String name, boolean enabled, LocalDateTime syncedAt) {
        jdbcClient.sql("""
                        insert into wechat_delivery_company(delivery_id, delivery_name, enabled, synced_at)
                        values (:id, :name, :enabled, :syncedAt)
                        """)
                .param("id", id)
                .param("name", name)
                .param("enabled", enabled)
                .param("syncedAt", syncedAt)
                .update();
    }

    private boolean enabled(String id) {
        return jdbcClient.sql("select enabled from wechat_delivery_company where delivery_id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private String name(String id) {
        return jdbcClient.sql("select delivery_name from wechat_delivery_company where delivery_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private LocalDateTime syncedAt(String id) {
        return jdbcClient.sql("select synced_at from wechat_delivery_company where delivery_id = :id")
                .param("id", id)
                .query(LocalDateTime.class)
                .single();
    }

    private int countRows() {
        return jdbcClient.sql("select count(*) from wechat_delivery_company")
                .query(Integer.class)
                .single();
    }

    private void setUploadEnabled(boolean enabled) {
        jdbcClient.sql("""
                        update wechat_shipping_runtime_setting
                        set upload_enabled = :enabled,
                            delivery_enabled = false,
                            receipt_reconciliation_enabled = false
                        where id = 1
                        """)
                .param("enabled", enabled)
                .update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CatalogProviderTestConfiguration {

        @Bean
        @Primary
        ControllableCatalogProvider controllableCatalogProvider() {
            return new ControllableCatalogProvider();
        }
    }

    static class ControllableCatalogProvider implements WechatShippingProvider {

        private WechatProviderMode mode;
        private WechatShippingCapabilityResult capability;
        private List<WechatDeliveryCompanyResult> companies;
        private RuntimeException failure;
        private boolean transactionActiveDuringFetch;
        private int capabilityCalls;
        private int deliveryCalls;

        void reset() {
            mode = WechatProviderMode.REAL;
            capability = WechatShippingCapabilityResult.available();
            companies = List.of();
            failure = null;
            transactionActiveDuringFetch = false;
            capabilityCalls = 0;
            deliveryCalls = 0;
        }

        int calls() {
            return capabilityCalls + deliveryCalls;
        }

        @Override
        public WechatProviderMode mode() {
            return mode;
        }

        @Override
        public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
            return WechatShippingUploadResult.unavailable("TEST_ONLY", "Test provider does not upload");
        }

        @Override
        public WechatShippingCapabilityResult queryCapability() {
            capabilityCalls++;
            return capability;
        }

        @Override
        public List<WechatDeliveryCompanyResult> getDeliveryCompanies() {
            deliveryCalls++;
            transactionActiveDuringFetch = TransactionSynchronizationManager.isActualTransactionActive();
            if (failure != null) {
                throw failure;
            }
            return companies;
        }
    }
}
