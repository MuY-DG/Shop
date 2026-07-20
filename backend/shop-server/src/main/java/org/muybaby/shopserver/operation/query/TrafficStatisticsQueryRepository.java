package org.muybaby.shopserver.operation.query;

import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.Granularity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class TrafficStatisticsQueryRepository {

    private static final String HOME_PAGE_PATH = "/pages/home/home";

    private final JdbcClient jdbcClient;

    public TrafficStatisticsQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<TrafficTrendBucket> loadTrendBuckets(
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime startAt,
            LocalDateTime endExclusive,
            Granularity granularity
    ) {
        String bucketExpression = bucketExpression(granularity);
        String sql = """
                select bucket_ordinal,
                       coalesce(sum(case when event_type = 'PAGE_VIEW' then 1 else 0 end), 0)
                           as page_view_count,
                       count(distinct visitor_id) as visitor_count,
                       count(distinct concat(visitor_id, ':', session_id)) as session_count
                from (
                    select %s as bucket_ordinal,
                           event_type,
                           visitor_id,
                           session_id
                    from analytics_event
                    where business_date >= :startDate
                      and business_date <= :endDate
                      and occurred_at >= :startAt
                      and occurred_at < :endExclusive
                ) bucketed_event
                group by bucket_ordinal
                order by bucket_ordinal
                """.formatted(bucketExpression);
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("startDate", startDate)
                .param("endDate", endDate)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive);
        if (granularity == Granularity.MONTH) {
            statement = statement
                    .param("startYear", startDate.getYear())
                    .param("startMonth", startDate.getMonthValue());
        }
        return statement
                .query((rs, rowNum) -> new TrafficTrendBucket(
                        rs.getInt("bucket_ordinal"),
                        rs.getLong("page_view_count"),
                        rs.getLong("visitor_count"),
                        rs.getLong("session_count")
                ))
                .list();
    }

    public TrafficFunnelCounts loadFunnelCounts(
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime startAt,
            LocalDateTime endExclusive
    ) {
        return jdbcClient.sql("""
                        with ranged_event as (
                            select id, visitor_id, event_type, page_path, occurred_at
                            from analytics_event
                            where business_date >= :startDate
                              and business_date <= :endDate
                              and event_type in ('PAGE_VIEW', 'PRODUCT_VIEW', 'CART_ADD', 'CHECKOUT_START')
                        ), home_candidate as (
                            select visitor_id,
                                   occurred_at,
                                   id,
                                   row_number() over (
                                       partition by visitor_id
                                       order by occurred_at, id
                                   ) as stage_order
                            from ranged_event
                            where event_type = 'PAGE_VIEW'
                              and page_path = :homePagePath
                        ), home_stage as (
                            select visitor_id, occurred_at as home_at, id as home_id
                            from home_candidate
                            where stage_order = 1
                        ), product_candidate as (
                            select home.visitor_id,
                                   event.occurred_at,
                                   event.id,
                                   row_number() over (
                                       partition by home.visitor_id
                                       order by event.occurred_at, event.id
                                   ) as stage_order
                            from home_stage home
                            join ranged_event event
                              on event.visitor_id = home.visitor_id
                             and event.event_type = 'PRODUCT_VIEW'
                             and (event.occurred_at > home.home_at
                                  or (event.occurred_at = home.home_at and event.id > home.home_id))
                        ), product_stage as (
                            select visitor_id, occurred_at as product_at, id as product_id
                            from product_candidate
                            where stage_order = 1
                        ), cart_candidate as (
                            select product.visitor_id,
                                   event.occurred_at,
                                   event.id,
                                   row_number() over (
                                       partition by product.visitor_id
                                       order by event.occurred_at, event.id
                                   ) as stage_order
                            from product_stage product
                            join ranged_event event
                              on event.visitor_id = product.visitor_id
                             and event.event_type = 'CART_ADD'
                             and (event.occurred_at > product.product_at
                                  or (event.occurred_at = product.product_at and event.id > product.product_id))
                        ), cart_stage as (
                            select visitor_id, occurred_at as cart_at, id as cart_id
                            from cart_candidate
                            where stage_order = 1
                        ), checkout_candidate as (
                            select cart.visitor_id,
                                   event.occurred_at,
                                   event.id,
                                   row_number() over (
                                       partition by cart.visitor_id
                                       order by event.occurred_at, event.id
                                   ) as stage_order
                            from cart_stage cart
                            join ranged_event event
                              on event.visitor_id = cart.visitor_id
                             and event.event_type = 'CHECKOUT_START'
                             and (event.occurred_at > cart.cart_at
                                  or (event.occurred_at = cart.cart_at and event.id > cart.cart_id))
                        ), checkout_stage as (
                            select visitor_id, occurred_at as checkout_at
                            from checkout_candidate
                            where stage_order = 1
                        ), submitted_stage as (
                            select distinct checkout.visitor_id
                            from checkout_stage checkout
                            join shop_order order_fact
                              on order_fact.analytics_visitor_id = checkout.visitor_id
                             and order_fact.created_at >= :startAt
                             and order_fact.created_at < :endExclusive
                             and order_fact.created_at >= checkout.checkout_at
                        ), paid_stage as (
                            select distinct checkout.visitor_id
                            from checkout_stage checkout
                            join shop_order order_fact
                              on order_fact.analytics_visitor_id = checkout.visitor_id
                             and order_fact.created_at >= :startAt
                             and order_fact.created_at < :endExclusive
                             and order_fact.created_at >= checkout.checkout_at
                             and order_fact.paid_at >= :startAt
                             and order_fact.paid_at < :endExclusive
                             and order_fact.paid_at >= order_fact.created_at
                        )
                        select count(*) as home_count,
                               coalesce(sum(case when product.visitor_id is not null then 1 else 0 end), 0)
                                   as product_count,
                               coalesce(sum(case when cart.visitor_id is not null then 1 else 0 end), 0)
                                   as cart_count,
                               coalesce(sum(case when checkout.visitor_id is not null then 1 else 0 end), 0)
                                   as checkout_count,
                               coalesce(sum(case when submitted.visitor_id is not null then 1 else 0 end), 0)
                                   as submitted_count,
                               coalesce(sum(case when paid.visitor_id is not null then 1 else 0 end), 0)
                                   as paid_count
                        from home_stage home
                        left join product_stage product on product.visitor_id = home.visitor_id
                        left join cart_stage cart on cart.visitor_id = home.visitor_id
                        left join checkout_stage checkout on checkout.visitor_id = home.visitor_id
                        left join submitted_stage submitted on submitted.visitor_id = home.visitor_id
                        left join paid_stage paid on paid.visitor_id = home.visitor_id
                        """)
                .param("startDate", startDate)
                .param("endDate", endDate)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .param("homePagePath", HOME_PAGE_PATH)
                .query((rs, rowNum) -> new TrafficFunnelCounts(
                        rs.getLong("home_count"),
                        rs.getLong("product_count"),
                        rs.getLong("cart_count"),
                        rs.getLong("checkout_count"),
                        rs.getLong("submitted_count"),
                        rs.getLong("paid_count")
                ))
                .single();
    }

    private String bucketExpression(Granularity granularity) {
        return switch (granularity) {
            case HOUR -> "timestampdiff(HOUR, :startAt, occurred_at)";
            case DAY -> "timestampdiff(DAY, :startAt, occurred_at)";
            case WEEK -> "floor(timestampdiff(DAY, :startAt, occurred_at) / 7)";
            case MONTH -> "((extract(year from occurred_at) - :startYear) * 12"
                    + " + (extract(month from occurred_at) - :startMonth))";
            case AUTO -> throw new IllegalArgumentException("AUTO granularity must be resolved before querying");
        };
    }

    public record TrafficTrendBucket(
            int bucketOrdinal,
            long pageViewCount,
            long visitorCount,
            long sessionCount
    ) {
    }

    public record TrafficFunnelCounts(
            long homeCount,
            long productCount,
            long cartCount,
            long checkoutCount,
            long submittedCount,
            long paidCount
    ) {
    }
}
