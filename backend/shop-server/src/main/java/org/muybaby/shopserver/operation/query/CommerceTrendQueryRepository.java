package org.muybaby.shopserver.operation.query;

import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.Granularity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Executes bounded, bucketed commerce trend queries in the database. Returning one row per chart
 * bucket keeps a year-long report from materializing every matching order item in the JVM.
 */
@Repository
public class CommerceTrendQueryRepository {

    private final JdbcClient jdbcClient;

    public CommerceTrendQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ProductTrendBucket> loadProductTrendBuckets(
            LocalDate startDate,
            LocalDateTime startAt,
            LocalDateTime endExclusive,
            Granularity granularity
    ) {
        String sql = """
                select bucket_ordinal,
                       coalesce(sum(quantity), 0) as sold_quantity,
                       coalesce(sum(line_amount_cent), 0) as paid_item_amount_cent
                from (
                    select %s as bucket_ordinal,
                           item.quantity,
                           item.line_amount_cent
                    from shop_order order_fact
                    join order_item item on item.order_id = order_fact.id
                    where order_fact.paid_at >= :startAt
                      and order_fact.paid_at < :endExclusive
                ) bucketed_item
                group by bucket_ordinal
                order by bucket_ordinal
                """.formatted(bucketExpression("order_fact.paid_at", granularity));
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive);
        if (granularity == Granularity.MONTH) {
            statement = statement
                    .param("startYear", startDate.getYear())
                    .param("startMonth", startDate.getMonthValue());
        }
        return statement
                .query((rs, rowNum) -> new ProductTrendBucket(
                        rs.getInt("bucket_ordinal"),
                        rs.getLong("sold_quantity"),
                        rs.getLong("paid_item_amount_cent")
                ))
                .list();
    }

    private String bucketExpression(String timestampColumn, Granularity granularity) {
        return switch (granularity) {
            case HOUR -> "timestampdiff(HOUR, :startAt, " + timestampColumn + ")";
            case DAY -> "timestampdiff(DAY, :startAt, " + timestampColumn + ")";
            case WEEK -> "floor(timestampdiff(DAY, :startAt, " + timestampColumn + ") / 7)";
            case MONTH -> "((extract(year from " + timestampColumn + ") - :startYear) * 12"
                    + " + (extract(month from " + timestampColumn + ") - :startMonth))";
            case AUTO -> throw new IllegalArgumentException(
                    "AUTO granularity must be resolved before querying");
        };
    }

    public record ProductTrendBucket(
            int bucketOrdinal,
            long soldQuantity,
            long paidItemAmountCent
    ) {
    }
}
