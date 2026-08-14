package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentialResolver;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentials;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardRuntimeUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WechatServiceCardRuntimeSettingServiceIntegrationTest {

    @Autowired
    JdbcClient jdbcClient;

    private WechatServiceCardRuntimeSettingService readyService;

    @BeforeEach
    void resetRuntimeOverride() {
        jdbcClient.sql("delete from wechat_service_card_runtime_audit").update();
        jdbcClient.sql("delete from wechat_service_card_runtime_setting").update();
        readyService = service(readyProperties(), readyMiniProgram());
    }

    @Test
    void stagedEnableUsesCasAndAppendsBeforeAfterAudit() {
        WechatServiceCardRuntimeSettingService.RuntimeSetting environment = readyService.current();
        assertThat(environment.persisted()).isFalse();
        assertThat(environment.version()).isZero();
        assertThat(environment.captureEnabled()).isFalse();
        assertThat(environment.workerEnabled()).isFalse();

        assertValidation(() -> readyService.update(
                request(true, true, 0, "enable everything"), 1L
        ));
        assertThat(auditCount()).isZero();

        WechatServiceCardRuntimeSettingService.RuntimeSetting capture = readyService.update(
                request(true, false, 0, "enable capture first"), 1L
        );
        assertThat(capture.persisted()).isTrue();
        assertThat(capture.version()).isOne();
        assertThat(capture.captureEnabled()).isTrue();
        assertThat(capture.workerEnabled()).isFalse();

        WechatServiceCardRuntimeSettingService.RuntimeSetting worker = readyService.update(
                request(true, true, 1, "enable worker after review"), 1L
        );
        assertThat(worker.version()).isEqualTo(2);
        assertThat(worker.workerEnabled()).isTrue();

        assertThat(jdbcClient.sql("""
                        select revision, capture_enabled_before, worker_enabled_before,
                               capture_enabled_after, worker_enabled_after,
                               change_reason, operator_id
                        from wechat_service_card_runtime_audit
                        order by revision
                        """)
                .query((rs, rowNum) -> new AuditRow(
                        rs.getLong("revision"),
                        rs.getBoolean("capture_enabled_before"),
                        rs.getBoolean("worker_enabled_before"),
                        rs.getBoolean("capture_enabled_after"),
                        rs.getBoolean("worker_enabled_after"),
                        rs.getString("change_reason"),
                        rs.getLong("operator_id")
                ))
                .list()).containsExactly(
                new AuditRow(
                        1L, false, false, true, false, "enable capture first", 1L
                ),
                new AuditRow(
                        2L, true, false, true, true, "enable worker after review", 1L
                )
        );

        assertThatThrownBy(() -> readyService.update(
                request(false, false, 1, "stale emergency close"), 1L
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.WECHAT_SERVICE_CARD_RUNTIME_CONFLICT));
        assertThat(auditCount()).isEqualTo(2);
    }

    @Test
    void brokenReadinessNeverPreventsEmergencyShutdown() {
        readyService.update(request(true, false, 0, "enable capture first"), 1L);
        readyService.update(request(true, true, 1, "enable worker after review"), 1L);

        WechatServiceCardRuntimeSettingService brokenService = service(
                disabledProperties(), () -> {
                    throw new IllegalStateException("missing credentials");
                }
        );
        WechatServiceCardRuntimeSettingService.RuntimeSetting disabled = brokenService.update(
                request(false, false, 2, "emergency provider shutdown"), 1L
        );

        assertThat(disabled.captureEnabled()).isFalse();
        assertThat(disabled.workerEnabled()).isFalse();
        assertThat(disabled.version()).isEqualTo(3);
        assertThat(auditCount()).isEqualTo(3);
    }

    @Test
    void rejectsUnsafeReasonAndMissingOperatorWithoutWritingAudit() {
        assertValidation(() -> readyService.update(
                request(false, false, 0, "safe\u202Eunsafe"), 1L
        ));
        assertValidation(() -> readyService.update(
                request(false, false, 0, "missing operator"), null
        ));
        assertThat(auditCount()).isZero();
    }

    private WechatServiceCardRuntimeSettingService service(
            WechatServiceCardProperties properties,
            WechatPlatformCredentialResolver credentialResolver
    ) {
        return new WechatServiceCardRuntimeSettingService(
                jdbcClient, properties, credentialResolver,
                () -> WechatServiceCardTestConfigs.fromProperties(properties)
        );
    }

    private AdminWechatServiceCardRuntimeUpdateRequest request(
            boolean captureEnabled,
            boolean workerEnabled,
            long version,
            String reason
    ) {
        return new AdminWechatServiceCardRuntimeUpdateRequest(
                captureEnabled, workerEnabled, version, reason
        );
    }

    private long auditCount() {
        return jdbcClient.sql("select count(*) from wechat_service_card_runtime_audit")
                .query(Long.class)
                .single();
    }

    private void assertValidation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private WechatServiceCardProperties readyProperties() {
        return properties(
                "template-record",
                "https://admin.muybaby6.icu/wechat/service-card-placeholder.png",
                List.of("admin.muybaby6.icu"),
                new WechatServiceCardProperties.Callback(
                        true,
                        "callbackToken123",
                        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
                        Duration.ofMinutes(5)
                )
        );
    }

    private WechatServiceCardProperties disabledProperties() {
        return properties(
                "", "", List.of(),
                new WechatServiceCardProperties.Callback(
                        false, "", "", Duration.ofMinutes(5)
                )
        );
    }

    private WechatServiceCardProperties properties(
            String template,
            String image,
            List<String> imageHosts,
            WechatServiceCardProperties.Callback callback
    ) {
        return new WechatServiceCardProperties(
                false, false, template, Duration.ofSeconds(15), 50,
                Duration.ofMinutes(2), 8, Duration.ofMinutes(1), Duration.ofMinutes(30),
                Duration.ofMinutes(1), Duration.ofHours(6), 2,
                Duration.ofSeconds(3), Duration.ofSeconds(15),
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(64),
                image, false, imageHosts, callback
        );
    }

    private WechatPlatformCredentialResolver readyMiniProgram() {
        return () -> new WechatPlatformCredentials(
                "wx-service-card-test",
                "mini-program-secret",
                WechatPlatformCredentials.Source.DATABASE
        );
    }

    private record AuditRow(
            long revision,
            boolean captureBefore,
            boolean workerBefore,
            boolean captureAfter,
            boolean workerAfter,
            String reason,
            long operatorId
    ) {
    }
}
