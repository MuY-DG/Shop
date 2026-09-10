package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.product.ProductSaleState;
import org.muybaby.shopserver.product.dto.ProductSearchMatchResponse;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/** 商品搜索口径：按 SPU 返回结果，多词规格条件必须由同一个 SKU 满足。 */
public final class ProductSearchQuery {

    private final List<String> terms;
    private final boolean identifiers;
    private final boolean enabledSkusOnly;
    private final Map<String, Object> parameters = new LinkedHashMap<>();

    private ProductSearchQuery(String keyword, boolean identifiers, boolean enabledSkusOnly) {
        String normalized = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        this.terms = normalized.isEmpty() ? List.of()
                : Arrays.stream(normalized.split("(?U)\\s+")).distinct().toList();
        this.identifiers = identifiers;
        this.enabledSkusOnly = enabledSkusOnly;
        for (int index = 0; index < terms.size(); index++) {
            String literal = terms.get(index).replace("!", "!!").replace("%", "!%").replace("_", "!_");
            parameters.put("productSearch" + index, "%" + literal + "%");
        }
        if (identifiers && !terms.isEmpty()) {
            Long productId = null;
            try {
                long parsed = Long.parseLong(normalized);
                if (parsed > 0) productId = parsed;
            } catch (NumberFormatException ignored) {
                // 普通关键词或超出 Long 范围的数字继续按文本匹配。
            }
            parameters.put("productSearchId", productId);
        }
    }

    public static ProductSearchQuery publicCatalog(String keyword) {
        return new ProductSearchQuery(keyword, false, true);
    }

    public static ProductSearchQuery adminCatalog(String keyword) {
        return new ProductSearchQuery(keyword, true, false);
    }

    public static ProductSearchQuery adminSelection(String keyword) {
        return new ProductSearchQuery(keyword, true, true);
    }

    public Map<String, Object> parameters() {
        return parameters;
    }

    public String predicate(String spuAlias) {
        if (terms.isEmpty()) return "1 = 1";
        String spuMatch = joinTerms(index -> spuTerm(spuAlias, index), " AND ");
        String idMatch = identifiers ? " OR " + spuAlias + ".id = :productSearchId" : "";
        return "(" + spuMatch + idMatch + " OR EXISTS (SELECT 1 FROM product_sku search_sku WHERE "
                + "search_sku.spu_id = " + spuAlias + ".id AND "
                + skuPredicate(spuAlias, "search_sku") + "))";
    }

    private String skuPredicate(String spuAlias, String skuAlias) {
        String eligible = skuAlias + ".deleted_at IS NULL"
                + (enabledSkusOnly ? " AND " + skuAlias + ".status = 'ENABLED'" : "");
        String allTerms = joinTerms(index -> "(" + spuTerm(spuAlias, index) + " OR "
                + skuTerm(skuAlias, index) + ")", " AND ");
        // 仅标题命中时，不把所有 SKU 当作相关规格返回。
        String skuEvidence = joinTerms(index -> skuTerm(skuAlias, index), " OR ");
        return eligible + " AND (" + allTerms + ") AND (" + skuEvidence + ")";
    }

    private String spuTerm(String alias, int index) {
        return "(" + like(alias + ".title", index) + " OR " + like(alias + ".subtitle", index) + ")";
    }

    private String skuTerm(String alias, int index) {
        return "(" + like(alias + ".spec_text", index)
                + (identifiers ? " OR " + like(alias + ".sku_code", index) : "") + ")";
    }

    private String like(String column, int index) {
        return "LOWER(" + column + ") LIKE :productSearch" + index + " ESCAPE '!'";
    }

    private String joinTerms(java.util.function.IntFunction<String> clause, String separator) {
        return IntStream.range(0, terms.size()).mapToObj(clause)
                .collect(java.util.stream.Collectors.joining(separator));
    }

    /** 仅查询当前页，一次批量读取每个 SPU 最多三个相关规格，避免逐商品查库。 */
    public Map<Long, List<ProductSearchMatchResponse>> matches(JdbcClient jdbcClient, List<Long> spuIds) {
        if (terms.isEmpty() || spuIds.isEmpty()) return Map.of();
        Map<String, Object> bindings = new LinkedHashMap<>(parameters);
        bindings.put("searchSpuIds", spuIds);
        Map<Long, List<ProductSearchMatchResponse>> result = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT * FROM (
                            SELECT k.spu_id, k.id, k.spec_text, k.sku_code, k.status, k.stock_available,
                                   ROW_NUMBER() OVER (PARTITION BY k.spu_id ORDER BY
                                       CASE WHEN k.status = 'ENABLED' AND k.stock_available > 0 THEN 0 ELSE 1 END,
                                       k.sort_order, k.id) AS match_rank
                            FROM product_sku k
                            JOIN product_spu s ON s.id = k.spu_id
                            WHERE k.spu_id IN (:searchSpuIds) AND %s
                        ) matched
                        WHERE match_rank <= 3
                        ORDER BY spu_id, match_rank
                        """.formatted(skuPredicate("s", "k")))
                .params(bindings)
                .query((rs, rowNum) -> {
                    result.computeIfAbsent(rs.getLong("spu_id"), ignored -> new ArrayList<>())
                            .add(new ProductSearchMatchResponse(
                                    rs.getLong("id"), rs.getString("spec_text"), rs.getString("sku_code"),
                                    rs.getString("status"), rs.getInt("stock_available") > 0
                                            ? ProductSaleState.AVAILABLE : ProductSaleState.SOLD_OUT));
                    return rs.getLong("id");
                }).list();
        return result;
    }
}
