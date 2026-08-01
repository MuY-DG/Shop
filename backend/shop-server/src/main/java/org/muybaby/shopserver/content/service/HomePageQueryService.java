package org.muybaby.shopserver.content.service;

import org.muybaby.shopserver.content.HomeProductSection;
import org.muybaby.shopserver.content.dto.AppHomeCategoryResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductBadgeResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductFeatureResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductPriceResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductSectionResponse;
import org.muybaby.shopserver.content.dto.AppHomeResponse;
import org.muybaby.shopserver.content.dto.AppHomeWholesaleSummaryResponse;
import org.muybaby.shopserver.product.dto.AppProductParameterOptionValueResponse;
import org.muybaby.shopserver.product.dto.AppProductParameterValueResponse;
import org.muybaby.shopserver.product.service.ProductParameterService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HomePageQueryService {

    private final JdbcClient jdbcClient;
    private final HomeBannerService homeBannerService;
    private final ProductParameterService productParameterService;

    public HomePageQueryService(
            JdbcClient jdbcClient,
            HomeBannerService homeBannerService,
            ProductParameterService productParameterService
    ) {
        this.jdbcClient = jdbcClient;
        this.homeBannerService = homeBannerService;
        this.productParameterService = productParameterService;
    }

    public HomePageLoad load() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        List<HomeProductRow> hotProducts = productRows(HomeProductSection.HOT);
        List<HomeProductRow> recommendedProducts = productRows(HomeProductSection.RECOMMENDED);
        List<Long> spuIds = java.util.stream.Stream.concat(hotProducts.stream(), recommendedProducts.stream())
                .map(HomeProductRow::spuId)
                .distinct()
                .toList();
        Map<Long, List<AppProductParameterValueResponse>> cardValues =
                productParameterService.displayValuesBySpuIds(spuIds, true);
        return new HomePageLoad(
                new AppHomeResponse(
                        3,
                        homeBannerService.appBanners(),
                        categories(),
                        List.of(
                                section(HomeProductSection.HOT, "FEATURED", hotProducts, cardValues),
                                section(HomeProductSection.RECOMMENDED, "COMPACT", recommendedProducts, cardValues)
                        )
                ),
                nextBannerTransition(now)
        );
    }

    private List<AppHomeCategoryResponse> categories() {
        return jdbcClient.sql("""
                        select i.id, i.category_id, c.name, i.image_url
                        from home_category_item i
                        join product_category c on c.id = i.category_id
                        where i.status = 'ENABLED'
                          and c.status = 'ENABLED'
                        order by i.sort_order asc, i.id desc
                        """)
                .query((rs, rowNum) -> {
                    Long categoryId = rs.getLong("category_id");
                    return new AppHomeCategoryResponse(
                            rs.getLong("id"),
                            categoryId,
                            rs.getString("name"),
                            rs.getString("image_url"),
                            "/pages/product/list/list?categoryId=" + categoryId
                    );
                })
                .list();
    }

    private List<HomeProductRow> productRows(HomeProductSection section) {
        return jdbcClient.sql("""
                        select i.id, i.spu_id, s.title, s.subtitle, i.image_url, s.main_image,
                               s.display_badge_text, s.display_badge_tone, s.virtual_sales,
                               pricing.min_price_cent, pricing.max_price_cent, pricing.original_price_cent,
                               pricing.wholesale_available,
                               coalesce(sales.actual_sales, 0) as actual_sales
                        from home_product_item i
                        join product_spu s on s.id = i.spu_id
                        join product_category c on c.id = s.category_id
                        left join (
                            select k.spu_id,
                                   min(k.price_cent) as min_price_cent,
                                   max(k.price_cent) as max_price_cent,
                                   max(case when k.price_rank = 1 then k.original_price_cent end) as original_price_cent,
                                   max(case when t.id is null then 0 else 1 end) as wholesale_available
                            from (
                                select sku.id, sku.spu_id, sku.price_cent, sku.original_price_cent,
                                       row_number() over (
                                           partition by sku.spu_id
                                           order by sku.price_cent asc, sku.id asc
                                       ) as price_rank
                                from product_sku sku
                                where sku.status = 'ENABLED' and sku.deleted_at is null
                            ) k
                            left join product_sku_wholesale_tier t on t.sku_id = k.id
                            group by k.spu_id
                        ) pricing on pricing.spu_id = s.id
                        left join (
                            select oi.spu_id, sum(oi.quantity) as actual_sales
                            from order_item oi
                            join shop_order o on o.id = oi.order_id
                            where o.paid_at is not null
                            group by oi.spu_id
                        ) sales on sales.spu_id = s.id
                        where i.section_type = :sectionType
                          and i.status = 'ENABLED'
                          and s.status = 'ON_SALE'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and c.status = 'ENABLED'
                        order by i.sort_order asc, i.id desc
                        """)
                .param("sectionType", section.name())
                .query((rs, rowNum) -> {
                    Long spuId = rs.getLong("spu_id");
                    String overrideImage = rs.getString("image_url");
                    String imageUrl = StringUtils.hasText(overrideImage)
                            ? overrideImage
                            : rs.getString("main_image");
                    return new HomeProductRow(
                            rs.getLong("id"),
                            spuId,
                            rs.getString("title"),
                            rs.getString("subtitle"),
                            imageUrl,
                            rs.getObject("min_price_cent", Long.class),
                            rs.getObject("max_price_cent", Long.class),
                            rs.getObject("original_price_cent", Long.class),
                            rs.getString("display_badge_text"),
                            rs.getString("display_badge_tone"),
                            rs.getInt("wholesale_available") == 1,
                            rs.getLong("actual_sales") + rs.getLong("virtual_sales")
                    );
                })
                .list();
    }

    private AppHomeProductSectionResponse section(
            HomeProductSection section,
            String presentation,
            List<HomeProductRow> rows,
            Map<Long, List<AppProductParameterValueResponse>> cardValues
    ) {
        return new AppHomeProductSectionResponse(
                section.name(),
                presentation,
                rows.stream().map(row -> product(row, cardValues.getOrDefault(row.spuId(), List.of()))).toList()
        );
    }

    private AppHomeProductResponse product(
            HomeProductRow row,
            List<AppProductParameterValueResponse> parameterValues
    ) {
        Map<String, List<AppHomeProductFeatureResponse>> features = new LinkedHashMap<>();
        features.put("HIGHLIGHT", new ArrayList<>());
        features.put("META", new ArrayList<>());
        for (AppProductParameterValueResponse value : parameterValues) {
            List<AppHomeProductFeatureResponse> target = features.get(value.cardRole());
            if (target == null) {
                continue;
            }
            int limit = "HIGHLIGHT".equals(value.cardRole()) ? 1 : 2;
            if (target.size() >= limit) {
                continue;
            }
            target.add(new AppHomeProductFeatureResponse(
                    value.parameterCode(),
                    value.parameterName(),
                    value.displayText(),
                    value.cardRenderer(),
                    displayLevel(value.selectedOptions())
            ));
        }
        AppHomeProductBadgeResponse badge = StringUtils.hasText(row.displayBadgeText())
                ? new AppHomeProductBadgeResponse(row.displayBadgeText(), "MANUAL", row.displayBadgeTone())
                : null;
        AppHomeWholesaleSummaryResponse wholesaleSummary = Boolean.TRUE.equals(row.wholesaleAvailable())
                ? new AppHomeWholesaleSummaryResponse(true, "支持批量价")
                : null;
        return new AppHomeProductResponse(
                row.placementId(),
                row.spuId(),
                row.title(),
                row.subtitle(),
                row.imageUrl(),
                new AppHomeProductPriceResponse(
                        row.minPriceCent(),
                        row.maxPriceCent(),
                        row.originalPriceCent()
                ),
                badge,
                List.copyOf(features.get("HIGHLIGHT")),
                List.copyOf(features.get("META")),
                wholesaleSummary,
                row.displaySales(),
                null,
                "/pages/product/detail/detail?id=" + row.spuId()
        );
    }

    private Integer displayLevel(List<AppProductParameterOptionValueResponse> options) {
        return options == null ? null : options.stream()
                .map(AppProductParameterOptionValueResponse::displayLevel)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private LocalDateTime nextBannerTransition(LocalDateTime now) {
        List<BannerSchedule> schedules = jdbcClient.sql("""
                        select start_at, end_at
                        from home_banner
                        where status = 'ENABLED'
                          and ((start_at is not null and start_at > :now)
                            or (end_at is not null and end_at > :now))
                        """)
                .param("now", now)
                .query((rs, rowNum) -> new BannerSchedule(
                        rs.getObject("start_at", LocalDateTime.class),
                        rs.getObject("end_at", LocalDateTime.class)
                ))
                .list();
        List<LocalDateTime> transitions = new ArrayList<>();
        for (BannerSchedule schedule : schedules) {
            if (schedule.startAt() != null && schedule.startAt().isAfter(now)) {
                transitions.add(schedule.startAt());
            }
            if (schedule.endAt() != null && schedule.endAt().isAfter(now)) {
                transitions.add(schedule.endAt());
            }
        }
        return transitions.stream().min(Comparator.naturalOrder()).orElse(null);
    }

    public record HomePageLoad(AppHomeResponse response, LocalDateTime nextTransitionAt) {
    }

    private record BannerSchedule(LocalDateTime startAt, LocalDateTime endAt) {
    }

    private record HomeProductRow(
            Long placementId,
            Long spuId,
            String title,
            String subtitle,
            String imageUrl,
            Long minPriceCent,
            Long maxPriceCent,
            Long originalPriceCent,
            String displayBadgeText,
            String displayBadgeTone,
            Boolean wholesaleAvailable,
            Long displaySales
    ) {
    }
}
