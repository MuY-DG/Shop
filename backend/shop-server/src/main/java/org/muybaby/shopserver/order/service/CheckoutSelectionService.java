package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.dto.OrderPreviewItemResponse;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.promotion.CheckoutContext;
import org.muybaby.shopserver.promotion.CheckoutItem;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CheckoutSelectionService {

    private static final String CATEGORY_ENABLED = "ENABLED";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CheckoutSelectionService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void validate(CheckoutRequest request) {
        if (request == null) {
            throw invalidRequest();
        }
        if (request.source() == CheckoutSource.CART) {
            if (request.cartItemIds().isEmpty() || request.skuId() != null || request.quantity() != null) {
                throw invalidRequest();
            }
            return;
        }
        if (!request.cartItemIds().isEmpty()
                || request.skuId() == null
                || request.quantity() == null
                || request.quantity() < 1
                || request.quantity() > 999) {
            throw invalidRequest();
        }
    }

    public CheckoutSelection preview(long userId, CheckoutRequest request) {
        validate(request);
        return request.source() == CheckoutSource.CART
                ? loadCart(userId, request, false)
                : loadDirect(userId, request, false);
    }

    public CheckoutSelection lockForSubmit(long userId, CheckoutRequest request) {
        validate(request);
        return request.source() == CheckoutSource.CART
                ? loadCart(userId, request, true)
                : loadDirect(userId, request, true);
    }

    private CheckoutSelection loadCart(long userId, CheckoutRequest request, boolean forUpdate) {
        List<CartSelectionRow> selectedRows = findOwnedCartRows(userId, request.cartItemIds(), forUpdate);
        if (selectedRows.size() != request.cartItemIds().size()) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }

        List<Long> selectedIds = selectedRows.stream().map(CartSelectionRow::cartItemId).toList();
        List<CheckoutRow> checkoutRows = forUpdate
                ? lockCartCheckoutRows(selectedRows)
                : findCheckoutRowsByCartIds(userId, selectedIds);
        if (checkoutRows.size() != selectedRows.size()) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        return buildSelection(CheckoutSource.CART, checkoutRows, selectedIds, userId);
    }

    private CheckoutSelection loadDirect(long userId, CheckoutRequest request, boolean forUpdate) {
        List<CheckoutRow> rows = forUpdate
                ? lockDirectCheckoutRow(request.skuId(), request.quantity())
                : findDirectCheckoutRow(request.skuId(), request.quantity());
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
        return buildSelection(CheckoutSource.DIRECT, rows, List.of(), userId);
    }

    private CheckoutSelection buildSelection(
            CheckoutSource source,
            List<CheckoutRow> checkoutRows,
            List<Long> selectedCartItemIds,
            long userId
    ) {
        List<OrderPreviewItemResponse> previewItems = new ArrayList<>(checkoutRows.size());
        List<CheckoutItem> checkoutItems = new ArrayList<>(checkoutRows.size());
        long productOriginalAmountCent = 0L;
        long productAmountCent = 0L;
        for (CheckoutRow row : checkoutRows) {
            validateCheckoutRow(row);
            long lineOriginalAmountCent = Math.multiplyExact(row.originalPriceCent(), row.quantity().longValue());
            long lineAmountCent = Math.multiplyExact(row.unitPriceCent(), row.quantity().longValue());
            productOriginalAmountCent = Math.addExact(productOriginalAmountCent, lineOriginalAmountCent);
            productAmountCent = Math.addExact(productAmountCent, lineAmountCent);
            previewItems.add(new OrderPreviewItemResponse(
                    row.cartItemId(),
                    row.skuId(),
                    row.spuId(),
                    row.productTitle(),
                    row.productSubtitle(),
                    row.mainImage(),
                    row.mainImageFileId(),
                    row.skuImage(),
                    row.skuImageFileId(),
                    row.displayImage(),
                    row.displayImageFileId(),
                    row.skuCode(),
                    row.specText(),
                    row.originalPriceCent(),
                    row.unitPriceCent(),
                    row.quantity(),
                    lineOriginalAmountCent,
                    lineAmountCent
            ));
            checkoutItems.add(new CheckoutItem(row.skuId(), row.spuId(), lineAmountCent, row.quantity()));
        }
        return new CheckoutSelection(
                source,
                previewItems,
                checkoutItems,
                selectedCartItemIds,
                productOriginalAmountCent,
                productAmountCent,
                new CheckoutContext(userId, checkoutItems)
        );
    }

    private List<CartSelectionRow> findOwnedCartRows(long userId, List<Long> cartItemIds, boolean forUpdate) {
        String sql = """
                select id as cart_item_id, sku_id, quantity
                from cart_item
                where user_id = :userId
                  and id in (:cartItemIds)
                order by id asc
                """ + (forUpdate ? " for update" : "");
        return jdbcTemplate.query(sql,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("cartItemIds", cartItemIds),
                mapCartSelectionRow());
    }

    private List<CheckoutRow> lockCartCheckoutRows(List<CartSelectionRow> selectedRows) {
        ProductLockState productState = lockProductState(
                selectedRows.stream().map(CartSelectionRow::skuId).toList());
        List<CheckoutRow> checkoutRows = new ArrayList<>(selectedRows.size());
        for (CartSelectionRow selectedRow : selectedRows) {
            checkoutRows.add(toCheckoutRow(
                    selectedRow.cartItemId(),
                    selectedRow.quantity(),
                    selectedRow.skuId(),
                    productState
            ));
        }
        return checkoutRows;
    }

    private List<CheckoutRow> lockDirectCheckoutRow(long skuId, int quantity) {
        ProductLockState productState = lockProductState(List.of(skuId));
        return List.of(toCheckoutRow(null, quantity, skuId, productState));
    }

    private ProductLockState lockProductState(List<Long> skuIds) {
        List<Long> normalizedSkuIds = skuIds.stream().distinct().sorted().toList();
        List<SkuParentRow> parentHints = findSkuParentRows(normalizedSkuIds);
        if (parentHints.size() != normalizedSkuIds.size()) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }

        // Global lock order: optional cart_item -> product_spu -> product_category -> product_sku.
        List<Long> spuIds = parentHints.stream().map(SkuParentRow::spuId).distinct().sorted().toList();
        List<LockedSpuRow> lockedSpus = lockSpuRows(spuIds);
        if (lockedSpus.size() != spuIds.size()) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        List<Long> categoryIds = lockedSpus.stream()
                .map(LockedSpuRow::categoryId)
                .distinct()
                .sorted()
                .toList();
        List<LockedCategoryRow> lockedCategories = lockCategoryRows(categoryIds);
        if (lockedCategories.size() != categoryIds.size()) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        List<LockedSkuRow> lockedSkus = lockSkuRows(normalizedSkuIds);
        if (lockedSkus.size() != normalizedSkuIds.size()) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }

        Map<Long, SkuParentRow> parentHintsBySku = new HashMap<>();
        parentHints.forEach(row -> parentHintsBySku.put(row.skuId(), row));
        Map<Long, LockedSpuRow> spusById = new HashMap<>();
        lockedSpus.forEach(row -> spusById.put(row.spuId(), row));
        Map<Long, LockedCategoryRow> categoriesById = new HashMap<>();
        lockedCategories.forEach(row -> categoriesById.put(row.categoryId(), row));
        Map<Long, LockedSkuRow> skusById = new HashMap<>();
        lockedSkus.forEach(row -> skusById.put(row.skuId(), row));
        for (LockedSkuRow sku : lockedSkus) {
            SkuParentRow parentHint = parentHintsBySku.get(sku.skuId());
            if (parentHint == null
                    || !parentHint.spuId().equals(sku.spuId())
                    || !spusById.containsKey(sku.spuId())) {
                throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
            }
        }
        return new ProductLockState(spusById, categoriesById, skusById);
    }

    private List<SkuParentRow> findSkuParentRows(List<Long> skuIds) {
        return jdbcTemplate.query("""
                        select id as sku_id, spu_id
                        from product_sku
                        where id in (:skuIds)
                        order by id asc
                        """,
                new MapSqlParameterSource().addValue("skuIds", skuIds),
                (rs, rowNum) -> new SkuParentRow(rs.getLong("sku_id"), rs.getLong("spu_id")));
    }

    private List<LockedSpuRow> lockSpuRows(List<Long> spuIds) {
        return jdbcTemplate.query("""
                        select id as spu_id,
                               category_id,
                               title,
                               subtitle,
                               main_image,
                               main_image_file_id,
                               status as spu_status
                        from product_spu
                        where id in (:spuIds)
                        order by id asc
                        for update
                        """,
                new MapSqlParameterSource().addValue("spuIds", spuIds),
                (rs, rowNum) -> new LockedSpuRow(
                        rs.getLong("spu_id"),
                        rs.getLong("category_id"),
                        rs.getString("title"),
                        rs.getString("subtitle"),
                        rs.getString("main_image"),
                        rs.getObject("main_image_file_id", Long.class),
                        rs.getString("spu_status")
                ));
    }

    private List<LockedCategoryRow> lockCategoryRows(List<Long> categoryIds) {
        return jdbcTemplate.query("""
                        select id as category_id, status as category_status
                        from product_category
                        where id in (:categoryIds)
                        order by id asc
                        for update
                        """,
                new MapSqlParameterSource().addValue("categoryIds", categoryIds),
                (rs, rowNum) -> new LockedCategoryRow(
                        rs.getLong("category_id"),
                        rs.getString("category_status")
                ));
    }

    private List<LockedSkuRow> lockSkuRows(List<Long> skuIds) {
        return jdbcTemplate.query("""
                        select id as sku_id,
                               spu_id,
                               image as sku_image,
                               image_file_id as sku_image_file_id,
                               sku_code,
                               spec_text,
                               original_price_cent,
                               price_cent as unit_price_cent,
                               stock_available,
                               status as sku_status
                        from product_sku
                        where id in (:skuIds)
                        order by id asc
                        for update
                        """,
                new MapSqlParameterSource().addValue("skuIds", skuIds),
                (rs, rowNum) -> new LockedSkuRow(
                        rs.getLong("sku_id"),
                        rs.getLong("spu_id"),
                        rs.getString("sku_image"),
                        rs.getObject("sku_image_file_id", Long.class),
                        rs.getString("sku_code"),
                        rs.getString("spec_text"),
                        rs.getLong("original_price_cent"),
                        rs.getLong("unit_price_cent"),
                        rs.getInt("stock_available"),
                        rs.getString("sku_status")
                ));
    }

    private CheckoutRow toCheckoutRow(
            Long cartItemId,
            int quantity,
            long skuId,
            ProductLockState productState
    ) {
        LockedSkuRow sku = productState.skusById().get(skuId);
        if (sku == null) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
        LockedSpuRow spu = productState.spusById().get(sku.spuId());
        if (spu == null) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        LockedCategoryRow category = productState.categoriesById().get(spu.categoryId());
        if (category == null) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        boolean useMainImage = sku.skuImage() == null || sku.skuImage().isEmpty();
        return new CheckoutRow(
                cartItemId,
                sku.skuId(),
                sku.spuId(),
                spu.productTitle(),
                spu.productSubtitle(),
                spu.mainImage(),
                spu.mainImageFileId(),
                sku.skuImage(),
                sku.skuImageFileId(),
                useMainImage ? spu.mainImage() : sku.skuImage(),
                useMainImage ? spu.mainImageFileId() : sku.skuImageFileId(),
                sku.skuCode(),
                sku.specText(),
                sku.originalPriceCent(),
                sku.unitPriceCent(),
                quantity,
                sku.stockAvailable(),
                sku.skuStatus(),
                spu.spuStatus(),
                category.categoryStatus()
        );
    }

    private List<CheckoutRow> findCheckoutRowsByCartIds(long userId, List<Long> cartItemIds) {
        return jdbcTemplate.query("""
                        select c.id as cart_item_id,
                               c.sku_id,
                               c.quantity,
                               k.spu_id,
                               s.title as product_title,
                               s.subtitle as product_subtitle,
                               s.main_image,
                               s.main_image_file_id,
                               k.image as sku_image,
                               k.image_file_id as sku_image_file_id,
                               case when k.image is null or k.image = '' then s.main_image else k.image end as display_image,
                               case when k.image is null or k.image = '' then s.main_image_file_id else k.image_file_id end as display_image_file_id,
                               k.sku_code,
                               k.spec_text,
                               k.original_price_cent,
                               k.price_cent as unit_price_cent,
                               k.stock_available,
                               k.status as sku_status,
                               s.status as spu_status,
                               pc.status as category_status
                        from cart_item c
                        join product_sku k on k.id = c.sku_id
                        join product_spu s on s.id = k.spu_id
                        join product_category pc on pc.id = s.category_id
                        where c.user_id = :userId
                          and c.id in (:cartItemIds)
                        order by c.id asc
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("cartItemIds", cartItemIds),
                this::mapCheckoutRow);
    }

    private List<CheckoutRow> findDirectCheckoutRow(long skuId, int quantity) {
        return jdbcTemplate.query("""
                select k.id as sku_id,
                       k.spu_id,
                       s.title as product_title,
                       s.subtitle as product_subtitle,
                       s.main_image,
                       s.main_image_file_id,
                       k.image as sku_image,
                       k.image_file_id as sku_image_file_id,
                       case when k.image is null or k.image = '' then s.main_image else k.image end as display_image,
                       case when k.image is null or k.image = '' then s.main_image_file_id else k.image_file_id end as display_image_file_id,
                       k.sku_code,
                       k.spec_text,
                       k.original_price_cent,
                       k.price_cent as unit_price_cent,
                       k.stock_available,
                       k.status as sku_status,
                       s.status as spu_status,
                       pc.status as category_status
                from product_sku k
                join product_spu s on s.id = k.spu_id
                join product_category pc on pc.id = s.category_id
                where k.id = :skuId
                """,
                new MapSqlParameterSource().addValue("skuId", skuId),
                (rs, rowNum) -> mapDirectCheckoutRow(rs, quantity));
    }

    private void validateCheckoutRow(CheckoutRow row) {
        if (!SkuStatus.ENABLED.name().equals(row.skuStatus())) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
        if (!ProductStatus.ON_SALE.name().equals(row.spuStatus()) || !CATEGORY_ENABLED.equals(row.categoryStatus())) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        if (row.stockAvailable() < row.quantity()) {
            throw new BusinessException(ErrorCode.STOCK_SHORTAGE);
        }
    }

    private CheckoutRow mapCheckoutRow(ResultSet rs, int rowNum) throws SQLException {
        return mapCheckoutRow(rs, rs.getObject("cart_item_id", Long.class), rs.getInt("quantity"));
    }

    private CheckoutRow mapDirectCheckoutRow(ResultSet rs, int quantity) throws SQLException {
        return mapCheckoutRow(rs, null, quantity);
    }

    private CheckoutRow mapCheckoutRow(ResultSet rs, Long cartItemId, int quantity) throws SQLException {
        return new CheckoutRow(
                cartItemId,
                rs.getLong("sku_id"),
                rs.getLong("spu_id"),
                rs.getString("product_title"),
                rs.getString("product_subtitle"),
                rs.getString("main_image"),
                rs.getObject("main_image_file_id", Long.class),
                rs.getString("sku_image"),
                rs.getObject("sku_image_file_id", Long.class),
                rs.getString("display_image"),
                rs.getObject("display_image_file_id", Long.class),
                rs.getString("sku_code"),
                rs.getString("spec_text"),
                rs.getLong("original_price_cent"),
                rs.getLong("unit_price_cent"),
                quantity,
                rs.getInt("stock_available"),
                rs.getString("sku_status"),
                rs.getString("spu_status"),
                rs.getString("category_status")
        );
    }

    private RowMapper<CartSelectionRow> mapCartSelectionRow() {
        return (rs, rowNum) -> new CartSelectionRow(
                rs.getLong("cart_item_id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity")
        );
    }

    private BusinessException invalidRequest() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record CartSelectionRow(Long cartItemId, Long skuId, Integer quantity) {
    }

    private record SkuParentRow(Long skuId, Long spuId) {
    }

    private record LockedSpuRow(
            Long spuId,
            Long categoryId,
            String productTitle,
            String productSubtitle,
            String mainImage,
            Long mainImageFileId,
            String spuStatus
    ) {
    }

    private record LockedCategoryRow(Long categoryId, String categoryStatus) {
    }

    private record LockedSkuRow(
            Long skuId,
            Long spuId,
            String skuImage,
            Long skuImageFileId,
            String skuCode,
            String specText,
            Long originalPriceCent,
            Long unitPriceCent,
            Integer stockAvailable,
            String skuStatus
    ) {
    }

    private record ProductLockState(
            Map<Long, LockedSpuRow> spusById,
            Map<Long, LockedCategoryRow> categoriesById,
            Map<Long, LockedSkuRow> skusById
    ) {
        private ProductLockState {
            spusById = Map.copyOf(spusById);
            categoriesById = Map.copyOf(categoriesById);
            skusById = Map.copyOf(skusById);
        }
    }

    private record CheckoutRow(
            Long cartItemId,
            Long skuId,
            Long spuId,
            String productTitle,
            String productSubtitle,
            String mainImage,
            Long mainImageFileId,
            String skuImage,
            Long skuImageFileId,
            String displayImage,
            Long displayImageFileId,
            String skuCode,
            String specText,
            Long originalPriceCent,
            Long unitPriceCent,
            Integer quantity,
            Integer stockAvailable,
            String skuStatus,
            String spuStatus,
            String categoryStatus
    ) {
    }
}
