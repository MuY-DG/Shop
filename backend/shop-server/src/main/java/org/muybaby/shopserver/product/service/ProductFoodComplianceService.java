package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.ProductComplianceType;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.dto.ProductFoodDisclosureRequest;
import org.muybaby.shopserver.product.dto.ProductFoodDisclosureResponse;
import org.muybaby.shopserver.product.dto.ProductFoodLabelAssetRequest;
import org.muybaby.shopserver.product.dto.ProductFoodLabelAssetResponse;
import org.muybaby.shopserver.product.entity.ProductSku;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ProductFoodComplianceService {

    private static final int MAX_LABEL_ASSETS = 12;
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?:待填写|待补充|占位|示例|测试数据|TODO|TBD|PLACEHOLDER)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIXED_PRODUCTION_DATE = Pattern.compile(
            "(?:(?:19|20)\\d{2}\\s*(?:年|[-/.])\\s*\\d{1,2}\\s*(?:月|[-/.])\\s*\\d{1,2}\\s*日?"
                    + "|(?:19|20)\\d{6})");
    private static final Pattern PACKAGE_MARKING = Pattern.compile(
            "(?:(?:详见|见).*(?:包装|标签|瓶盖|封口)"
                    + "|(?:包装|标签|瓶盖|封口).*(?:喷码|标注|标识|标示|所示|详见|为准))");
    private static final Pattern POSITIVE_NET_CONTENT = Pattern.compile(
            "(?<!\\d)(?:0*[1-9]\\d*(?:\\.\\d+)?|0\\.\\d*[1-9]\\d*)\\s*"
                    + "(?:kg|g|mg|l|ml|克|千克|公斤|毫升|升|枚|个|袋|包|盒|罐|瓶|支)",
            Pattern.CASE_INSENSITIVE);

    private final JdbcClient jdbcClient;
    private final StorageUsageService storageUsageService;

    public ProductFoodComplianceService(
            JdbcClient jdbcClient,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.storageUsageService = storageUsageService;
    }

    @Transactional(readOnly = true)
    public ProductFoodDisclosureResponse get(Long spuId) {
        ProductRow product = requireProduct(spuId, false);
        DisclosureRow disclosure = findDisclosure(spuId);
        return response(product.complianceType(), disclosure, findLabels(spuId));
    }

    @Transactional
    public ProductFoodDisclosureResponse update(Long spuId, ProductFoodDisclosureRequest request) {
        ProductRow product = requireProduct(spuId, true);
        if (ProductStatus.ON_SALE.name().equals(product.status()) || request == null) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        ProductComplianceType complianceType = parseComplianceType(request.complianceType());
        DisclosureRow disclosure = complianceType == ProductComplianceType.FOOD
                ? normalizeDisclosure(request)
                : DisclosureRow.empty();
        List<NormalizedLabel> labels = complianceType == ProductComplianceType.FOOD
                ? normalizeLabels(request.labelAssets())
                : List.of();

        upsertDisclosure(spuId, disclosure);
        replaceLabels(spuId, labels);
        jdbcClient.sql("""
                        update product_spu
                        set compliance_type = :complianceType,
                            updated_at = :updatedAt
                        where id = :spuId
                          and deleted_at is null
                          and purged_at is null
                        """)
                .param("complianceType", complianceType.name())
                .param("updatedAt", LocalDateTime.now(ZoneOffset.UTC))
                .param("spuId", spuId)
                .update();
        syncLabelUsages(spuId, product.title(), labels);
        return response(complianceType.name(), disclosure, labels.stream()
                .map(label -> new ProductFoodLabelAssetResponse(
                        label.fileId(), label.url(), label.sortOrder()))
                .toList());
    }

    @Transactional
    public void requirePublishable(Long spuId, List<ProductSku> enabledSkus) {
        ProductRow product = requireProduct(spuId, false);
        ProductComplianceType complianceType = parseComplianceType(product.complianceType());
        if (complianceType == ProductComplianceType.UNCLASSIFIED) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        if (complianceType == ProductComplianceType.NON_FOOD) {
            return;
        }

        requireCurrentMerchantFoodQualification();
        DisclosureRow disclosure = findDisclosure(spuId);
        if (!completeAndTruthful(disclosure)) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        List<ProductFoodLabelAssetResponse> labels = findLabels(spuId);
        if (labels.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        Set<Long> activeManagedLabelFileIds = new HashSet<>(jdbcClient.sql("""
                        select distinct asset_id
                        from storage_asset_usage
                        where owner_type = :ownerType
                          and owner_id = :spuId
                          and usage_type = :usageType
                          and status = 'ACTIVE'
                        """)
                .param("ownerType", StorageUsageOwnerType.PRODUCT_FOOD_DISCLOSURE.name())
                .param("spuId", spuId)
                .param("usageType", StorageFileUsageType.PRODUCT_FOOD_LABEL.name())
                .query(Long.class)
                .list());
        Set<Long> disclosedLabelFileIds = labels.stream()
                .map(ProductFoodLabelAssetResponse::fileId)
                .collect(Collectors.toSet());
        if (!activeManagedLabelFileIds.equals(disclosedLabelFileIds)) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        labels.stream()
                .map(ProductFoodLabelAssetResponse::fileId)
                .sorted()
                .forEach(fileId -> storageUsageService.requireActivePublicMedia(
                        fileId, StorageMediaKind.IMAGE));
        List<ProductSku> skus = enabledSkus == null ? List.of() : enabledSkus;
        if (skus.isEmpty() || skus.stream().anyMatch(sku -> !truthfulNetContent(sku.netContentText()))) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
    }

    @Transactional(readOnly = true)
    public ProductFoodDisclosureResponse publicDisclosure(Long spuId) {
        ProductFoodDisclosureResponse disclosure = get(spuId);
        return ProductComplianceType.FOOD.name().equals(disclosure.complianceType())
                ? disclosure
                : null;
    }

    @Transactional
    public void purge(Long spuId) {
        jdbcClient.sql("""
                        delete from storage_asset_usage
                        where owner_type = :ownerType
                          and owner_id = :spuId
                        """)
                .param("ownerType", StorageUsageOwnerType.PRODUCT_FOOD_DISCLOSURE.name())
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("delete from product_food_disclosure_label where spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("delete from product_food_disclosure where spu_id = :spuId")
                .param("spuId", spuId)
                .update();
    }

    private ProductRow requireProduct(Long spuId, boolean lock) {
        if (spuId == null || spuId <= 0L) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        String lockClause = lock ? " for update" : "";
        return jdbcClient.sql("""
                        select id, title, status, compliance_type
                        from product_spu
                        where id = :spuId
                          and deleted_at is null
                          and purged_at is null
                        """ + lockClause)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new ProductRow(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getString("compliance_type")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
    }

    private ProductComplianceType parseComplianceType(String value) {
        try {
            return ProductComplianceType.valueOf(value == null
                    ? ""
                    : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private DisclosureRow normalizeDisclosure(ProductFoodDisclosureRequest request) {
        return new DisclosureRow(
                clean(request.foodName()),
                clean(request.ingredients()),
                clean(request.allergenInformation()),
                clean(request.storageConditions()),
                clean(request.shelfLifeDescription()),
                clean(request.manufacturerName()),
                clean(request.manufacturerAddress()),
                clean(request.productionLicenseNumber()),
                clean(request.origin()),
                clean(request.consumerNotice()),
                clean(request.variableProductionNotice())
        );
    }

    private List<NormalizedLabel> normalizeLabels(List<ProductFoodLabelAssetRequest> requests) {
        List<ProductFoodLabelAssetRequest> source = requests == null ? List.of() : requests;
        if (source.size() > MAX_LABEL_ASSETS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<Long> fileIds = new HashSet<>();
        for (ProductFoodLabelAssetRequest request : source) {
            if (request == null || request.fileId() == null || request.fileId() <= 0L
                    || !fileIds.add(request.fileId())
                    || (request.sortOrder() != null && request.sortOrder() < 0)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }

        Map<Long, String> canonicalUrls = new HashMap<>();
        fileIds.stream().sorted().forEach(fileId -> {
            storageUsageService.requireActivePublicMedia(fileId, StorageMediaKind.IMAGE);
            String publicUrl = jdbcClient.sql("""
                            select public_url
                            from storage_asset
                            where id = :fileId
                              and status = 'ACTIVE'
                              and visibility = 'PUBLIC'
                            """)
                    .param("fileId", fileId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
            if (!StringUtils.hasText(publicUrl) || publicUrl.length() > 500) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
            canonicalUrls.put(fileId, publicUrl.trim());
        });

        List<NormalizedLabel> labels = new ArrayList<>();
        int fallbackSortOrder = 0;
        for (ProductFoodLabelAssetRequest request : source) {
            labels.add(new NormalizedLabel(
                    request.fileId(),
                    canonicalUrls.get(request.fileId()),
                    request.sortOrder() == null ? fallbackSortOrder : request.sortOrder()));
            fallbackSortOrder++;
        }
        labels.sort(Comparator.comparing(NormalizedLabel::sortOrder)
                .thenComparing(NormalizedLabel::fileId));
        return List.copyOf(labels);
    }

    private void upsertDisclosure(Long spuId, DisclosureRow disclosure) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = jdbcClient.sql("""
                        update product_food_disclosure
                        set food_name = :foodName,
                            ingredients = :ingredients,
                            allergen_information = :allergenInformation,
                            storage_conditions = :storageConditions,
                            shelf_life_description = :shelfLifeDescription,
                            manufacturer_name = :manufacturerName,
                            manufacturer_address = :manufacturerAddress,
                            production_license_number = :productionLicenseNumber,
                            origin = :origin,
                            consumer_notice = :consumerNotice,
                            variable_production_notice = :variableProductionNotice,
                            updated_at = :updatedAt
                        where spu_id = :spuId
                        """)
                .params(disclosure.parameters(spuId, now))
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            insert into product_food_disclosure (
                                spu_id, food_name, ingredients, allergen_information,
                                storage_conditions, shelf_life_description, manufacturer_name,
                                manufacturer_address, production_license_number, origin,
                                consumer_notice, variable_production_notice, created_at, updated_at
                            ) values (
                                :spuId, :foodName, :ingredients, :allergenInformation,
                                :storageConditions, :shelfLifeDescription, :manufacturerName,
                                :manufacturerAddress, :productionLicenseNumber, :origin,
                                :consumerNotice, :variableProductionNotice, :updatedAt, :updatedAt
                            )
                            """)
                    .params(disclosure.parameters(spuId, now))
                    .update();
        }
    }

    private void replaceLabels(Long spuId, List<NormalizedLabel> labels) {
        jdbcClient.sql("delete from product_food_disclosure_label where spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        for (NormalizedLabel label : labels) {
            jdbcClient.sql("""
                            insert into product_food_disclosure_label (
                                spu_id, file_id, url, sort_order
                            ) values (:spuId, :fileId, :url, :sortOrder)
                            """)
                    .param("spuId", spuId)
                    .param("fileId", label.fileId())
                    .param("url", label.url())
                    .param("sortOrder", label.sortOrder())
                    .update();
        }
    }

    private void syncLabelUsages(Long spuId, String productTitle, List<NormalizedLabel> labels) {
        List<StorageUsageService.UsageAssignment> usages = labels.stream()
                .map(label -> new StorageUsageService.UsageAssignment(
                        label.fileId(),
                        StorageFileUsageType.PRODUCT_FOOD_LABEL,
                        label.url(),
                        label.sortOrder(),
                        false))
                .toList();
        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.PRODUCT_FOOD_DISCLOSURE,
                spuId,
                productTitle + " 食品标签",
                usages);
    }

    private void requireCurrentMerchantFoodQualification() {
        MerchantQualificationRow qualification = jdbcClient.sql("""
                        select id, food_qualification_asset_id
                        from merchant_publication_revision
                        where current_publication_key = 1
                          and status = 'PUBLISHED'
                          and food_qualification_asset_id is not null
                          and food_qualification_type <> ''
                          and food_qualification_number <> ''
                          and food_qualification_valid_from is not null
                          and food_qualification_valid_from <= current_date
                          and food_qualification_valid_until is not null
                          and food_qualification_valid_until >= current_date
                        """)
                .query((rs, rowNum) -> new MerchantQualificationRow(
                        rs.getLong("id"),
                        rs.getLong("food_qualification_asset_id")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        Integer managedUsageCount = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :fileId
                          and usage_type = :usageType
                          and owner_type = :ownerType
                          and owner_id = :ownerId
                          and protected = true
                          and status = 'ACTIVE'
                        """)
                .param("fileId", qualification.fileId())
                .param("usageType", StorageFileUsageType.MERCHANT_FOOD_QUALIFICATION.name())
                .param("ownerType", StorageUsageOwnerType.MERCHANT_PUBLICATION.name())
                .param("ownerId", qualification.publicationId())
                .query(Integer.class)
                .single();
        if (managedUsageCount == null || managedUsageCount != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        storageUsageService.requireActivePublicMedia(qualification.fileId(), StorageMediaKind.IMAGE);
    }

    private DisclosureRow findDisclosure(Long spuId) {
        return jdbcClient.sql("""
                        select food_name, ingredients, allergen_information, storage_conditions,
                               shelf_life_description, manufacturer_name, manufacturer_address,
                               production_license_number, origin, consumer_notice,
                               variable_production_notice
                        from product_food_disclosure
                        where spu_id = :spuId
                        """)
                .param("spuId", spuId)
                .query(this::mapDisclosure)
                .optional()
                .orElseGet(DisclosureRow::empty);
    }

    private List<ProductFoodLabelAssetResponse> findLabels(Long spuId) {
        return jdbcClient.sql("""
                        select file_id, url, sort_order
                        from product_food_disclosure_label
                        where spu_id = :spuId
                        order by sort_order, id
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new ProductFoodLabelAssetResponse(
                        rs.getLong("file_id"),
                        rs.getString("url"),
                        rs.getInt("sort_order")))
                .list();
    }

    private ProductFoodDisclosureResponse response(
            String complianceType,
            DisclosureRow disclosure,
            List<ProductFoodLabelAssetResponse> labels
    ) {
        return new ProductFoodDisclosureResponse(
                complianceType,
                disclosure.foodName(),
                disclosure.ingredients(),
                disclosure.allergenInformation(),
                disclosure.storageConditions(),
                disclosure.shelfLifeDescription(),
                disclosure.manufacturerName(),
                disclosure.manufacturerAddress(),
                disclosure.productionLicenseNumber(),
                disclosure.origin(),
                disclosure.consumerNotice(),
                disclosure.variableProductionNotice(),
                List.copyOf(labels));
    }

    private boolean completeAndTruthful(DisclosureRow disclosure) {
        return truthfulText(disclosure.foodName())
                && truthfulText(disclosure.ingredients())
                && optionalTruthfulText(disclosure.allergenInformation())
                && truthfulText(disclosure.storageConditions())
                && truthfulText(disclosure.shelfLifeDescription())
                && truthfulText(disclosure.manufacturerName())
                && truthfulText(disclosure.manufacturerAddress())
                && truthfulText(disclosure.productionLicenseNumber())
                && truthfulText(disclosure.origin())
                && optionalTruthfulText(disclosure.consumerNotice())
                && truthfulVariableNotice(disclosure.variableProductionNotice());
    }

    private boolean truthfulVariableNotice(String value) {
        return truthfulText(value)
                && !FIXED_PRODUCTION_DATE.matcher(value).find()
                && PACKAGE_MARKING.matcher(value).find();
    }

    private boolean truthfulText(String value) {
        return StringUtils.hasText(value) && !PLACEHOLDER.matcher(value).find();
    }

    private boolean optionalTruthfulText(String value) {
        return !StringUtils.hasText(value) || truthfulText(value);
    }

    private boolean truthfulNetContent(String value) {
        return truthfulText(value) && POSITIVE_NET_CONTENT.matcher(value).find();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private DisclosureRow mapDisclosure(ResultSet rs, int rowNum) throws SQLException {
        return new DisclosureRow(
                rs.getString("food_name"),
                rs.getString("ingredients"),
                rs.getString("allergen_information"),
                rs.getString("storage_conditions"),
                rs.getString("shelf_life_description"),
                rs.getString("manufacturer_name"),
                rs.getString("manufacturer_address"),
                rs.getString("production_license_number"),
                rs.getString("origin"),
                rs.getString("consumer_notice"),
                rs.getString("variable_production_notice"));
    }

    private record ProductRow(Long id, String title, String status, String complianceType) {
    }

    private record MerchantQualificationRow(Long publicationId, Long fileId) {
    }

    private record NormalizedLabel(Long fileId, String url, Integer sortOrder) {
    }

    private record DisclosureRow(
            String foodName,
            String ingredients,
            String allergenInformation,
            String storageConditions,
            String shelfLifeDescription,
            String manufacturerName,
            String manufacturerAddress,
            String productionLicenseNumber,
            String origin,
            String consumerNotice,
            String variableProductionNotice
    ) {
        private static DisclosureRow empty() {
            return new DisclosureRow("", "", "", "", "", "", "", "", "", "", "");
        }

        private Map<String, Object> parameters(Long spuId, LocalDateTime updatedAt) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("spuId", spuId);
            parameters.put("foodName", foodName);
            parameters.put("ingredients", ingredients);
            parameters.put("allergenInformation", allergenInformation);
            parameters.put("storageConditions", storageConditions);
            parameters.put("shelfLifeDescription", shelfLifeDescription);
            parameters.put("manufacturerName", manufacturerName);
            parameters.put("manufacturerAddress", manufacturerAddress);
            parameters.put("productionLicenseNumber", productionLicenseNumber);
            parameters.put("origin", origin);
            parameters.put("consumerNotice", consumerNotice);
            parameters.put("variableProductionNotice", variableProductionNotice);
            parameters.put("updatedAt", updatedAt);
            return parameters;
        }
    }
}
