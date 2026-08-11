package org.muybaby.shopserver.wechat.servicecard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.api.JsonStringId;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.wechat.WechatMiniProgramProperties;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryQuery;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryResponse;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardRuntimeUpdateRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AdminWechatServiceCardContractTest {

    private static final long LARGE_ID = 9_007_199_254_740_993L;

    @Test
    void allExposedDatabaseIdsSerializeAsStrings() throws Exception {
        AdminWechatServiceCardDeliveryResponse response =
                new AdminWechatServiceCardDeliveryResponse(
                        LARGE_ID, LARGE_ID - 1, LARGE_ID - 2,
                        "202608100001",
                        1, 2, "UNKNOWN", true, "USER_REFUSED",
                        LocalDateTime.of(2026, 8, 10, 11, 30),
                        3, 4, 1,
                        "QUERY_UNAVAILABLE", "Provider reconciliation is unavailable",
                        LocalDateTime.of(2026, 8, 10, 12, 0), null,
                        "UNKNOWN", null, "", null,
                        LocalDateTime.of(2026, 8, 10, 11, 0),
                        LocalDateTime.of(2026, 8, 10, 12, 0)
                );

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(response);

        assertThat(json.path("id").isTextual()).isTrue();
        assertThat(json.path("cardId").isTextual()).isTrue();
        assertThat(json.path("orderId").isTextual()).isTrue();
        assertThat(json.path("sequenceNo").isNumber()).isTrue();
        assertThat(json.path("setAttempts").isNumber()).isTrue();
        assertThat(json.path("cardSendBlocked").asBoolean()).isTrue();
        assertThat(json.path("cardSendBlockReason").asText()).isEqualTo("USER_REFUSED");

        Map<String, RecordComponent> components = Arrays.stream(
                        AdminWechatServiceCardDeliveryResponse.class.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, value -> value));
        for (String name : List.of("id", "cardId", "orderId")) {
            assertThat(components.get(name).isAnnotationPresent(JsonStringId.class))
                    .as(name)
                    .isTrue();
        }
    }

    @Test
    void endpointsUseDedicatedReadAndRuntimeWriteAuthorities() throws Exception {
        Method status = AdminWechatServiceCardController.class.getMethod("status");
        Method deliveries = AdminWechatServiceCardController.class.getMethod(
                "deliveries", AdminWechatServiceCardDeliveryQuery.class
        );
        Method updateRuntime = AdminWechatServiceCardController.class.getMethod(
                "updateRuntime",
                org.muybaby.shopserver.security.AuthenticatedPrincipal.class,
                AdminWechatServiceCardRuntimeUpdateRequest.class
        );

        assertThat(status.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('wechat-service-card:read')");
        assertThat(deliveries.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('wechat-service-card:read')");
        assertThat(updateRuntime.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('wechat-service-card:runtime:write')");
    }

    @Test
    void invalidPaginationOrderAndStateFailAsBusinessValidationBeforeSql() {
        WechatServiceCardAdminReadService service = new WechatServiceCardAdminReadService(
                mock(JdbcClient.class), disabledProperties(),
                new WechatMiniProgramProperties("app-id", "secret", false),
                mock(WechatServiceCardRuntimeSettingService.class),
                Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC)
        );

        assertValidation(() -> service.deliveries(
                new AdminWechatServiceCardDeliveryQuery(0L, 20L, null, null)
        ));
        assertValidation(() -> service.deliveries(
                new AdminWechatServiceCardDeliveryQuery(1L, 201L, null, null)
        ));
        assertValidation(() -> service.deliveries(
                new AdminWechatServiceCardDeliveryQuery(1L, 20L, 0L, null)
        ));
        assertValidation(() -> service.deliveries(
                new AdminWechatServiceCardDeliveryQuery(1L, 20L, null, "SENT")
        ));
    }

    @Test
    void runtimeUpdateRequestRejectsUnknownSecretFields() {
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> mapper.readValue("""
                        {
                          "captureEnabled": false,
                          "workerEnabled": false,
                          "version": 0,
                          "reason": "emergency shutdown",
                          "callbackToken": "must-not-be-accepted"
                        }
                        """, AdminWechatServiceCardRuntimeUpdateRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    private void assertValidation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private WechatServiceCardProperties disabledProperties() {
        return new WechatServiceCardProperties(
                false, false, "", Duration.ofSeconds(15), 50,
                Duration.ofMinutes(2), 8, Duration.ofMinutes(1), Duration.ofMinutes(30),
                Duration.ofMinutes(1), Duration.ofHours(6), 2,
                Duration.ofSeconds(3), Duration.ofSeconds(15),
                org.springframework.util.unit.DataSize.ofMegabytes(1),
                org.springframework.util.unit.DataSize.ofKilobytes(64),
                "", false, List.of(),
                new WechatServiceCardProperties.Callback(
                        false, "", "", Duration.ofMinutes(5)
                )
        );
    }
}
