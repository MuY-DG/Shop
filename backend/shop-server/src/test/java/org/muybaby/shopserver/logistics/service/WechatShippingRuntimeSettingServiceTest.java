package org.muybaby.shopserver.logistics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.dto.AdminWechatShippingRuntimeUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WechatShippingRuntimeSettingServiceTest {

    @Autowired
    private WechatShippingRuntimeSettingService service;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clean() {
        jdbcClient.sql("delete from wechat_shipping_runtime_audit").update();
        jdbcClient.sql("delete from wechat_shipping_runtime_setting").update();
    }

    @Test
    void firstUpdatePersistsVersionedFlagsAndAudit() {
        WechatShippingRuntimeSettingService.RuntimeSetting defaults = service.current();
        assertThat(defaults.persisted()).isFalse();
        assertThat(defaults.version()).isZero();
        assertThat(defaults.uploadEnabled()).isFalse();

        WechatShippingRuntimeSettingService.RuntimeSetting saved = service.update(
                request(true, true, true, 0),
                1L
        );

        assertThat(saved.persisted()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.uploadEnabled()).isTrue();
        assertThat(saved.deliveryEnabled()).isTrue();
        assertThat(saved.receiptReconciliationEnabled()).isTrue();
        assertThat(saved.reason()).isEqualTo("管理员调整微信订单同步设置");
        assertThat(saved.updatedBy()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from wechat_shipping_runtime_audit")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void databaseSettingOverridesSafeDefault() {
        service.update(request(false, false, false, 0), 1L);

        assertThat(service.uploadEnabledFailClosed()).isFalse();
        assertThat(service.deliveryEnabledFailClosed()).isFalse();
        assertThat(service.receiptReconciliationEnabledFailClosed()).isFalse();
    }

    @Test
    void rejectsDependentWorkersWithoutUploadAndStaleVersion() {
        assertThatThrownBy(() -> service.update(
                request(false, true, false, 0), 1L
        )).isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        service.update(request(true, false, false, 0), 1L);

        assertThatThrownBy(() -> service.update(
                request(false, false, false, 0), 1L
        )).isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.errorCode())
                        .isEqualTo(ErrorCode.WECHAT_SHIPPING_RUNTIME_CONFLICT));
    }

    private AdminWechatShippingRuntimeUpdateRequest request(
            boolean uploadEnabled,
            boolean deliveryEnabled,
            boolean receiptEnabled,
            long version
    ) {
        return new AdminWechatShippingRuntimeUpdateRequest(
                uploadEnabled, deliveryEnabled, receiptEnabled, version
        );
    }
}
