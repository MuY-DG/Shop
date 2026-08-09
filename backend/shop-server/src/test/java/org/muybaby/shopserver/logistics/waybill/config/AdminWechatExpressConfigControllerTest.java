package org.muybaby.shopserver.logistics.waybill.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminWechatExpressConfigControllerTest {

    private static final String ENDPOINT = "/admin/logistics/wechat-express/config";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void endpointsEnforceExactPermissionsAndReturnSafeExactContract() throws Exception {
        String readToken = token(List.of("logistics:express:config:read"));
        String writeToken = token(List.of("logistics:express:config:write"));

        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isForbidden());

        JsonNode seeded = responseData(mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertExactConfigContract(seeded);
        assertThat(seeded.path("mode").asText()).isEqualTo("DISABLED");
        assertThat(seeded.path("production").has("serviceType")).isTrue();
        assertThat(seeded.path("production").path("serviceType").isNull()).isTrue();
        assertThat(seeded.path("effective").path("deliveryId").asText()).isEmpty();
        assertThat(seeded.path("defaultParcel").path("count").asInt()).isOne();

        ObjectNode request = request(0, "DISABLED", true);
        request.withObject("production").put("bizId", "biz-secret-01");
        request.withObject("production").put("serviceType", 0);

        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isForbidden());

        JsonNode updated = responseData(mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertExactConfigContract(updated);
        assertThat(updated.path("revision").asLong()).isEqualTo(1L);
        assertThat(updated.path("production").path("bizIdMasked").asText())
                .isEqualTo("bi******01");
        assertThat(updated.path("production").path("serviceType").asInt()).isZero();
        assertThat(updated.toString())
                .doesNotContain("biz-secret-01", "password", "customerPassword");
        assertThat(jdbcClient.sql("select biz_id from wechat_express_setting where id = 1")
                .query(String.class).single()).isEqualTo("biz-secret-01");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = 'wechat_express_setting'
                          and lower(column_name) like '%password%'
                        """).query(Integer.class).single()).isZero();
    }

    @Test
    void sandboxForcesOfficialEffectiveValuesAndBlankBizIdPreservesStoredDraft() throws Exception {
        String writeToken = token(List.of("logistics:express:config:write"));
        ObjectNode initial = request(0, "DISABLED", true);
        initial.withObject("production").put("bizId", "merchant-biz-8888");
        initial.withObject("production").put("serviceType", 0);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initial.toString()))
                .andExpect(status().isOk());

        ObjectNode sandbox = request(1, "SANDBOX", true);
        sandbox.withObject("production").remove("bizId");
        sandbox.withObject("production").put("clearBizId", false);
        JsonNode updated = responseData(mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sandbox.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(updated.path("mode").asText()).isEqualTo("SANDBOX");
        assertThat(updated.path("production").path("bizIdMasked").asText())
                .isEqualTo("me******88");
        assertThat(updated.path("effective").path("deliveryId").asText()).isEqualTo("TEST");
        assertThat(updated.path("effective").path("deliveryName").asText())
                .isEqualTo("微信官方测试运力");
        assertThat(updated.path("effective").path("bizIdMasked").asText())
                .isEqualTo("test_biz_id");
        assertThat(updated.path("effective").path("serviceType").asInt()).isOne();
        assertThat(updated.path("effective").path("serviceName").asText())
                .isEqualTo("test_service_name");
        assertThat(jdbcClient.sql("select biz_id from wechat_express_setting where id = 1")
                .query(String.class).single()).isEqualTo("merchant-biz-8888");
    }

    @Test
    void clearBizIdWorksForDraftAndProductionRequiresCompleteValuesButAllowsServiceTypeZero()
            throws Exception {
        String writeToken = token(List.of("logistics:express:config:write"));
        ObjectNode initial = request(0, "DISABLED", true);
        initial.withObject("production").put("bizId", "existing-biz-01");
        initial.withObject("production").put("serviceType", 0);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initial.toString()))
                .andExpect(status().isOk());

        ObjectNode clear = request(1, "DISABLED", true);
        clear.withObject("production").remove("bizId");
        clear.withObject("production").put("clearBizId", true);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clear.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.production.bizIdMasked").value(""));
        assertThat(jdbcClient.sql("select biz_id from wechat_express_setting where id = 1")
                .query(String.class).single()).isEmpty();

        ObjectNode incompleteProduction = request(2, "PRODUCTION", true);
        incompleteProduction.withObject("production").remove("bizId");
        incompleteProduction.withObject("production").put("clearBizId", false);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incompleteProduction.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        ObjectNode completeProduction = request(2, "PRODUCTION", true);
        completeProduction.withObject("production").put("bizId", "new-production-biz");
        completeProduction.withObject("production").put("serviceType", 0);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeProduction.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("PRODUCTION"))
                .andExpect(jsonPath("$.data.production.serviceType").value(0))
                .andExpect(jsonPath("$.data.effective.serviceType").value(0));
    }

    @Test
    void disabledAllowsIncompleteDraftWhileEnabledModesAndParcelRemainValidated() throws Exception {
        String writeToken = token(List.of("logistics:express:config:write"));
        ObjectNode disabledDraft = request(0, "DISABLED", false);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disabledDraft.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));

        ObjectNode missingSandboxSender = request(1, "SANDBOX", false);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingSandboxSender.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        ObjectNode multiplePackages = request(1, "DISABLED", false);
        multiplePackages.withObject("defaultParcel").put("count", 2);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiplePackages.toString()))
                .andExpect(status().isBadRequest());

        ObjectNode negativeService = request(1, "DISABLED", false);
        negativeService.withObject("production").put("serviceType", -1);
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negativeService.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledDraftRoundTripsExplicitServiceTypeZeroWithoutOtherProductionFields()
            throws Exception {
        String token = token(List.of(
                "logistics:express:config:read",
                "logistics:express:config:write"
        ));
        ObjectNode draft = request(0, "DISABLED", false);
        draft.withObject("production").put("serviceType", 0);

        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.production.serviceType").value(0));

        mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.production.serviceType").value(0));
        assertThat(jdbcClient.sql("select service_type from wechat_express_setting where id = 1")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void disabledDraftKeepsMissingServiceTypeNullAndCannotRoundTripItIntoProduction()
            throws Exception {
        String token = token(List.of(
                "logistics:express:config:read",
                "logistics:express:config:write"
        ));
        ObjectNode draft = request(0, "DISABLED", true);
        draft.withObject("production").put("bizId", "draft-biz-id");

        JsonNode saved = responseData(mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(saved.path("production").path("serviceType").isNull()).isTrue();
        assertThat(jdbcClient.sql("select service_type from wechat_express_setting where id = 1")
                .query(Integer.class)
                .optional()).isEmpty();

        ObjectNode production = request(1, "PRODUCTION", true);
        production.withObject("production").remove("bizId");
        production.withObject("production").set(
                "serviceType",
                saved.path("production").path("serviceType")
        );
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(production.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    @Test
    void staleRevisionReturnsDedicatedConflictWithoutOverwritingWinner() throws Exception {
        String writeToken = token(List.of("logistics:express:config:write"));
        ObjectNode winner = request(0, "DISABLED", false);
        winner.withObject("sender").put("company", "winner-company");
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(winner.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));

        ObjectNode stale = request(0, "DISABLED", false);
        stale.withObject("sender").put("company", "stale-company");
        mockMvc.perform(put(ENDPOINT)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stale.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.WECHAT_EXPRESS_CONFIG_CONFLICT.code()));

        assertThat(jdbcClient.sql("""
                        select sender_company
                        from wechat_express_setting
                        where id = 1 and revision = 1
                        """).query(String.class).single()).isEqualTo("winner-company");
    }

    private ObjectNode request(long revision, String mode, boolean complete) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("revision", revision);
        root.put("mode", mode);
        root.put("messageEnabled", true);

        ObjectNode sender = root.putObject("sender");
        sender.put("name", complete ? "寄件人" : "");
        sender.put("mobile", complete ? "13800138000" : "");
        sender.put("company", complete ? "沐宝商城" : "");
        sender.put("province", complete ? "广东省" : "");
        sender.put("city", complete ? "深圳市" : "");
        sender.put("district", complete ? "南山区" : "");
        sender.put("detailAddress", complete ? "科技园测试路1号" : "");

        ObjectNode production = root.putObject("production");
        production.put("deliveryId", complete ? "SF" : "");
        production.put("deliveryName", complete ? "顺丰速运" : "");
        production.putNull("serviceType");
        production.put("serviceName", complete ? "标准快递" : "");
        production.put("clearBizId", false);

        ObjectNode parcel = root.putObject("defaultParcel");
        parcel.put("count", 1);
        parcel.put("weightKg", 1.25);
        parcel.put("lengthCm", 20.0);
        parcel.put("widthCm", 15.0);
        parcel.put("heightCm", 10.0);
        return root;
    }

    private JsonNode responseData(String body) throws Exception {
        return objectMapper.readTree(body).path("data");
    }

    private void assertExactConfigContract(JsonNode config) {
        assertExactFields(config,
                "mode", "messageEnabled", "sender", "production", "effective",
                "defaultParcel", "revision", "updatedAt");
        assertExactFields(config.path("sender"),
                "name", "mobile", "company", "province", "city", "district", "detailAddress");
        assertExactFields(config.path("production"),
                "deliveryId", "deliveryName", "bizIdMasked", "serviceType", "serviceName");
        assertExactFields(config.path("effective"),
                "deliveryId", "deliveryName", "bizIdMasked", "serviceType", "serviceName");
        assertExactFields(config.path("defaultParcel"),
                "count", "weightKg", "lengthCm", "widthCm", "heightCm");
    }

    private void assertExactFields(JsonNode object, String... expected) {
        List<String> actual = new ArrayList<>();
        object.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(expected);
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions);
    }
}
