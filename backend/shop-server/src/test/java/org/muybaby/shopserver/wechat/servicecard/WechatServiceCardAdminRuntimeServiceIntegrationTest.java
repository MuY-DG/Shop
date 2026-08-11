package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardRuntimeUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class WechatServiceCardAdminRuntimeServiceIntegrationTest {

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WechatServiceCardAdminRuntimeService adminRuntimeService;

    @MockitoSpyBean
    WechatServiceCardAdminReadService readService;

    @BeforeEach
    void resetRuntimeState() {
        reset(readService);
        jdbcClient.sql("delete from wechat_service_card_runtime_audit").update();
        jdbcClient.sql("delete from wechat_service_card_runtime_setting").update();
    }

    @Test
    void statusFailureRollsBackSettingAndAuditInsteadOfReturningAFalseFailure() {
        doThrow(new IllegalStateException("forced status failure"))
                .when(readService).status();

        assertThatThrownBy(() -> adminRuntimeService.update(
                new AdminWechatServiceCardRuntimeUpdateRequest(
                        false, false, 0L, "verified emergency shutdown"
                ),
                1L
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("forced status failure");

        assertThat(jdbcClient.sql(
                        "select count(*) from wechat_service_card_runtime_setting")
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbcClient.sql(
                        "select count(*) from wechat_service_card_runtime_audit")
                .query(Long.class)
                .single()).isZero();
    }
}
