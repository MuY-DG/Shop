package org.muybaby.shopserver.content.service;

import org.muybaby.shopserver.content.HomeProductSection;
import org.muybaby.shopserver.content.dto.AppHomeCategoryResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductResponse;
import org.muybaby.shopserver.content.dto.AppHomeResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class HomePageQueryService {

    private final JdbcClient jdbcClient;
    private final HomeBannerService homeBannerService;

    public HomePageQueryService(JdbcClient jdbcClient, HomeBannerService homeBannerService) {
        this.jdbcClient = jdbcClient;
        this.homeBannerService = homeBannerService;
    }

    public HomePageLoad load() {
        LocalDateTime now = LocalDateTime.now();
        return new HomePageLoad(
                new AppHomeResponse(
                        homeBannerService.appBanners(),
                        categories(),
                        products(HomeProductSection.HOT),
                        products(HomeProductSection.RECOMMENDED)
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

    private List<AppHomeProductResponse> products(HomeProductSection section) {
        return jdbcClient.sql("""
                        select i.id, i.spu_id, s.title, s.subtitle, i.image_url, s.main_image,
                               min(k.price_cent) as min_price_cent, max(k.price_cent) as max_price_cent
                        from home_product_item i
                        join product_spu s on s.id = i.spu_id
                        join product_category c on c.id = s.category_id
                        left join product_sku k on k.spu_id = s.id
                          and k.status = 'ENABLED' and k.deleted_at is null
                        where i.section_type = :sectionType
                          and i.status = 'ENABLED'
                          and s.status = 'ON_SALE'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and c.status = 'ENABLED'
                        group by i.id, i.spu_id, s.title, s.subtitle, i.image_url, s.main_image,
                                 i.sort_order
                        order by i.sort_order asc, i.id desc
                        """)
                .param("sectionType", section.name())
                .query((rs, rowNum) -> {
                    Long spuId = rs.getLong("spu_id");
                    String overrideImage = rs.getString("image_url");
                    String imageUrl = StringUtils.hasText(overrideImage)
                            ? overrideImage
                            : rs.getString("main_image");
                    return new AppHomeProductResponse(
                            rs.getLong("id"),
                            spuId,
                            rs.getString("title"),
                            rs.getString("subtitle"),
                            imageUrl,
                            rs.getObject("min_price_cent", Long.class),
                            rs.getObject("max_price_cent", Long.class),
                            "/pages/product/detail/detail?id=" + spuId
                    );
                })
                .list();
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
}
