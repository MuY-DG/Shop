package org.muybaby.shopserver.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.compliance.service.MerchantComplianceService;
import org.muybaby.shopserver.product.dto.AdminSkuResponse;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.dto.ProductFoodDisclosureRequest;
import org.muybaby.shopserver.product.dto.ProductFoodDisclosureResponse;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.product.service.ProductFoodComplianceService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.muybaby.shopserver.product.ProductFoodComplianceTestSupport.completeFoodDisclosure;
import static org.muybaby.shopserver.product.ProductFoodComplianceTestSupport.createDraftProduct;
import static org.muybaby.shopserver.product.ProductFoodComplianceTestSupport.insertPublicImage;
import static org.muybaby.shopserver.product.ProductFoodComplianceTestSupport.publishCurrentMerchantQualification;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProductFoodComplianceServiceTest {

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private ProductFoodComplianceService productFoodComplianceService;

    @Autowired
    private ProductReadMapper productReadMapper;

    @Autowired
    private MerchantComplianceService merchantComplianceService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearCurrentMerchantQualification() {
        jdbcClient.sql("delete from storage_asset_usage where owner_type = 'MERCHANT_PUBLICATION'").update();
        jdbcClient.sql("delete from merchant_publication_revision").update();
    }

    @Test
    void unclassifiedProductsFailClosedWhileNonFoodProductsCanPublish() {
        ProductFoodComplianceTestSupport.CreatedProduct product =
                createDraftProduct(adminProductService, jdbcClient, "");

        ProductFoodDisclosureResponse initial = productFoodComplianceService.get(product.spuId());
        assertThat(initial.complianceType()).isEqualTo(ProductComplianceType.UNCLASSIFIED.name());
        assertThat(initial.labelAssets()).isEmpty();
        assertThatThrownBy(() -> adminProductService.publishSpu(product.spuId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));

        ProductFoodDisclosureResponse nonFood = productFoodComplianceService.update(
                product.spuId(),
                new ProductFoodDisclosureRequest(
                        ProductComplianceType.NON_FOOD.name(),
                        "不应保留的食品名称",
                        "不应保留的配料",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of())
        );
        assertThat(nonFood.complianceType()).isEqualTo(ProductComplianceType.NON_FOOD.name());
        assertThat(nonFood.foodName()).isEmpty();
        assertThat(nonFood.ingredients()).isEmpty();

        adminProductService.publishSpu(product.spuId());

        assertThat(productStatus(product.spuId())).isEqualTo(ProductStatus.ON_SALE.name());
    }

    @Test
    void foodPublishRequiresCompleteDisclosureManagedLabelAndCurrentMerchantQualification() {
        ProductFoodComplianceTestSupport.CreatedProduct product =
                createDraftProduct(adminProductService, jdbcClient, "净含量 300 克");
        ProductFoodComplianceTestSupport.StoredFile label = insertPublicImage(jdbcClient, "food-label.png");

        ProductFoodDisclosureRequest incomplete = new ProductFoodDisclosureRequest(
                ProductComplianceType.FOOD.name(),
                "原味燕麦脆",
                "",
                "含牛奶",
                "阴凉干燥处保存",
                "9 个月",
                "广东合规食品制造有限公司",
                "广东省广州市番禺区食品工业路 8 号",
                "SC12345678901234",
                "广东省广州市",
                "过敏人群谨慎食用",
                "生产日期及批次见包装喷码",
                List.of(new org.muybaby.shopserver.product.dto.ProductFoodLabelAssetRequest(
                        label.id(), label.publicUrl(), 0))
        );
        productFoodComplianceService.update(product.spuId(), incomplete);
        assertThatThrownBy(() -> adminProductService.publishSpu(product.spuId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));

        ProductFoodDisclosureResponse complete = productFoodComplianceService.update(
                product.spuId(),
                completeFoodDisclosure(label.id(), "生产日期及批次以包装标示为准"));
        assertThat(complete.allergenInformation()).isEmpty();
        assertThat(complete.consumerNotice()).isEmpty();
        assertThat(complete.variableProductionNotice()).isEqualTo("生产日期及批次以包装标示为准");
        assertThat(complete.labelAssets()).singleElement()
                .satisfies(asset -> {
                    assertThat(asset.fileId()).isEqualTo(label.id());
                    assertThat(asset.url()).isEqualTo(label.publicUrl());
                });
        assertThatThrownBy(() -> adminProductService.publishSpu(product.spuId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));

        publishCurrentMerchantQualification(jdbcClient, merchantComplianceService);
        jdbcClient.sql("""
                        update storage_asset_usage
                        set status = 'REMOVED'
                        where owner_type = 'PRODUCT_FOOD_DISCLOSURE'
                          and owner_id = :spuId
                          and usage_type = 'PRODUCT_FOOD_LABEL'
                        """)
                .param("spuId", product.spuId())
                .update();
        assertThatThrownBy(() -> adminProductService.publishSpu(product.spuId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));
        productFoodComplianceService.update(
                product.spuId(),
                completeFoodDisclosure(label.id(), "生产日期及批次以包装标示为准"));
        adminProductService.publishSpu(product.spuId());

        assertThat(productStatus(product.spuId())).isEqualTo(ProductStatus.ON_SALE.name());
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :fileId
                          and usage_type = 'PRODUCT_FOOD_LABEL'
                          and owner_type = 'PRODUCT_FOOD_DISCLOSURE'
                          and owner_id = :spuId
                          and status = 'ACTIVE'
                        """)
                .param("fileId", label.id())
                .param("spuId", product.spuId())
                .query(Integer.class)
                .single()).isEqualTo(1);

        AdminSpuDetailResponse detail = productReadMapper.adminSpuDetail(product.spuId());
        AdminSkuResponse persistedSku = detail.skus().getFirst();
        AdminSkuUpsertRequest invalidSkuUpdate = new AdminSkuUpsertRequest(
                persistedSku.id(),
                persistedSku.skuCode(),
                persistedSku.specJson(),
                persistedSku.specText(),
                persistedSku.priceCent(),
                persistedSku.originalPriceCent(),
                persistedSku.stockAvailable(),
                persistedSku.weightGram(),
                persistedSku.image(),
                persistedSku.imageFileId(),
                persistedSku.status(),
                persistedSku.sortOrder());
        invalidSkuUpdate.setNetContentText("");
        assertThatThrownBy(() -> adminProductService.updateSpu(
                product.spuId(),
                new AdminSpuUpsertRequest(
                        detail.categoryId(),
                        detail.title(),
                        detail.subtitle(),
                        detail.mainImage(),
                        detail.mainImageFileId(),
                        detail.sellingPoints(),
                        detail.detailHtml(),
                        detail.sortOrder(),
                        List.of(),
                        List.of(invalidSkuUpdate))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));
        assertThat(jdbcClient.sql("select net_content_text from product_sku where id = :skuId")
                .param("skuId", product.skuId())
                .query(String.class)
                .single()).isEqualTo("净含量 300 克");
    }

    @Test
    void foodPublishRejectsFixedProductionDateAndMissingEnabledSkuNetContent() {
        ProductFoodComplianceTestSupport.CreatedProduct product =
                createDraftProduct(adminProductService, jdbcClient, "");
        ProductFoodComplianceTestSupport.StoredFile label = insertPublicImage(jdbcClient, "variable-label.png");
        publishCurrentMerchantQualification(jdbcClient, merchantComplianceService);

        ProductFoodDisclosureRequest requiredFields =
                completeFoodDisclosure(label.id(), "生产日期及批次详见包装");
        productFoodComplianceService.update(
                product.spuId(),
                new ProductFoodDisclosureRequest(
                        requiredFields.complianceType(),
                        requiredFields.foodName(),
                        requiredFields.ingredients(),
                        "待补充过敏原信息",
                        requiredFields.storageConditions(),
                        requiredFields.shelfLifeDescription(),
                        requiredFields.manufacturerName(),
                        requiredFields.manufacturerAddress(),
                        requiredFields.productionLicenseNumber(),
                        requiredFields.origin(),
                        requiredFields.consumerNotice(),
                        requiredFields.variableProductionNotice(),
                        requiredFields.labelAssets()));
        assertThatThrownBy(() -> adminProductService.publishSpu(product.spuId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));

        productFoodComplianceService.update(
                product.spuId(),
                completeFoodDisclosure(label.id(), "生产日期为 2026年8月9日，批次见包装喷码"));
        assertThatThrownBy(() -> adminProductService.publishSpu(product.spuId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));

        productFoodComplianceService.update(
                product.spuId(),
                completeFoodDisclosure(label.id(), "生产日期及批次见包装喷码"));
        assertThatThrownBy(() -> adminProductService.publishSpu(product.spuId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));

        jdbcClient.sql("update product_sku set net_content_text = '净含量 300 克' where id = :skuId")
                .param("skuId", product.skuId())
                .update();
        adminProductService.publishSpu(product.spuId());

        assertThat(productStatus(product.spuId())).isEqualTo(ProductStatus.ON_SALE.name());
    }

    private String productStatus(Long spuId) {
        return jdbcClient.sql("select status from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(String.class)
                .single();
    }
}
