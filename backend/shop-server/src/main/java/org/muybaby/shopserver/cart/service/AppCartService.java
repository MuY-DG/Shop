package org.muybaby.shopserver.cart.service;

import org.muybaby.shopserver.analytics.AnalyticsEventService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.cart.dto.AddCartItemRequest;
import org.muybaby.shopserver.cart.dto.CartItemResponse;
import org.muybaby.shopserver.cart.dto.CartListResponse;
import org.muybaby.shopserver.cart.dto.UpdateCartQuantityRequest;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AppCartService {

    private static final Logger log = LoggerFactory.getLogger(AppCartService.class);
    private static final int MAX_QUANTITY = 999;
    private static final String CATEGORY_ENABLED = "ENABLED";

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final AnalyticsEventService analyticsEventService;

    public AppCartService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            AnalyticsEventService analyticsEventService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.analyticsEventService = analyticsEventService;
    }

    public CartListResponse list(AuthenticatedPrincipal principal) {
        Long userId = requireAppUser(principal);
        List<CartItemResponse> items = findCartItems(userId);
        int totalQuantity = items.stream()
                .mapToInt(CartItemResponse::quantity)
                .sum();
        long totalAmountCent = items.stream()
                .filter(CartItemResponse::available)
                .mapToLong(CartItemResponse::lineAmountCent)
                .sum();
        int unavailableCount = (int) items.stream()
                .filter(item -> !item.available())
                .count();
        return new CartListResponse(items, totalQuantity, totalAmountCent, unavailableCount);
    }

    @Transactional
    public CartItemResponse add(AuthenticatedPrincipal principal, AddCartItemRequest request) {
        Long userId = requireAppUser(principal);
        int requestQuantity = requireQuantity(request.quantity());
        SellableSkuRow sku = lockSellableSku(request.skuId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_UNAVAILABLE));
        Optional<CartQuantityRow> existingItem = findCartItemBySkuForUpdate(userId, request.skuId());
        int targetQuantity = requireQuantity(existingItem
                .map(item -> item.quantity() + requestQuantity)
                .orElse(requestQuantity));
        requireSellable(sku, targetQuantity);

        Long cartItemId = existingItem
                .map(item -> {
                    updateQuantityById(item.id(), targetQuantity);
                    return item.id();
                })
                .orElseGet(() -> insertOrMergeCartItem(userId, request.skuId(), requestQuantity, sku));
        CartItemResponse response = findCartItem(userId, cartItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
        recordCartAddAfterCommit(principal, request, sku.spuId(), requestQuantity);
        return response;
    }

    private void recordCartAddAfterCommit(
            AuthenticatedPrincipal principal,
            AddCartItemRequest request,
            Long spuId,
            int requestQuantity
    ) {
        Runnable recorder = () -> {
            try {
                analyticsEventService.recordCartAdd(
                        principal,
                        request.analyticsVisitorId(),
                        request.analyticsSessionId(),
                        request.analyticsEntryScene(),
                        spuId,
                        request.skuId(),
                        requestQuantity);
            } catch (RuntimeException ex) {
                log.warn("Failed to record cart add analytics for SKU {}", request.skuId(), ex);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recorder.run();
                }
            });
            return;
        }
        recorder.run();
    }

    @Transactional
    public CartItemResponse updateQuantity(AuthenticatedPrincipal principal, Long cartItemId, UpdateCartQuantityRequest request) {
        Long userId = requireAppUser(principal);
        int targetQuantity = requireQuantity(request.quantity());
        CartQuantityRow preview = findCartItemById(userId, cartItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
        SellableSkuRow sku = lockSellableSku(preview.skuId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_UNAVAILABLE));
        CartQuantityRow item = findCartItemByIdForUpdate(userId, cartItemId)
                .filter(lockedItem -> lockedItem.skuId().equals(preview.skuId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
        requireSellable(sku, targetQuantity);
        updateQuantityById(cartItemId, targetQuantity);
        return findCartItem(userId, cartItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, Long cartItemId) {
        Long userId = requireAppUser(principal);
        int deletedRows = jdbcClient.sql("""
                        DELETE FROM cart_item
                        WHERE id = :cartItemId
                          AND user_id = :userId
                        """)
                .param("cartItemId", cartItemId)
                .param("userId", userId)
                .update();
        if (deletedRows != 1) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    @Transactional
    public void clear(AuthenticatedPrincipal principal) {
        Long userId = requireAppUser(principal);
        jdbcClient.sql("""
                        DELETE FROM cart_item
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .update();
    }

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private int requireQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return quantity;
    }

    private void requireSellable(SellableSkuRow sku, int quantity) {
        if (!SkuStatus.ENABLED.name().equals(sku.skuStatus())) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
        if (!ProductStatus.ON_SALE.name().equals(sku.spuStatus()) || !CATEGORY_ENABLED.equals(sku.categoryStatus())) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        if (sku.stockAvailable() < quantity) {
            throw new BusinessException(ErrorCode.STOCK_SHORTAGE);
        }
    }

    private Optional<SellableSkuRow> lockSellableSku(Long skuId) {
        Optional<Long> spuId = jdbcClient.sql("select spu_id from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Long.class)
                .optional();
        if (spuId.isEmpty()) {
            return Optional.empty();
        }
        Optional<LockedProductRow> product = jdbcClient.sql("""
                        select category_id, status as spu_status
                        from product_spu
                        where id = :spuId
                          and deleted_at is null
                          and purged_at is null
                        for update
                        """)
                .param("spuId", spuId.get())
                .query((rs, rowNum) -> new LockedProductRow(
                        rs.getLong("category_id"),
                        rs.getString("spu_status")
                ))
                .optional();
        if (product.isEmpty()) {
            return Optional.empty();
        }
        LockedProductRow productRow = product.get();
        Optional<String> categoryStatus = jdbcClient.sql("""
                        select status
                        from product_category
                        where id = :categoryId
                        for update
                        """)
                .param("categoryId", productRow.categoryId())
                .query(String.class)
                .optional();
        if (categoryStatus.isEmpty()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                        select id as sku_id, stock_available, status as sku_status
                        from product_sku
                        where id = :skuId
                          and spu_id = :spuId
                          and deleted_at is null
                        for update
                        """)
                .param("skuId", skuId)
                .param("spuId", spuId.get())
                .query((rs, rowNum) -> new SellableSkuRow(
                        rs.getLong("sku_id"),
                        spuId.get(),
                        rs.getInt("stock_available"),
                        rs.getString("sku_status"),
                        productRow.spuStatus(),
                        categoryStatus.get()
                ))
                .optional();
    }

    private Optional<CartQuantityRow> findCartItemBySkuForUpdate(Long userId, Long skuId) {
        return jdbcClient.sql("""
                        SELECT id, sku_id, quantity
                        FROM cart_item
                        WHERE user_id = :userId
                          AND sku_id = :skuId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .param("skuId", skuId)
                .query(this::mapCartQuantity)
                .optional();
    }

    private Optional<CartQuantityRow> findCartItemByIdForUpdate(Long userId, Long cartItemId) {
        return jdbcClient.sql("""
                        SELECT id, sku_id, quantity
                        FROM cart_item
                        WHERE user_id = :userId
                          AND id = :cartItemId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .param("cartItemId", cartItemId)
                .query(this::mapCartQuantity)
                .optional();
    }

    private Optional<CartQuantityRow> findCartItemById(Long userId, Long cartItemId) {
        return jdbcClient.sql("""
                        select id, sku_id, quantity
                        from cart_item
                        where user_id = :userId
                          and id = :cartItemId
                        """)
                .param("userId", userId)
                .param("cartItemId", cartItemId)
                .query(this::mapCartQuantity)
                .optional();
    }

    private Long insertCartItem(Long userId, Long skuId, Integer quantity) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO cart_item (user_id, sku_id, quantity)
                        VALUES (:userId, :skuId, :quantity)
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("skuId", skuId)
                        .addValue("quantity", quantity),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private Long insertOrMergeCartItem(Long userId, Long skuId, Integer requestQuantity, SellableSkuRow sku) {
        try {
            return insertCartItem(userId, skuId, requestQuantity);
        } catch (DuplicateKeyException ex) {
            CartQuantityRow existingItem = findCartItemBySkuForUpdate(userId, skuId)
                    .orElseThrow(() -> ex);
            int targetQuantity = requireQuantity(existingItem.quantity() + requestQuantity);
            requireSellable(sku, targetQuantity);
            updateQuantityById(existingItem.id(), targetQuantity);
            return existingItem.id();
        }
    }

    private void updateQuantityById(Long cartItemId, Integer quantity) {
        int updatedRows = jdbcClient.sql("""
                        UPDATE cart_item
                        SET quantity = :quantity,
                            updated_at = :updatedAt
                        WHERE id = :cartItemId
                        """)
                .param("quantity", quantity)
                .param("updatedAt", LocalDateTime.now())
                .param("cartItemId", cartItemId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    private List<CartItemResponse> findCartItems(Long userId) {
        return jdbcClient.sql(cartItemSelectSql() + """
                        WHERE ci.user_id = :userId
                        ORDER BY ci.updated_at DESC, ci.id DESC
                        """)
                .param("userId", userId)
                .query(this::mapCartItem)
                .list();
    }

    private Optional<CartItemResponse> findCartItem(Long userId, Long cartItemId) {
        return jdbcClient.sql(cartItemSelectSql() + """
                        WHERE ci.user_id = :userId
                          AND ci.id = :cartItemId
                        """)
                .param("userId", userId)
                .param("cartItemId", cartItemId)
                .query(this::mapCartItem)
                .optional();
    }

    private String cartItemSelectSql() {
        return """
                SELECT ci.id AS cart_item_id,
                       ci.sku_id,
                       ci.quantity,
                       ci.created_at,
                       ci.updated_at,
                       k.spu_id,
                       k.spec_text,
                       COALESCE(applied_tier.unit_price_cent, k.price_cent) AS price_cent,
                       k.price_cent AS retail_price_cent,
                       k.original_price_cent,
                       applied_tier.min_quantity AS wholesale_tier_min_quantity,
                       next_tier.min_quantity AS next_wholesale_tier_min_quantity,
                       next_tier.unit_price_cent AS next_wholesale_tier_price_cent,
                       k.stock_available,
                       k.image AS sku_image,
                       k.status AS sku_status,
                       s.title AS product_title,
                       s.subtitle AS product_subtitle,
                       s.main_image,
                       s.status AS spu_status,
                       c.status AS category_status
                FROM cart_item ci
                JOIN product_sku k ON k.id = ci.sku_id
                                      AND k.deleted_at IS NULL
                JOIN product_spu s ON s.id = k.spu_id
                                      AND s.deleted_at IS NULL
                                      AND s.purged_at IS NULL
                JOIN product_category c ON c.id = s.category_id
                LEFT JOIN product_sku_wholesale_tier applied_tier
                       ON applied_tier.sku_id = k.id
                      AND applied_tier.min_quantity = (
                          SELECT MAX(candidate.min_quantity)
                          FROM product_sku_wholesale_tier candidate
                          WHERE candidate.sku_id = k.id
                            AND candidate.min_quantity <= ci.quantity
                      )
                LEFT JOIN product_sku_wholesale_tier next_tier
                       ON next_tier.sku_id = k.id
                      AND next_tier.min_quantity = (
                          SELECT MIN(candidate.min_quantity)
                          FROM product_sku_wholesale_tier candidate
                          WHERE candidate.sku_id = k.id
                            AND candidate.min_quantity > ci.quantity
                      )
                """;
    }

    private CartItemResponse mapCartItem(ResultSet rs, int rowNum) throws SQLException {
        int quantity = rs.getInt("quantity");
        Long spuId = valueOrZero(rs.getObject("spu_id", Long.class));
        Long priceCent = valueOrZero(rs.getObject("price_cent", Long.class));
        Long retailPriceCent = valueOrZero(rs.getObject("retail_price_cent", Long.class));
        Long originalPriceCent = valueOrZero(rs.getObject("original_price_cent", Long.class));
        Integer wholesaleTierMinQuantity = rs.getObject("wholesale_tier_min_quantity", Integer.class);
        Integer nextWholesaleTierMinQuantity = rs.getObject("next_wholesale_tier_min_quantity", Integer.class);
        Long nextWholesaleTierPriceCent = rs.getObject("next_wholesale_tier_price_cent", Long.class);
        Integer stockAvailable = valueOrZero(rs.getObject("stock_available", Integer.class));
        String skuStatus = rs.getString("sku_status");
        String spuStatus = rs.getString("spu_status");
        String categoryStatus = rs.getString("category_status");
        String unavailableReason = unavailableReason(skuStatus, spuStatus, categoryStatus, stockAvailable, quantity);
        String skuImage = rs.getString("sku_image");
        String mainImage = defaultString(rs.getString("main_image"));
        return new CartItemResponse(
                rs.getLong("cart_item_id"),
                rs.getLong("sku_id"),
                spuId,
                defaultString(rs.getString("product_title")),
                defaultString(rs.getString("product_subtitle")),
                mainImage,
                defaultString(skuImage),
                StringUtils.hasText(skuImage) ? skuImage : mainImage,
                defaultString(rs.getString("spec_text")),
                priceCent,
                retailPriceCent,
                originalPriceCent,
                wholesaleTierMinQuantity,
                nextWholesaleTierMinQuantity,
                nextWholesaleTierPriceCent,
                nextWholesaleTierMinQuantity == null ? null : nextWholesaleTierMinQuantity - quantity,
                quantity,
                priceCent * quantity,
                skuStatus,
                spuStatus,
                unavailableReason == null,
                unavailableReason,
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private String unavailableReason(String skuStatus, String spuStatus, String categoryStatus, int stockAvailable, int quantity) {
        if (!StringUtils.hasText(skuStatus)) {
            return "SKU_UNAVAILABLE";
        }
        if (!SkuStatus.ENABLED.name().equals(skuStatus)) {
            return "SKU_UNAVAILABLE";
        }
        if (!ProductStatus.ON_SALE.name().equals(spuStatus) || !CATEGORY_ENABLED.equals(categoryStatus)) {
            return "PRODUCT_UNAVAILABLE";
        }
        if (stockAvailable <= 0) {
            return "SOLD_OUT";
        }
        if (stockAvailable < quantity) {
            return "STOCK_SHORTAGE";
        }
        return null;
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number generatedId) {
            return generatedId.longValue();
        }
        throw new IllegalStateException("Failed to retrieve generated key");
    }

    private Long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private Integer valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private CartQuantityRow mapCartQuantity(ResultSet rs, int rowNum) throws SQLException {
        return new CartQuantityRow(
                rs.getLong("id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity")
        );
    }

    private record SellableSkuRow(
            Long skuId,
            Long spuId,
            Integer stockAvailable,
            String skuStatus,
            String spuStatus,
            String categoryStatus
    ) {
    }

    private record CartQuantityRow(
            Long id,
            Long skuId,
            Integer quantity
    ) {
    }

    private record LockedProductRow(Long categoryId, String spuStatus) {
    }
}
