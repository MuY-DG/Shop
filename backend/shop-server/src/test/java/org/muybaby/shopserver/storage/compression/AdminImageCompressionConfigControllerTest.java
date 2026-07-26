package org.muybaby.shopserver.storage.compression;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.storage.compression.config.ImageCompressionRuntimeConfigService;
import org.muybaby.shopserver.storage.compression.config.ImageCompressionUsageProbe;
import org.muybaby.shopserver.storage.compression.dto.AdminImageCompressionConfigRequest;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.storage.image-compression.api-key=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminImageCompressionConfigControllerTest.ProbeConfiguration.class)
class AdminImageCompressionConfigControllerTest {

    private static final String API_KEY = "tinify-database-secret-7890";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private ImageCompressionRuntimeConfigService configService;

    @Autowired
    private MutableProbe usageProbe;

    @BeforeEach
    void clearConfig() {
        jdbcClient.sql("delete from image_compression_runtime_setting").update();
        usageProbe.result = new ImageCompressionUsageProbe.ProbeResult(
                ImageCompressionUsageProbe.State.VALID, 7);
    }

    @AfterEach
    void cleanupConfig() {
        clearConfig();
    }

    @Test
    void configEndpointsRequireDedicatedAuthorities() throws Exception {
        String readToken = token(List.of("image-compression:config:read"));
        String writeToken = token(List.of("image-compression:config:write"));

        mockMvc.perform(get("/admin/image-compression/config"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/image-compression/config")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/image-compression/config")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/image-compression/config/refresh")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/image-compression/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedEnabled").value(true))
                .andExpect(jsonPath("$.data.effectiveEnabled").value(false))
                .andExpect(jsonPath("$.data.configSource").value("AUTO"))
                .andExpect(jsonPath("$.data.persisted").value(false))
                .andExpect(jsonPath("$.data.defaultConfigSource").value("AUTO"))
                .andExpect(jsonPath("$.data.keyConfigured").value(false))
                .andExpect(jsonPath("$.data.outputFormat").value("WEBP"))
                .andExpect(jsonPath("$.data.preserveMetadata").value(false))
                .andExpect(jsonPath("$.data.monthlyLimit").value(500))
                .andExpect(jsonPath("$.data.compressionCount").value(nullValue()))
                .andExpect(jsonPath("$.data.remainingCount").value(nullValue()))
                .andExpect(jsonPath("$.data.quotaPeriod").value(nullValue()));
    }

    @Test
    void savesOnlyEncryptedDatabaseKeyAndAutoFallsBackToIt() throws Exception {
        String writeToken = token(List.of("image-compression:config:write"));
        String readToken = token(List.of("image-compression:config:read"));

        updateConfig(writeToken, "DB", API_KEY, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedEnabled").value(true))
                .andExpect(jsonPath("$.data.effectiveEnabled").value(true))
                .andExpect(jsonPath("$.data.configSource").value("DB"))
                .andExpect(jsonPath("$.data.persisted").value(true))
                .andExpect(jsonPath("$.data.keyConfigured").value(true))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("tini******7890"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());

        SecretRow secret = jdbcClient.sql("""
                        select api_key_ciphertext, secret_cipher_version, secret_key_id
                        from image_compression_runtime_setting
                        where id = 1
                        """)
                .query((rs, rowNum) -> new SecretRow(
                        rs.getString("api_key_ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id")))
                .single();
        assertThat(secret.ciphertext()).startsWith("v1:").doesNotContain(API_KEY);
        assertThat(secret.cipherVersion()).isEqualTo(1);
        assertThat(secret.keyId()).isEmpty();

        mockMvc.perform(put("/admin/image-compression/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedEnabled": true,
                                  "configSource": "AUTO",
                                  "monthlyLimit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configSource").value("AUTO"))
                .andExpect(jsonPath("$.data.defaultConfigSource").value("DB"))
                .andExpect(jsonPath("$.data.keyConfigured").value(true));

        mockMvc.perform(get("/admin/image-compression/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value("tini******7890"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
        assertThat(configService.effective().apiKey()).isEqualTo(API_KEY);
    }

    @Test
    void refreshAndAtomicStateTransitionsKeepRequestedPreference() throws Exception {
        String writeToken = token(List.of("image-compression:config:write"));
        updateConfig(writeToken, "DB", API_KEY, 10).andExpect(status().isOk());

        mockMvc.perform(post("/admin/image-compression/config/refresh")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.compressionCount").value(7))
                .andExpect(jsonPath("$.data.remainingCount").value(3))
                .andExpect(jsonPath("$.data.effectiveEnabled").value(true))
                .andExpect(jsonPath("$.data.lastCheckedAt").isNotEmpty());

        YearMonth currentPeriod = YearMonth.now(ZoneOffset.UTC);
        configService.recordProviderCount(API_KEY, 4, currentPeriod);
        assertThat(configService.current().compressionCount()).isEqualTo(7);

        configService.markInvalidKey(API_KEY);
        assertThat(configService.current().requestedEnabled()).isTrue();
        assertThat(configService.current().effectiveEnabled()).isFalse();
        assertThat(configService.current().autoDisabledReason()).isEqualTo("INVALID_KEY");

        configService.recordProviderCount(API_KEY, 8, currentPeriod);
        assertThat(configService.current().compressionCount()).isEqualTo(8);
        assertThat(configService.current().autoDisabledReason()).isEmpty();
        assertThat(configService.current().effectiveEnabled()).isTrue();

        configService.markQuotaExhausted(API_KEY);
        assertThat(configService.current().requestedEnabled()).isTrue();
        assertThat(configService.current().effectiveEnabled()).isFalse();
        assertThat(configService.current().compressionCount()).isEqualTo(8);
        assertThat(configService.current().remainingCount()).isZero();
        assertThat(configService.current().autoDisabledReason()).isEqualTo("QUOTA_EXHAUSTED");

        jdbcClient.sql("""
                        update image_compression_runtime_setting
                        set quota_period = :oldPeriod
                        where id = 1
                        """)
                .param("oldPeriod", currentPeriod.minusMonths(1).toString())
                .update();
        assertThat(configService.current().compressionCount()).isNull();
        assertThat(configService.current().autoDisabledReason()).isEmpty();
        assertThat(configService.current().effectiveEnabled()).isTrue();

        configService.recordProviderCount(API_KEY, 2, currentPeriod);
        assertThat(configService.current().compressionCount()).isEqualTo(2);
        assertThat(configService.current().remainingCount()).isEqualTo(8);
        assertThat(configService.current().autoDisabledReason()).isEmpty();
        assertThat(configService.current().effectiveEnabled()).isTrue();
    }

    @Test
    void refreshPersistsOnlyPermanentProviderStates() throws Exception {
        String writeToken = token(List.of("image-compression:config:write"));
        updateConfig(writeToken, "DB", API_KEY, 10).andExpect(status().isOk());

        usageProbe.result = new ImageCompressionUsageProbe.ProbeResult(
                ImageCompressionUsageProbe.State.QUOTA_EXHAUSTED, null);
        mockMvc.perform(post("/admin/image-compression/config/refresh")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedEnabled").value(true))
                .andExpect(jsonPath("$.data.effectiveEnabled").value(false))
                .andExpect(jsonPath("$.data.autoDisabledReason").value("QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.data.compressionCount").value(nullValue()))
                .andExpect(jsonPath("$.data.remainingCount").value(0))
                .andExpect(jsonPath("$.data.quotaPeriod").isNotEmpty());

        usageProbe.result = new ImageCompressionUsageProbe.ProbeResult(
                ImageCompressionUsageProbe.State.VALID, 3);
        mockMvc.perform(post("/admin/image-compression/config/refresh")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveEnabled").value(true))
                .andExpect(jsonPath("$.data.autoDisabledReason").value(""))
                .andExpect(jsonPath("$.data.compressionCount").value(3));

        usageProbe.result = new ImageCompressionUsageProbe.ProbeResult(
                ImageCompressionUsageProbe.State.INVALID_KEY, null);
        mockMvc.perform(post("/admin/image-compression/config/refresh")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveEnabled").value(false))
                .andExpect(jsonPath("$.data.autoDisabledReason").value("INVALID_KEY"));

        updateConfig(writeToken, "DB", API_KEY + "-valid", 10)
                .andExpect(status().isOk());
        usageProbe.result = new ImageCompressionUsageProbe.ProbeResult(
                ImageCompressionUsageProbe.State.RATE_LIMITED, null);
        mockMvc.perform(post("/admin/image-compression/config/refresh")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
        assertThat(configService.current().autoDisabledReason()).isEmpty();
        assertThat(configService.current().effectiveEnabled()).isTrue();
    }

    @Test
    void raisingMonthlyLimitReenablesAConfigDisabledByItsLocalBudget() {
        configService.update(new AdminImageCompressionConfigRequest(
                true, "DB", API_KEY, 10));
        configService.recordProviderCount(API_KEY, 10);

        assertThat(configService.current().effectiveEnabled()).isFalse();
        assertThat(configService.current().autoDisabledReason())
                .isEqualTo("QUOTA_EXHAUSTED");

        configService.update(new AdminImageCompressionConfigRequest(
                true, "DB", null, 20));

        assertThat(configService.current().compressionCount()).isEqualTo(10);
        assertThat(configService.current().remainingCount()).isEqualTo(10);
        assertThat(configService.current().autoDisabledReason()).isEmpty();
        assertThat(configService.current().effectiveEnabled()).isTrue();
    }

    @Test
    void changingTheKeyResetsUsageAndIgnoresResultsFromThePreviousKey() {
        String replacementKey = API_KEY + "-replacement";
        configService.update(new AdminImageCompressionConfigRequest(
                true, "DB", API_KEY, 10));
        configService.recordProviderCount(API_KEY, 8);

        var replacement = configService.update(new AdminImageCompressionConfigRequest(
                true, "DB", replacementKey, 10));

        assertThat(replacement.compressionCount()).isNull();
        assertThat(replacement.remainingCount()).isNull();
        assertThat(replacement.effectiveEnabled()).isTrue();

        configService.markQuotaExhausted(API_KEY);
        assertThat(configService.current().effectiveEnabled()).isTrue();
        assertThat(configService.current().autoDisabledReason()).isEmpty();

        configService.recordProviderCount(replacementKey, 2);
        assertThat(configService.current().compressionCount()).isEqualTo(2);
        assertThat(configService.current().remainingCount()).isEqualTo(8);
    }

    @Test
    void damagedDatabaseKeyFailsClosedAndCanBeReplaced() {
        jdbcClient.sql("""
                        insert into image_compression_runtime_setting
                            (id, admin_configured, requested_enabled, config_source,
                             api_key_ciphertext, monthly_limit)
                        values
                            (1, true, true, 'DB', 'damaged-envelope', 10)
                        """)
                .update();

        var damaged = configService.current();
        assertThat(damaged.requestedEnabled()).isTrue();
        assertThat(damaged.effectiveEnabled()).isFalse();
        assertThat(damaged.keyConfigured()).isFalse();
        assertThat(damaged.autoDisabledReason()).isEqualTo("INVALID_KEY");

        var repaired = configService.update(new AdminImageCompressionConfigRequest(
                true, "DB", API_KEY, 10));
        assertThat(repaired.effectiveEnabled()).isTrue();
        assertThat(repaired.keyConfigured()).isTrue();
        assertThat(repaired.autoDisabledReason()).isEmpty();
        assertThat(configService.effective().apiKey()).isEqualTo(API_KEY);
    }

    @Test
    void invalidKeyStatePersistsAcrossQuotaPeriods() {
        configService.update(new AdminImageCompressionConfigRequest(
                true, "DB", API_KEY, 10));
        configService.markInvalidKey(API_KEY);
        jdbcClient.sql("""
                        update image_compression_runtime_setting
                        set quota_period = :oldPeriod
                        where id = 1
                        """)
                .param("oldPeriod", YearMonth.now(ZoneOffset.UTC)
                        .minusMonths(1).toString())
                .update();

        assertThat(configService.current().compressionCount()).isNull();
        assertThat(configService.current().remainingCount()).isNull();
        assertThat(configService.current().autoDisabledReason()).isEqualTo("INVALID_KEY");
        assertThat(configService.current().effectiveEnabled()).isFalse();
    }

    @Test
    void activeReservationsPreventConcurrentUploadsFromExceedingTheLocalBudget() {
        configService.update(new AdminImageCompressionConfigRequest(
                true, "DB", API_KEY, 2));

        var first = configService.acquireCompressionPermit(2);
        var second = configService.acquireCompressionPermit(2);

        assertThat(first).isNotNull();
        assertThat(second).isNull();
        assertThat(configService.current().compressionCount()).isNull();

        configService.releaseCompressionPermit(first.reservationId());
        var afterRelease = configService.acquireCompressionPermit(2);
        assertThat(afterRelease).isNotNull();
        configService.releaseCompressionPermit(afterRelease.reservationId());
    }

    @Test
    void migrationGrantsMenuAndPermissionsOnlyToSuperRole() {
        Integer menuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 804
                          and parent_id = 800
                          and component = '/configuration/image-compression'
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where id in (18003, 18004)
                          and auth_mark in (
                              'image-compression:config:read',
                              'image-compression:config:write'
                          )
                        """)
                .query(Integer.class)
                .single();
        List<String> roleCodes = jdbcClient.sql("""
                        select distinct role_entry.code
                        from admin_role role_entry
                        join admin_role_menu role_menu on role_menu.role_id = role_entry.id
                        join admin_role_permission role_permission
                          on role_permission.role_id = role_entry.id
                        where role_menu.menu_id = 804
                          and role_permission.permission_id in (18003, 18004)
                        order by role_entry.code
                        """)
                .query(String.class)
                .list();

        assertThat(menuCount).isEqualTo(1);
        assertThat(permissionCount).isEqualTo(2);
        assertThat(roleCodes).containsExactly("R_SUPER");
    }

    private org.springframework.test.web.servlet.ResultActions updateConfig(
            String token,
            String source,
            String apiKey,
            int monthlyLimit
    ) throws Exception {
        return mockMvc.perform(put("/admin/image-compression/config")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "requestedEnabled": true,
                          "configSource": "%s",
                          "apiKey": "%s",
                          "monthlyLimit": %d
                        }
                        """.formatted(source, apiKey, monthlyLimit)));
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions);
    }

    private record SecretRow(String ciphertext, int cipherVersion, String keyId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        @Primary
        MutableProbe imageCompressionUsageProbe() {
            return new MutableProbe();
        }
    }

    static final class MutableProbe implements ImageCompressionUsageProbe {

        private ProbeResult result = new ProbeResult(State.VALID, 7);

        @Override
        public ProbeResult probe(String apiKey) {
            return result;
        }
    }
}
