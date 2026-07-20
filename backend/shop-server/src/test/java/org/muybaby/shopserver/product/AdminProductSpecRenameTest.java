package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuSpecGroupResponse;
import org.muybaby.shopserver.product.dto.AdminSpuSpecGroupUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuSpecValueResponse;
import org.muybaby.shopserver.product.dto.AdminSpuSpecValueUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AdminProductSpecRenameTest {

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private ProductReadMapper productReadMapper;

    @Test
    void structuredSpecRenameMayReuseAnotherSkusPreviousDisplayText() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "Spec Rename Category", "", null, 0, "ENABLED"
        ));
        AdminSpuSpecGroupUpsertRequest originalGroup = new AdminSpuSpecGroupUpsertRequest(
                null,
                "rename-color",
                "颜色",
                true,
                0,
                List.of(
                        new AdminSpuSpecValueUpsertRequest(null, "rename-red", "红色", "", null, 0),
                        new AdminSpuSpecValueUpsertRequest(null, "rename-blue", "蓝色", "", null, 1)
                )
        );
        Long spuId = adminProductService.createSpu(productRequest(
                categoryId,
                "Spec Rename SPU",
                List.of(
                        skuRequest(null, "SPEC-RENAME-RED", "rename-red", 0, true),
                        skuRequest(null, "SPEC-RENAME-BLUE", "rename-blue", 1, false)
                ),
                originalGroup
        ));
        var created = productReadMapper.adminSpuDetail(spuId);
        AdminSpuSpecGroupResponse createdGroup = created.specGroups().getFirst();
        Map<String, AdminSpuSpecValueResponse> valuesByKey = createdGroup.values().stream()
                .collect(Collectors.toMap(AdminSpuSpecValueResponse::valueKey, Function.identity()));
        Map<String, Long> skuIdsByCombination = created.skus().stream()
                .collect(Collectors.toMap(sku -> sku.combinationKey(), sku -> sku.id()));

        AdminSpuSpecGroupUpsertRequest renamedGroup = new AdminSpuSpecGroupUpsertRequest(
                createdGroup.id(),
                createdGroup.groupKey(),
                createdGroup.name(),
                createdGroup.imageEnabled(),
                createdGroup.sortOrder(),
                List.of(
                        new AdminSpuSpecValueUpsertRequest(
                                valuesByKey.get("rename-red").id(), "rename-red", "蓝色", "", null, 0
                        ),
                        new AdminSpuSpecValueUpsertRequest(
                                valuesByKey.get("rename-blue").id(), "rename-blue", "绿色", "", null, 1
                        )
                )
        );
        adminProductService.updateSpu(spuId, productRequest(
                categoryId,
                "Spec Rename SPU",
                List.of(
                        skuRequest(skuIdsByCombination.get("rename-red"), "SPEC-RENAME-RED", "rename-red", 0, true),
                        skuRequest(skuIdsByCombination.get("rename-blue"), "SPEC-RENAME-BLUE", "rename-blue", 1, false)
                ),
                renamedGroup
        ));

        var updated = productReadMapper.adminSpuDetail(spuId);
        assertThat(updated.skus()).extracting("id")
                .containsExactlyInAnyOrderElementsOf(skuIdsByCombination.values());
        assertThat(updated.skus()).extracting("specText")
                .containsExactly("蓝色", "绿色");
        assertThat(updated.skus()).extracting("combinationKey")
                .containsExactly("rename-red", "rename-blue");
    }

    @Test
    void structuredSpecKeysMustBeUniqueIgnoringCase() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "Spec Key Case Category", "", null, 0, "ENABLED"
        ));
        AdminSpuSpecGroupUpsertRequest group = new AdminSpuSpecGroupUpsertRequest(
                null,
                "case-color",
                "颜色",
                true,
                0,
                List.of(
                        new AdminSpuSpecValueUpsertRequest(null, "Case-Red", "红色", "", null, 0),
                        new AdminSpuSpecValueUpsertRequest(null, "case-red", "蓝色", "", null, 1)
                )
        );

        assertThatThrownBy(() -> adminProductService.createSpu(productRequest(
                categoryId,
                "Spec Key Case SPU",
                List.of(
                        skuRequest(null, "SPEC-KEY-CASE-RED", "Case-Red", 0, true),
                        skuRequest(null, "SPEC-KEY-CASE-BLUE", "case-red", 1, false)
                ),
                group
        )))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private AdminSpuUpsertRequest productRequest(
            Long categoryId,
            String title,
            List<AdminSkuUpsertRequest> skus,
            AdminSpuSpecGroupUpsertRequest group
    ) {
        return new AdminSpuUpsertRequest(
                categoryId, title, "", "https://example.test/spec-rename-main.jpg", null,
                "", null, "MULTI", 1L, 0L, "", "", 0, List.of(),
                skus, List.of(group), "", "NEUTRAL", List.of(), false, false, true
        );
    }

    private AdminSkuUpsertRequest skuRequest(
            Long skuId,
            String skuCode,
            String valueKey,
            int sortOrder,
            boolean defaultSelected
    ) {
        return new AdminSkuUpsertRequest(
                skuId, skuCode, null, null, 1990L + sortOrder * 100L, 0L, 1, null,
                null, null, "", null, "ENABLED", sortOrder, defaultSelected, null,
                List.of(valueKey), false
        );
    }
}
