package org.muybaby.shopserver.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.compliance.service.MerchantComplianceService;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.muybaby.shopserver.product.ProductFoodComplianceTestSupport.createDraftProduct;
import static org.muybaby.shopserver.product.ProductFoodComplianceTestSupport.insertPublicImage;
import static org.muybaby.shopserver.product.ProductFoodComplianceTestSupport.publishCurrentMerchantQualification;
import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProductFoodComplianceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private MerchantComplianceService merchantComplianceService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void clearCurrentMerchantQualification() {
        jdbcClient.sql("delete from storage_asset_usage where owner_type = 'MERCHANT_PUBLICATION'").update();
        jdbcClient.sql("delete from merchant_publication_revision").update();
    }

    @Test
    void adminRoundTripUsesManagedLabelUrlAndAppDetailExposesDedicatedFoodDisclosure() throws Exception {
        String token = issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("product:spu:update", "product:spu:publish"));
        ProductFoodComplianceTestSupport.CreatedProduct product =
                createDraftProduct(adminProductService, jdbcClient, "净含量 300 克");
        ProductFoodComplianceTestSupport.StoredFile label = insertPublicImage(jdbcClient, "controller-label.png");

        mockMvc.perform(get("/admin/product/spus/" + product.spuId() + "/food-disclosure"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.code()));

        mockMvc.perform(get("/admin/product/spus/" + product.spuId() + "/food-disclosure")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complianceType").value("NON_FOOD"))
                .andExpect(jsonPath("$.data.labelAssets").isEmpty());

        mockMvc.perform(put("/admin/product/spus/" + product.spuId() + "/food-disclosure")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "complianceType": "FOOD",
                                  "foodName": "原味燕麦脆",
                                  "ingredients": "燕麦片、全脂乳粉、白砂糖、食用植物油",
                                  "allergenInformation": "含牛奶及燕麦制品；同一生产线加工含大豆的产品",
                                  "storageConditions": "阴凉、干燥处密封保存，开封后请尽快食用",
                                  "shelfLifeDescription": "未开封条件下保质期 9 个月",
                                  "manufacturerName": "广东合规食品制造有限公司",
                                  "manufacturerAddress": "广东省广州市番禺区食品工业路 8 号",
                                  "productionLicenseNumber": "SC12345678901234",
                                  "origin": "广东省广州市",
                                  "consumerNotice": "婴幼儿及对配料中过敏原敏感的人群请谨慎食用",
                                  "variableProductionNotice": "生产日期及批次见包装喷码",
                                  "labelAssets": [
                                    {
                                      "fileId": %d,
                                      "url": "https://untrusted.example.test/forged-label.png",
                                      "sortOrder": 0
                                    }
                                  ]
                                }
                                """.formatted(label.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complianceType").value("FOOD"))
                .andExpect(jsonPath("$.data.foodName").value("原味燕麦脆"))
                .andExpect(jsonPath("$.data.ingredients").value("燕麦片、全脂乳粉、白砂糖、食用植物油"))
                .andExpect(jsonPath("$.data.allergenInformation").isString())
                .andExpect(jsonPath("$.data.storageConditions").isString())
                .andExpect(jsonPath("$.data.shelfLifeDescription").isString())
                .andExpect(jsonPath("$.data.manufacturerName").isString())
                .andExpect(jsonPath("$.data.manufacturerAddress").isString())
                .andExpect(jsonPath("$.data.productionLicenseNumber").value("SC12345678901234"))
                .andExpect(jsonPath("$.data.origin").value("广东省广州市"))
                .andExpect(jsonPath("$.data.consumerNotice").isString())
                .andExpect(jsonPath("$.data.variableProductionNotice").value("生产日期及批次见包装喷码"))
                .andExpect(jsonPath("$.data.labelAssets[0].fileId").isNumber())
                .andExpect(jsonPath("$.data.labelAssets[0].fileId").value(label.id()))
                .andExpect(jsonPath("$.data.labelAssets[0].url").value(label.publicUrl()))
                .andExpect(jsonPath("$.data.labelAssets[0].sortOrder").value(0));

        publishCurrentMerchantQualification(jdbcClient, merchantComplianceService);
        mockMvc.perform(post("/admin/product/spus/" + product.spuId() + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + product.spuId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].netContentText").value("净含量 300 克"));

        String appDetail = mockMvc.perform(get("/app/product/spus/" + product.spuId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.foodDisclosure.complianceType").value("FOOD"))
                .andExpect(jsonPath("$.data.foodDisclosure.foodName").value("原味燕麦脆"))
                .andExpect(jsonPath("$.data.foodDisclosure.labelAssets[0].url").value(label.publicUrl()))
                .andExpect(jsonPath("$.data.skus[0].netContentText").value("净含量 300 克"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(appDetail.indexOf("\"foodDisclosure\""))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(appDetail.indexOf("\"detailHtml\""));
        assertThat(appDetail).doesNotContain("untrusted.example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNCLASSIFIED", "OTHER"})
    void rejectsRemovedOrUnknownComplianceTypes(String complianceType) throws Exception {
        String token = issueAdminToken(jdbcClient, opaqueTokenService, List.of("product:spu:update"));
        ProductFoodComplianceTestSupport.CreatedProduct product =
                createDraftProduct(adminProductService, jdbcClient, "");

        mockMvc.perform(put("/admin/product/spus/" + product.spuId() + "/food-disclosure")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"complianceType": "%s"}
                                """.formatted(complianceType)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        mockMvc.perform(get("/admin/product/spus/" + product.spuId() + "/food-disclosure")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complianceType").value("NON_FOOD"));
    }
}
