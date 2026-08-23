package org.muybaby.shopserver.compliance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.compliance.dto.LegalDocumentDraftRequest;
import org.muybaby.shopserver.compliance.dto.LegalDocumentResponse;
import org.muybaby.shopserver.compliance.dto.MerchantPublicationDraftRequest;
import org.muybaby.shopserver.compliance.dto.MerchantPublicationResponse;
import org.muybaby.shopserver.compliance.service.LegalDocumentService;
import org.muybaby.shopserver.compliance.service.MerchantComplianceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.compliance.privacy-consent-required=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComplianceControllerTest {

    private static final long BUSINESS_LICENSE_ASSET_ID = 9_880_001L;
    private static final long FOOD_LICENSE_ASSET_ID = 9_880_002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private LegalDocumentService legalDocumentService;

    @Autowired
    private MerchantComplianceService merchantComplianceService;

    @BeforeEach
    void clearComplianceFixtures() {
        jdbcClient.sql("delete from app_user_document_consent").update();
        jdbcClient.sql("delete from legal_document_revision").update();
        jdbcClient.sql("delete from storage_asset_usage where owner_type = 'MERCHANT_PUBLICATION'").update();
        jdbcClient.sql("delete from merchant_publication_revision").update();
        jdbcClient.sql("delete from storage_asset where id in (:businessId, :foodId)")
                .param("businessId", BUSINESS_LICENSE_ASSET_ID)
                .param("foodId", FOOD_LICENSE_ASSET_ID)
                .update();
    }

    @Test
    void migrationCreatesVersionedTablesRbacAndUnusedPublicState() throws Exception {
        assertThat(columnCount("merchant_publication_revision")).isEqualTo(21);
        assertThat(columnCount("legal_document_revision")).isEqualTo(14);
        assertThat(columnCount("app_user_document_consent")).isEqualTo(10);
        assertThat(jdbcClient.sql("""
                        select count(*) from admin_permission
                        where auth_mark in (
                            'compliance:merchant:read', 'compliance:merchant:write',
                            'compliance:document:read', 'compliance:document:write'
                        )
                        """).query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbcClient.sql("""
                        select count(*) from admin_menu
                        where id in (900, 901, 902)
                        """).query(Integer.class).single()).isEqualTo(3);

        mockMvc.perform(get("/app/compliance/merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(get("/app/compliance/documents/PRIVACY_POLICY/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(get("/admin/compliance/merchant"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.code()));
    }

    @Test
    void publishedLegalRevisionsAreImmutableCurrentHistory() throws Exception {
        LegalDocumentResponse first = legalDocumentService.createDraft(
                LegalDocumentType.PRIVACY_POLICY,
                new LegalDocumentDraftRequest("2026.08.1", "隐私保护指引", "第一版正式内容", null),
                1L);
        legalDocumentService.publish(first.id(), 1L);
        LegalDocumentResponse second = legalDocumentService.createDraft(
                LegalDocumentType.PRIVACY_POLICY,
                new LegalDocumentDraftRequest("2026.08.2", "隐私保护指引", "第二版正式内容", null),
                1L);
        legalDocumentService.publish(second.id(), 1L);

        assertThat(legalDocumentService.current(LegalDocumentType.PRIVACY_POLICY).version())
                .isEqualTo("2026.08.2");
        assertThat(legalDocumentService.history(LegalDocumentType.PRIVACY_POLICY))
                .extracting(LegalDocumentResponse::status)
                .containsExactly("PUBLISHED", "SUPERSEDED");
        assertThat(jdbcClient.sql("""
                        select content from legal_document_revision where id = :id
                        """).param("id", first.id()).query(String.class).single())
                .isEqualTo("第一版正式内容");

        mockMvc.perform(get("/app/compliance/documents/PRIVACY_POLICY/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("2026.08.2"))
                .andExpect(jsonPath("$.data.content").value("第二版正式内容"));
    }

    @Test
    void legalDocumentsRejectPlaceholderContentAndUnsupportedFuturePublication() {
        LegalDocumentResponse placeholder = legalDocumentService.createDraft(
                LegalDocumentType.USER_AGREEMENT,
                new LegalDocumentDraftRequest("2026.08.placeholder", "用户协议", "待填写正式条款", null),
                1L);
        assertThatThrownBy(() -> legalDocumentService.publish(placeholder.id(), 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        LegalDocumentResponse future = legalDocumentService.createDraft(
                LegalDocumentType.USER_AGREEMENT,
                new LegalDocumentDraftRequest(
                        "2026.08.future",
                        "用户协议",
                        "已经审核且内容完整的用户协议正文",
                        LocalDateTime.now(ZoneOffset.UTC).plusDays(1)),
                1L);
        assertThatThrownBy(() -> legalDocumentService.publish(future.id(), 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(legalDocumentService.current(LegalDocumentType.USER_AGREEMENT)).isNull();
    }

    @Test
    void loginRequiresAndPersistsTheExactPublishedPrivacyRevision() throws Exception {
        LegalDocumentResponse current = legalDocumentService.createDraft(
                LegalDocumentType.PRIVACY_POLICY,
                new LegalDocumentDraftRequest("2026.08.3", "隐私保护指引", "经审核的隐私内容", null),
                1L);
        legalDocumentService.publish(current.id(), 1L);

        mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"compliance-missing-consent",
                                  "privacyPolicyVersion":"2026.08.3",
                                  "privacyPolicyAccepted":false,
                                  "miniProgramEnv":"release"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"compliance-stale-policy",
                                  "privacyPolicyVersion":"2026.08.2",
                                  "privacyPolicyAccepted":true,
                                  "miniProgramEnv":"release"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"compliance-current-policy",
                                  "privacyPolicyVersion":"2026.08.3",
                                  "privacyPolicyAccepted":true,
                                  "miniProgramEnv":"release"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString());

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from app_user_document_consent consent
                        join legal_document_revision document
                          on document.id = consent.legal_document_revision_id
                        where consent.document_version = '2026.08.3'
                          and consent.content_sha256 = document.content_sha256
                          and consent.mini_program_env = 'release'
                        """).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*) from app_user
                        where openid in ('test-compliance-missing-consent', 'test-compliance-stale-policy')
                        """).query(Integer.class).single()).isZero();
    }

    @Test
    void merchantPublicationAllowsIncompleteFactsAndPublishesManagedAssets() throws Exception {
        MerchantPublicationResponse incomplete = merchantComplianceService.createDraft(
                new MerchantPublicationDraftRequest(
                        "", "", "", "", "", "", null,
                        "", "", null, null, null),
                1L);
        MerchantPublicationResponse publishedIncomplete =
                merchantComplianceService.publish(incomplete.id(), 1L);
        assertThat(publishedIncomplete.status()).isEqualTo("PUBLISHED");

        MerchantPublicationResponse malformed = merchantComplianceService.createDraft(
                new MerchantPublicationDraftRequest(
                        "真实主体名称", "LIMITED_COMPANY", "BAD-CODE",
                        "真实经营地址", "020-12345678", "020-87654321",
                        null, "食品经营许可证", "JY14401010000001",
                        null, LocalDate.now().minusYears(1),
                        LocalDate.now().minusYears(2)),
                1L);
        assertThatThrownBy(() -> merchantComplianceService.publish(malformed.id(), 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        insertPublicImage(BUSINESS_LICENSE_ASSET_ID, "compliance/business-license.png");
        insertPublicImage(FOOD_LICENSE_ASSET_ID, "compliance/food-license.png");
        MerchantPublicationResponse draft = merchantComplianceService.createDraft(
                new MerchantPublicationDraftRequest(
                        "真实主体名称", "LIMITED_COMPANY", "91440101MA5ABCDE12",
                        "真实经营地址", "020-12345678", "020-87654321",
                        BUSINESS_LICENSE_ASSET_ID, "食品经营许可证", "JY14401010000001",
                        FOOD_LICENSE_ASSET_ID, LocalDate.now().minusYears(1),
                        LocalDate.now().plusYears(1)),
                1L);
        MerchantPublicationResponse published = merchantComplianceService.publish(draft.id(), 1L);

        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.businessLicenseUrl()).endsWith("/compliance/business-license.png");
        assertThat(jdbcClient.sql("""
                        select count(*) from storage_asset_usage
                        where owner_type = 'MERCHANT_PUBLICATION'
                          and owner_id = :ownerId
                          and usage_type in ('MERCHANT_BUSINESS_LICENSE', 'MERCHANT_FOOD_QUALIFICATION')
                          and protected = true
                        """).param("ownerId", draft.id()).query(Integer.class).single()).isEqualTo(2);

        mockMvc.perform(get("/app/compliance/merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.legalName").value("真实主体名称"))
                .andExpect(jsonPath("$.data.businessLicenseAssetId").isNumber())
                .andExpect(jsonPath("$.data.businessLicenseAssetId").value(BUSINESS_LICENSE_ASSET_ID))
                .andExpect(jsonPath("$.data.foodQualificationAssetId").isNumber())
                .andExpect(jsonPath("$.data.foodQualificationAssetId").value(FOOD_LICENSE_ASSET_ID))
                .andExpect(jsonPath("$.data.businessLicenseUrl").isString());
    }

    private int columnCount(String tableName) {
        return jdbcClient.sql("""
                        select count(*) from information_schema.columns
                        where lower(table_name) = :tableName
                        """)
                .param("tableName", tableName)
                .query(Integer.class)
                .single();
    }

    private void insertPublicImage(long id, String objectKey) {
        jdbcClient.sql("""
                        insert into storage_asset (
                            id, scope, media_kind, folder_id, visibility, provider,
                            storage_container, object_key, original_filename, content_type,
                            extension, size_bytes, sha256, width, height, alt_text, tags_json,
                            public_url, status, uploaded_by_type, uploaded_by_id
                        ) values (
                            :id, 'LIBRARY', 'IMAGE', null, 'PUBLIC', 'TENCENT_COS',
                            'shop-test', :objectKey, 'qualification.png', 'image/png',
                            'png', 68, :sha256, 1, 1, '资质原件', null,
                            concat('https://assets.example.test/', :objectKey),
                            'ACTIVE', 'ADMIN', 1
                        )
                        """)
                .param("id", id)
                .param("objectKey", objectKey)
                .param("sha256", "qualification-" + id)
                .update();
    }
}
