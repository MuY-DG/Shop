package org.muybaby.shopserver.product;

import org.muybaby.shopserver.compliance.dto.MerchantPublicationDraftRequest;
import org.muybaby.shopserver.compliance.dto.MerchantPublicationResponse;
import org.muybaby.shopserver.compliance.service.MerchantComplianceService;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.dto.ProductFoodDisclosureRequest;
import org.muybaby.shopserver.product.dto.ProductFoodLabelAssetRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class ProductFoodComplianceTestSupport {

    private static final AtomicLong FILE_SEQUENCE = new AtomicLong(8_990_000L);

    private ProductFoodComplianceTestSupport() {
    }

    static CreatedProduct createDraftProduct(
            AdminProductService adminProductService,
            JdbcClient jdbcClient,
            String netContentText
    ) {
        String suffix = Long.toUnsignedString(System.nanoTime());
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "食品合规分类-" + suffix, "", null, 1, "ENABLED"));
        AdminSkuUpsertRequest sku = new AdminSkuUpsertRequest(
                null,
                "FOOD-COMPLIANCE-SKU-" + suffix,
                "{}",
                "默认规格",
                2_990L,
                3_590L,
                20,
                300,
                "https://assets.example.test/product-sku-" + suffix + ".png",
                null,
                "ENABLED",
                1
        );
        sku.setNetContentText(netContentText);
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "食品合规商品-" + suffix,
                "结构化食品披露回归",
                "https://assets.example.test/product-main-" + suffix + ".png",
                null,
                "配料清晰,标签可查",
                "<p>商品详情正文</p>",
                1,
                List.of(),
                List.of(sku)
        ));
        Long skuId = jdbcClient.sql("select id from product_sku where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        return new CreatedProduct(spuId, skuId);
    }

    static ProductFoodDisclosureRequest completeFoodDisclosure(long labelFileId, String variableNotice) {
        return new ProductFoodDisclosureRequest(
                ProductComplianceType.FOOD.name(),
                "原味燕麦脆",
                "燕麦片、全脂乳粉、白砂糖、食用植物油",
                "",
                "阴凉、干燥处密封保存，开封后请尽快食用",
                "未开封条件下保质期 9 个月",
                "广东合规食品制造有限公司",
                "广东省广州市番禺区食品工业路 8 号",
                "SC12345678901234",
                "广东省广州市",
                "",
                variableNotice,
                List.of(new ProductFoodLabelAssetRequest(
                        labelFileId,
                        "https://untrusted.example.test/client-snapshot.png",
                        0))
        );
    }

    static StoredFile insertPublicImage(JdbcClient jdbcClient, String objectName) {
        long id = FILE_SEQUENCE.incrementAndGet();
        String objectKey = "food-compliance/" + id + "-" + objectName;
        String publicUrl = "https://assets.example.test/" + objectKey;
        jdbcClient.sql("""
                        insert into storage_asset (
                            id, scope, media_kind, folder_id, visibility, provider,
                            storage_container, object_key, original_filename, content_type,
                            extension, size_bytes, sha256, width, height, alt_text, tags_json,
                            public_url, status, uploaded_by_type, uploaded_by_id
                        ) values (
                            :id, 'LIBRARY', 'IMAGE', null, 'PUBLIC', 'TENCENT_COS',
                            'shop-test', :objectKey, :objectName, 'image/png',
                            'png', 68, :sha256, 1, 1, '自动化测试图片', null,
                            :publicUrl, 'ACTIVE', 'ADMIN', 1
                        )
                        """)
                .param("id", id)
                .param("objectKey", objectKey)
                .param("objectName", objectName)
                .param("sha256", "food-compliance-" + id)
                .param("publicUrl", publicUrl)
                .update();
        return new StoredFile(id, publicUrl);
    }

    static MerchantPublicationResponse publishCurrentMerchantQualification(
            JdbcClient jdbcClient,
            MerchantComplianceService merchantComplianceService
    ) {
        StoredFile businessLicense = insertPublicImage(jdbcClient, "business-license.png");
        StoredFile foodQualification = insertPublicImage(jdbcClient, "food-qualification.png");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        MerchantPublicationResponse draft = merchantComplianceService.createDraft(
                new MerchantPublicationDraftRequest(
                        "自动化测试食品经营主体",
                        "LIMITED_COMPANY",
                        "91440101MA5ABCDE12",
                        "广东省广州市番禺区测试园区 1 号",
                        "020-12345678",
                        "020-87654321",
                        businessLicense.id(),
                        "食品经营许可证",
                        "JY14401010000001",
                        foodQualification.id(),
                        today.minusYears(1),
                        today.plusYears(1)
                ),
                1L
        );
        return merchantComplianceService.publish(draft.id(), 1L);
    }

    record CreatedProduct(Long spuId, Long skuId) {
    }

    record StoredFile(Long id, String publicUrl) {
    }
}
