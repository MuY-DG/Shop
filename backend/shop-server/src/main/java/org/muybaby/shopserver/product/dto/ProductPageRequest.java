package org.muybaby.shopserver.product.dto;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public record ProductPageRequest(
        Long categoryId,
        String keyword,
        Long current,
        Long size,
        String sort,
        String parameterFilters
) {
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_PARAMETER_FILTERS = 10;

    public ProductPageRequest(Long categoryId, String keyword, Long current, Long size) {
        this(categoryId, keyword, current, size, null, null);
    }

    public ProductPageRequest(Long categoryId, String keyword, Long current, Long size, String sort) {
        this(categoryId, keyword, current, size, sort, null);
    }

    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 10 : Math.min(size, 50);
    }

    public String orderByClause() {
        String normalized = sort == null ? "" : sort.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SALES_DESC" ->
                    "display_sales DESC, s.sort_order ASC, s.id DESC";
            case "PRICE_ASC" ->
                    "(min(k.price_cent) IS NULL) ASC, min(k.price_cent) ASC, s.sort_order ASC, s.id DESC";
            case "PRICE_DESC" ->
                    "(min(k.price_cent) IS NULL) ASC, min(k.price_cent) DESC, s.sort_order ASC, s.id DESC";
            default ->
                    "s.sort_order ASC, s.id DESC";
        };
    }

    public Map<String, String> normalizedParameterFilters() {
        if (parameterFilters == null || parameterFilters.isBlank()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String item : parameterFilters.split(",")) {
            if (normalized.size() >= MAX_PARAMETER_FILTERS) {
                break;
            }
            String[] parts = item.split(":", -1);
            if (parts.length != 2) {
                continue;
            }
            String parameterCode = parts[0].trim().toUpperCase(Locale.ROOT);
            String optionCode = parts[1].trim().toUpperCase(Locale.ROOT);
            if (CODE_PATTERN.matcher(parameterCode).matches()
                    && CODE_PATTERN.matcher(optionCode).matches()) {
                normalized.put(parameterCode, optionCode);
            }
        }
        return Map.copyOf(normalized);
    }
}
