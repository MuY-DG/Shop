package org.muybaby.shopserver.operation.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.time.TimePolicy;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.AgentLoadItem;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.AlertItem;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.Availability;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.BreakdownItem;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.DataBlock;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.DateRange;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.FunnelStage;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.Granularity;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.MarketingStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.MetricUnit;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.MetricValue;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.OverviewReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ProductStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.RankingItem;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.RecentOrderItem;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ReportMeta;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ReportQuery;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.RetentionCohortItem;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.RetentionWindow;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ServiceStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TodoItem;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TradeStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TrafficStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TrendPoint;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TrendSeries;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.UserStatisticsReport;
import org.muybaby.shopserver.operation.query.CommerceTrendQueryRepository;
import org.muybaby.shopserver.operation.query.CommerceTrendQueryRepository.ProductTrendBucket;
import org.muybaby.shopserver.operation.query.TrafficStatisticsQueryRepository;
import org.muybaby.shopserver.operation.query.TrafficStatisticsQueryRepository.TrafficFunnelCounts;
import org.muybaby.shopserver.operation.query.TrafficStatisticsQueryRepository.TrafficTrendBucket;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

@Service
public class OperationsStatisticsService {

    private static final ZoneId BUSINESS_ZONE = TimePolicy.BUSINESS_ZONE;
    private static final String BUSINESS_TIMEZONE = BUSINESS_ZONE.getId();
    private static final long MAX_RANGE_DAYS = 366;
    private static final int RANKING_LIMIT = 10;

    private final JdbcClient jdbcClient;
    private final CommerceTrendQueryRepository commerceTrendQueryRepository;
    private final TrafficStatisticsQueryRepository trafficStatisticsQueryRepository;

    public OperationsStatisticsService(
            JdbcClient jdbcClient,
            CommerceTrendQueryRepository commerceTrendQueryRepository,
            TrafficStatisticsQueryRepository trafficStatisticsQueryRepository
    ) {
        this.jdbcClient = jdbcClient;
        this.commerceTrendQueryRepository = commerceTrendQueryRepository;
        this.trafficStatisticsQueryRepository = trafficStatisticsQueryRepository;
    }

    public OverviewReport overview(ReportQuery query) {
        ReportContext context = normalize(query);
        TradeAggregate currentTrade = tradeAggregate(context.startAt(), context.endExclusive());
        TradeAggregate previousTrade = tradeAggregate(context.comparisonStartAt(), context.comparisonEndExclusive());
        long currentNewUsers = countUsersCreated(context.startAt(), context.endExclusive());
        long previousNewUsers = countUsersCreated(context.comparisonStartAt(), context.comparisonEndExclusive());

        Map<String, MetricValue> trade = new LinkedHashMap<>();
        trade.put("paidAmountCent", metric(currentTrade.paidAmountCent(), previousTrade.paidAmountCent(), MetricUnit.CENT));
        trade.put("refundAmountCent", metric(currentTrade.refundAmountCent(), previousTrade.refundAmountCent(), MetricUnit.CENT));
        trade.put("netReceiptAmountCent", metric(currentTrade.netReceiptAmountCent(), previousTrade.netReceiptAmountCent(), MetricUnit.CENT));
        trade.put("paidOrderCount", metric(currentTrade.paidOrderCount(), previousTrade.paidOrderCount(), MetricUnit.COUNT));
        trade.put("paidBuyerCount", metric(currentTrade.paidBuyerCount(), previousTrade.paidBuyerCount(), MetricUnit.COUNT));
        trade.put("customerUnitPriceCent", nullableApplicableMetric(
                divideAmount(currentTrade.paidAmountCent(), currentTrade.paidBuyerCount()),
                divideAmount(previousTrade.paidAmountCent(), previousTrade.paidBuyerCount()),
                MetricUnit.CENT));

        Map<String, MetricValue> users = new LinkedHashMap<>();
        users.put("newUserCount", metric(currentNewUsers, previousNewUsers, MetricUnit.COUNT));
        users.put("paidBuyerCount", metric(currentTrade.paidBuyerCount(), previousTrade.paidBuyerCount(), MetricUnit.COUNT));

        return new OverviewReport(
                context.meta(null),
                trade,
                users,
                available(todoItems()),
                available(overviewTrend(context)),
                available(productRanking(context, 5)),
                available(recentOrders(context))
        );
    }

    public TradeStatisticsReport tradeStatistics(ReportQuery query) {
        ReportContext context = normalize(query);
        TradeAggregate current = tradeAggregate(context.startAt(), context.endExclusive());
        TradeAggregate previous = tradeAggregate(context.comparisonStartAt(), context.comparisonEndExclusive());

        Map<String, MetricValue> summary = tradeSummary(context, current, previous);
        return new TradeStatisticsReport(
                context.meta(paymentAttemptCollectionStartedAt()),
                summary,
                available(tradeTrend(context)),
                available(breakdown("shop_order", "status", "created_at", context)),
                available(breakdown("payment_order", "status", "created_at", context)),
                available(breakdown("refund_order", "status", "requested_at", context)),
                available(breakdown("shop_order", "source", "created_at", context)),
                available(hourlyPaidOrders(context))
        );
    }

    public ProductStatisticsReport productStatistics(ReportQuery query) {
        ReportContext context = normalize(query);
        ProductAggregate current = productAggregate(context.startAt(), context.endExclusive());
        ProductAggregate previous = productAggregate(context.comparisonStartAt(), context.comparisonEndExclusive());
        CatalogAggregate catalog = catalogAggregate();

        Map<String, MetricValue> summary = new LinkedHashMap<>();
        summary.put("activeSpuCount", metric(catalog.activeSpuCount(), MetricUnit.COUNT));
        summary.put("onSaleSpuCount", metric(catalog.onSaleSpuCount(), MetricUnit.COUNT));
        summary.put("enabledSkuCount", metric(catalog.enabledSkuCount(), MetricUnit.COUNT));
        summary.put("totalAvailableStock", metric(catalog.totalAvailableStock(), MetricUnit.COUNT));
        summary.put("outOfStockSkuCount", metric(catalog.outOfStockSkuCount(), MetricUnit.COUNT));
        summary.put("lowStockSkuCount", metric(catalog.lowStockSkuCount(), MetricUnit.COUNT));
        summary.put("soldQuantity", metric(current.soldQuantity(), previous.soldQuantity(), MetricUnit.COUNT));
        summary.put("refundedQuantity", metric(current.refundedQuantity(), previous.refundedQuantity(), MetricUnit.COUNT));
        summary.put("netSoldQuantity", metric(current.netSoldQuantity(), previous.netSoldQuantity(), MetricUnit.COUNT));
        summary.put("paidItemAmountCent", metric(current.paidItemAmountCent(), previous.paidItemAmountCent(), MetricUnit.CENT));
        summary.put("paidOrderCount", metric(current.paidOrderCount(), previous.paidOrderCount(), MetricUnit.COUNT));
        summary.put("paidBuyerCount", metric(current.paidBuyerCount(), previous.paidBuyerCount(), MetricUnit.COUNT));
        summary.put("refundRate", ratioMetric(current.refundedQuantity(), current.soldQuantity(),
                previous.refundedQuantity(), previous.soldQuantity()));
        summary.put("costCoverageRate", ratioMetric(current.coveredAmountCent(), current.coverageBaseAmountCent(),
                previous.coveredAmountCent(), previous.coverageBaseAmountCent()));
        summary.put("grossProfitAmountCent", nullableMetric(current.grossProfitAmountCent(), previous.grossProfitAmountCent(), MetricUnit.CENT));
        summary.put("virtualSalesExcluded", metric(1L, MetricUnit.COUNT));

        return new ProductStatisticsReport(
                context.meta(orderCostCollectionStartedAt()),
                summary,
                available(productTrend(context)),
                available(productRanking(context, RANKING_LIMIT)),
                available(categoryRanking(context)),
                available(stockAlerts())
        );
    }

    public UserStatisticsReport userStatistics(ReportQuery query) {
        ReportContext context = normalize(query);
        UserAggregate current = userAggregate(
                context.startDate(), context.endDate().plusDays(1), context.startAt(), context.endExclusive());
        UserAggregate previous = userAggregate(
                context.comparisonStartDate(), context.comparisonEndDate().plusDays(1),
                context.comparisonStartAt(), context.comparisonEndExclusive());
        long totalUsers = countUsersBefore(context.endExclusive());
        long previousTotalUsers = countUsersBefore(context.comparisonEndExclusive());
        LocalDateTime activityStartedAt = activityCollectionStartedAt();
        boolean currentActivityAvailable = collectionAvailable(activityStartedAt, context.endExclusive());
        boolean comparisonActivityAvailable = collectionAvailable(
                activityStartedAt, context.comparisonEndExclusive());
        LocalDateTime phoneAuthorizationStartedAt = phoneAuthorizationCollectionStartedAt();
        boolean currentPhoneAuthorizationAvailable = collectionAvailable(
                phoneAuthorizationStartedAt, context.endExclusive());
        boolean comparisonPhoneAuthorizationAvailable = collectionAvailable(
                phoneAuthorizationStartedAt, context.comparisonEndExclusive());

        Map<String, MetricValue> summary = new LinkedHashMap<>();
        summary.put("totalUserCount", metric(totalUsers, previousTotalUsers, MetricUnit.COUNT));
        summary.put("newUserCount", metric(current.newUserCount(), previous.newUserCount(), MetricUnit.COUNT));
        summary.put("activeUserCount", currentActivityAvailable
                ? metric(current.activeUserCount(), comparisonActivityAvailable ? previous.activeUserCount() : null,
                        MetricUnit.COUNT)
                : unavailableMetric(MetricUnit.COUNT));
        summary.put("paidBuyerCount", metric(current.paidBuyerCount(), previous.paidBuyerCount(), MetricUnit.COUNT));
        summary.put("newPayingBuyerCount", metric(current.newPayingBuyerCount(), previous.newPayingBuyerCount(), MetricUnit.COUNT));
        summary.put("firstPurchaseUserCount", metric(current.newPayingBuyerCount(), previous.newPayingBuyerCount(), MetricUnit.COUNT));
        summary.put("repeatBuyerCount", metric(current.repeatBuyerCount(), previous.repeatBuyerCount(), MetricUnit.COUNT));
        summary.put("repeatBuyerRate", ratioMetric(current.repeatBuyerCount(), current.paidBuyerCount(),
                previous.repeatBuyerCount(), previous.paidBuyerCount()));
        summary.put("phoneAuthorizedUserCount", currentPhoneAuthorizationAvailable
                ? metric(current.phoneAuthorizedUserCount(),
                        comparisonPhoneAuthorizationAvailable ? previous.phoneAuthorizedUserCount() : null,
                        MetricUnit.COUNT)
                : unavailableMetric(MetricUnit.COUNT));
        summary.put("phoneAuthorizationRate", currentPhoneAuthorizationAvailable
                ? ratioMetric(current.phoneAuthorizedUserCount(), totalUsers,
                        comparisonPhoneAuthorizationAvailable ? previous.phoneAuthorizedUserCount() : null,
                        comparisonPhoneAuthorizationAvailable ? previousTotalUsers : null)
                : unavailableMetric(MetricUnit.BASIS_POINT));

        DataBlock<List<TrendSeries>> trend = !currentActivityAvailable
                ? notCollected("USER_ACTIVITY_NOT_COLLECTED", "用户活跃从服务端采集启用后开始统计", userTrend(context))
                : available(userTrend(context));
        return new UserStatisticsReport(
                context.meta(activityStartedAt),
                summary,
                trend,
                available(purchaseSegments(context)),
                available(topCustomers(context)),
                retentionCohorts(context, activityStartedAt)
        );
    }

    public TrafficStatisticsReport trafficStatistics(ReportQuery query) {
        ReportContext context = normalize(query);
        LocalDateTime collectionStartedAt = analyticsCollectionStartedAt();
        TrafficAggregate traffic = trafficAggregate(context);
        boolean collected = collectionAvailable(collectionStartedAt, context.endExclusive());

        Map<String, MetricValue> summary = new LinkedHashMap<>();
        summary.put("pageViewCount", collected
                ? metric(traffic.pageViewCount(), MetricUnit.COUNT)
                : unavailableMetric(MetricUnit.COUNT));
        summary.put("visitorCount", collected
                ? metric(traffic.visitorCount(), MetricUnit.COUNT)
                : unavailableMetric(MetricUnit.COUNT));
        summary.put("sessionCount", collected
                ? metric(traffic.sessionCount(), MetricUnit.COUNT)
                : unavailableMetric(MetricUnit.COUNT));
        summary.put("loginActiveUserCount", collected
                ? metric(traffic.loginActiveUserCount(), MetricUnit.COUNT)
                : unavailableMetric(MetricUnit.COUNT));

        return new TrafficStatisticsReport(
                context.meta(collectionStartedAt),
                summary,
                collected ? available(trafficTrend(context)) : notCollectedList("TRAFFIC_NOT_COLLECTED"),
                collected ? available(analyticsBreakdown(context, "entry_scene", "APP_LAUNCH")) : notCollectedList("TRAFFIC_NOT_COLLECTED"),
                collected ? available(analyticsRanking(context, "page_path", "PAGE_VIEW")) : notCollectedList("TRAFFIC_NOT_COLLECTED"),
                collected ? available(analyticsRanking(context, "search_keyword", "SEARCH")) : notCollectedList("TRAFFIC_NOT_COLLECTED"),
                collected ? available(trafficFunnel(context)) : notCollectedList("TRAFFIC_NOT_COLLECTED")
        );
    }

    public MarketingStatisticsReport marketingStatistics(ReportQuery query) {
        ReportContext context = normalize(query);
        MarketingAggregate current = marketingAggregate(context.startAt(), context.endExclusive());
        MarketingAggregate previous = marketingAggregate(context.comparisonStartAt(), context.comparisonEndExclusive());

        Map<String, MetricValue> summary = new LinkedHashMap<>();
        summary.put("issuedCouponCount", metric(current.issuedCouponCount(), previous.issuedCouponCount(), MetricUnit.COUNT));
        summary.put("usedCouponCount", metric(current.usedCouponCount(), previous.usedCouponCount(), MetricUnit.COUNT));
        summary.put("expiredCouponCount", metric(current.expiredCouponCount(), previous.expiredCouponCount(), MetricUnit.COUNT));
        summary.put("couponUsageRate", ratioMetric(current.usedCouponCount(), current.issuedCouponCount(),
                previous.usedCouponCount(), previous.issuedCouponCount()));
        summary.put("couponDiscountCent", metric(current.couponDiscountCent(), previous.couponDiscountCent(), MetricUnit.CENT));
        summary.put("couponPaidAmountCent", metric(current.couponPaidAmountCent(), previous.couponPaidAmountCent(), MetricUnit.CENT));

        return new MarketingStatisticsReport(
                context.meta(null),
                summary,
                available(marketingTrend(context)),
                available(breakdown("coupon_claim_record", "issue_source", "claimed_at", context)),
                available(couponTemplateRanking(context))
        );
    }

    public ServiceStatisticsReport serviceStatistics(ReportQuery query) {
        ReportContext context = normalize(query);
        ServiceAggregate current = serviceAggregate(context.startAt(), context.endExclusive());
        ServiceAggregate previous = serviceAggregate(context.comparisonStartAt(), context.comparisonEndExclusive());
        ServiceConversationAggregate currentConversations = serviceConversationAggregate(
                context.startAt(), context.endExclusive());
        ServiceConversationAggregate previousConversations = serviceConversationAggregate(
                context.comparisonStartAt(), context.comparisonEndExclusive());
        CurrentServiceQueue queue = currentServiceQueue();

        Map<String, MetricValue> summary = new LinkedHashMap<>();
        summary.put("toShipOrderCount", metric(queue.toShipOrderCount(), MetricUnit.COUNT));
        summary.put("overdueToShipOrderCount", metric(queue.overdueToShipOrderCount(), MetricUnit.COUNT));
        summary.put("averageShippingSeconds", nullableApplicableMetric(
                current.averageShippingSeconds(), previous.averageShippingSeconds(), MetricUnit.SECOND));
        summary.put("averageCompletionSeconds", nullableApplicableMetric(
                current.averageCompletionSeconds(), previous.averageCompletionSeconds(), MetricUnit.SECOND));
        summary.put("afterSaleApplicationCount", metric(current.afterSaleApplicationCount(), previous.afterSaleApplicationCount(), MetricUnit.COUNT));
        summary.put("approvedAfterSaleCount", metric(current.approvedAfterSaleCount(), previous.approvedAfterSaleCount(), MetricUnit.COUNT));
        summary.put("rejectedAfterSaleCount", metric(current.rejectedAfterSaleCount(), previous.rejectedAfterSaleCount(), MetricUnit.COUNT));
        summary.put("afterSaleApprovalRate", ratioMetric(
                current.approvedAfterSaleCount(),
                current.approvedAfterSaleCount() + current.rejectedAfterSaleCount(),
                previous.approvedAfterSaleCount(),
                previous.approvedAfterSaleCount() + previous.rejectedAfterSaleCount()));
        summary.put("averageAfterSaleReviewSeconds", nullableApplicableMetric(
                current.averageAfterSaleReviewSeconds(), previous.averageAfterSaleReviewSeconds(), MetricUnit.SECOND));
        summary.put("successfulRefundCount", metric(current.successfulRefundCount(), previous.successfulRefundCount(), MetricUnit.COUNT));
        summary.put("failedRefundCount", metric(current.failedRefundCount(), previous.failedRefundCount(), MetricUnit.COUNT));
        summary.put("refundSuccessRate", ratioMetric(
                current.successfulRefundCount(), current.successfulRefundCount() + current.failedRefundCount(),
                previous.successfulRefundCount(), previous.successfulRefundCount() + previous.failedRefundCount()));
        summary.put("averageRefundProcessingSeconds", nullableApplicableMetric(
                current.averageRefundProcessingSeconds(), previous.averageRefundProcessingSeconds(), MetricUnit.SECOND));
        summary.put("successfulRefundAmountCent", metric(current.successfulRefundAmountCent(), previous.successfulRefundAmountCent(), MetricUnit.CENT));
        summary.put("waitingConversationCount", metric(queue.waitingConversationCount(), MetricUnit.COUNT));
        summary.put("activeConversationCount", metric(queue.activeConversationCount(), MetricUnit.COUNT));
        summary.put("adminUnreadMessageCount", metric(queue.adminUnreadMessageCount(), MetricUnit.COUNT));
        summary.put("conversationCount", metric(
                currentConversations.conversationCount(), previousConversations.conversationCount(), MetricUnit.COUNT));
        summary.put("averageFirstResponseSeconds", nullableApplicableMetric(
                currentConversations.averageFirstResponseSeconds(),
                previousConversations.averageFirstResponseSeconds(), MetricUnit.SECOND));
        summary.put("averageResolutionSeconds", nullableApplicableMetric(
                currentConversations.averageResolutionSeconds(),
                previousConversations.averageResolutionSeconds(), MetricUnit.SECOND));
        summary.put("conversationCloseRate", ratioMetric(
                currentConversations.closedConversationCount(), currentConversations.conversationCount(),
                previousConversations.closedConversationCount(), previousConversations.conversationCount()));
        summary.put("conversationTransferRate", ratioMetric(
                currentConversations.transferredConversationCount(), currentConversations.conversationCount(),
                previousConversations.transferredConversationCount(), previousConversations.conversationCount()));

        return new ServiceStatisticsReport(
                context.meta(null),
                summary,
                available(serviceTrend(context)),
                available(shippingCompanyBreakdown(context)),
                available(shippingStatusBreakdown(context)),
                available(breakdown("after_sale_request", "status", "created_at", context)),
                available(refundReasonBreakdown(context)),
                available(agentLoads(context))
        );
    }

    private ReportContext normalize(ReportQuery query) {
        LocalDate requestedStart = query == null ? null : query.startDate();
        LocalDate requestedEnd = query == null ? null : query.endDate();
        if ((requestedStart == null) != (requestedEnd == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        LocalDate endDate = requestedEnd == null ? LocalDate.now(BUSINESS_ZONE) : requestedEnd;
        LocalDate startDate = requestedStart == null ? endDate.minusDays(6) : requestedStart;
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long rangeDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (rangeDays > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Granularity requestedGranularity = query == null ? null : query.granularity();
        Granularity granularity = requestedGranularity == null || requestedGranularity == Granularity.AUTO
                ? automaticGranularity(rangeDays)
                : requestedGranularity;
        LocalDate comparisonEndDate = startDate.minusDays(1);
        LocalDate comparisonStartDate = startDate.minusDays(rangeDays);
        return new ReportContext(
                startDate,
                endDate,
                comparisonStartDate,
                comparisonEndDate,
                granularity,
                TimePolicy.businessDayStartUtc(startDate),
                TimePolicy.businessDayStartUtc(endDate.plusDays(1)),
                TimePolicy.businessDayStartUtc(comparisonStartDate),
                TimePolicy.businessDayStartUtc(comparisonEndDate.plusDays(1))
        );
    }

    private Granularity automaticGranularity(long rangeDays) {
        if (rangeDays == 1) {
            return Granularity.HOUR;
        }
        if (rangeDays <= 31) {
            return Granularity.DAY;
        }
        if (rangeDays <= 180) {
            return Granularity.WEEK;
        }
        return Granularity.MONTH;
    }

    private TradeAggregate tradeAggregate(LocalDateTime startAt, LocalDateTime endExclusive) {
        PaidAggregate paid = jdbcClient.sql("""
                        select count(*) as paid_order_count,
                               count(distinct user_id) as paid_buyer_count,
                               coalesce(sum(paid_amount_cent), 0) as paid_amount_cent,
                               coalesce(sum(coupon_discount_cent), 0) as coupon_discount_cent,
                               coalesce(sum(freight_cent), 0) as freight_cent
                        from shop_order
                        where paid_at >= :startAt
                          and paid_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new PaidAggregate(
                        rs.getLong("paid_order_count"),
                        rs.getLong("paid_buyer_count"),
                        rs.getLong("paid_amount_cent"),
                        rs.getLong("coupon_discount_cent"),
                        rs.getLong("freight_cent")
                ))
                .single();
        RefundAggregate refund = jdbcClient.sql("""
                        select count(*) as refund_count,
                               coalesce(sum(refund_amount_cent), 0) as refund_amount_cent
                        from refund_order
                        where status = 'SUCCESS'
                          and success_at >= :startAt
                          and success_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new RefundAggregate(
                        rs.getLong("refund_count"),
                        rs.getLong("refund_amount_cent")
                ))
                .single();
        CreatedAggregate created = jdbcClient.sql("""
                        select count(*) as created_order_count,
                               coalesce(sum(case when paid_at is not null then 1 else 0 end), 0) as eventually_paid_count
                        from shop_order
                        where created_at >= :startAt
                          and created_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new CreatedAggregate(
                        rs.getLong("created_order_count"),
                        rs.getLong("eventually_paid_count")
                ))
                .single();
        return new TradeAggregate(
                created.createdOrderCount(),
                created.eventuallyPaidCount(),
                paid.paidOrderCount(),
                paid.paidBuyerCount(),
                paid.paidAmountCent(),
                refund.refundCount(),
                refund.refundAmountCent(),
                paid.paidAmountCent() - refund.refundAmountCent(),
                paid.couponDiscountCent(),
                paid.freightCent()
        );
    }

    private ProductAggregate productAggregate(LocalDateTime startAt, LocalDateTime endExclusive) {
        ProductPaidAggregate paid = jdbcClient.sql("""
                        select coalesce(sum(oi.quantity), 0) as sold_quantity,
                               coalesce(sum(oi.line_amount_cent), 0) as paid_item_amount_cent,
                               count(distinct oi.order_id) as paid_order_count,
                               count(distinct o.user_id) as paid_buyer_count,
                               coalesce(sum(oi.line_amount_cent), 0) as coverage_base_amount_cent,
                               coalesce(sum(case when oi.line_cost_cent is not null
                                                then oi.line_amount_cent else 0 end), 0) as covered_amount_cent,
                               sum(case when oi.line_cost_cent is not null
                                             and oi.paid_amount_allocated_cent is not null
                                        then oi.paid_amount_allocated_cent - oi.line_cost_cent else null end) as gross_profit_amount_cent
                        from order_item oi
                        join shop_order o on o.id = oi.order_id
                        where o.paid_at >= :startAt
                          and o.paid_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new ProductPaidAggregate(
                        rs.getLong("sold_quantity"),
                        rs.getLong("paid_item_amount_cent"),
                        rs.getLong("paid_order_count"),
                        rs.getLong("paid_buyer_count"),
                        rs.getLong("coverage_base_amount_cent"),
                        rs.getLong("covered_amount_cent"),
                        nullableLong(rs, "gross_profit_amount_cent")
                ))
                .single();
        long refundedQuantity = jdbcClient.sql("""
                        select coalesce(sum(oi.quantity), 0)
                        from refund_order r
                        join order_item oi on oi.order_id = r.order_id
                        where r.status = 'SUCCESS'
                          and r.success_at >= :startAt
                          and r.success_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();
        return new ProductAggregate(
                paid.soldQuantity(),
                refundedQuantity,
                paid.soldQuantity() - refundedQuantity,
                paid.paidItemAmountCent(),
                paid.paidOrderCount(),
                paid.paidBuyerCount(),
                paid.coverageBaseAmountCent(),
                paid.coveredAmountCent(),
                paid.grossProfitAmountCent()
        );
    }

    private CatalogAggregate catalogAggregate() {
        return jdbcClient.sql("""
                        select count(distinct s.id) as active_spu_count,
                               count(distinct case when s.status = 'ON_SALE' then s.id end) as on_sale_spu_count,
                               count(distinct case when k.status = 'ENABLED' and k.deleted_at is null then k.id end) as enabled_sku_count,
                               coalesce(sum(case when k.status = 'ENABLED' and k.deleted_at is null
                                                 then k.stock_available else 0 end), 0) as total_available_stock,
                               count(distinct case when s.status = 'ON_SALE'
                                                   and k.status = 'ENABLED' and k.deleted_at is null
                                                   and k.stock_available <= 0 then k.id end) as out_of_stock_sku_count,
                               count(distinct case when s.status = 'ON_SALE'
                                                   and k.status = 'ENABLED' and k.deleted_at is null
                                                   and k.stock_available > 0
                                                   and k.stock_available <= k.low_stock_threshold then k.id end) as low_stock_sku_count
                        from product_spu s
                        left join product_sku k on k.spu_id = s.id
                        where s.deleted_at is null
                          and s.purged_at is null
                        """)
                .query((rs, rowNum) -> new CatalogAggregate(
                        rs.getLong("active_spu_count"),
                        rs.getLong("on_sale_spu_count"),
                        rs.getLong("enabled_sku_count"),
                        rs.getLong("total_available_stock"),
                        rs.getLong("out_of_stock_sku_count"),
                        rs.getLong("low_stock_sku_count")
                ))
                .single();
    }

    private UserAggregate userAggregate(
            LocalDate startDate,
            LocalDate endDateExclusive,
            LocalDateTime startAt,
            LocalDateTime endExclusive
    ) {
        long newUsers = countUsersCreated(startAt, endExclusive);
        long activeUsers = jdbcClient.sql("""
                        select count(distinct user_id)
                        from app_user_daily_activity
                        where activity_date >= :startDate
                          and activity_date < :endDate
                        """)
                .param("startDate", startDate)
                .param("endDate", endDateExclusive)
                .query(Long.class)
                .single();
        long paidBuyers = jdbcClient.sql("""
                        select count(distinct user_id)
                        from shop_order
                        where paid_at >= :startAt
                          and paid_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();
        long newPayingBuyers = jdbcClient.sql("""
                        select count(*)
                        from (
                            select user_id, min(paid_at) as first_paid_at
                            from shop_order
                            where paid_at is not null
                            group by user_id
                        ) first_purchase
                        where first_paid_at >= :startAt
                          and first_paid_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();
        long repeatBuyers = jdbcClient.sql("""
                        select count(*)
                        from (
                            select user_id
                            from shop_order
                            where paid_at is not null
                              and paid_at < :endExclusive
                            group by user_id
                            having count(*) >= 2
                               and sum(case when paid_at >= :startAt then 1 else 0 end) >= 1
                        ) repeat_purchase
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();
        long phoneAuthorizedUsers = jdbcClient.sql("""
                        select count(*)
                        from app_user
                        where created_at < :endExclusive
                          and phone_authorized = true
                          and phone_authorized_at < :endExclusive
                        """)
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();
        return new UserAggregate(
                newUsers,
                activeUsers,
                paidBuyers,
                newPayingBuyers,
                repeatBuyers,
                phoneAuthorizedUsers
        );
    }

    private TrafficAggregate trafficAggregate(ReportContext context) {
        return jdbcClient.sql("""
                        select coalesce(sum(case when event_type = 'PAGE_VIEW' then 1 else 0 end), 0) as page_view_count,
                               count(distinct visitor_id) as visitor_count,
                               count(distinct concat(visitor_id, ':', session_id)) as session_count,
                               count(distinct case when user_id is not null then user_id end) as login_active_user_count
                        from analytics_event
                        where business_date >= :startDate
                          and business_date <= :endDate
                        """)
                .param("startDate", context.startDate())
                .param("endDate", context.endDate())
                .query((rs, rowNum) -> new TrafficAggregate(
                        rs.getLong("page_view_count"),
                        rs.getLong("visitor_count"),
                        rs.getLong("session_count"),
                        rs.getLong("login_active_user_count")
                ))
                .single();
    }

    private MarketingAggregate marketingAggregate(LocalDateTime startAt, LocalDateTime endExclusive) {
        long issued = countBetween("coupon_claim_record", "claimed_at", startAt, endExclusive);
        long used = countBetween("user_coupon", "used_at", startAt, endExclusive);
        long expired = jdbcClient.sql("""
                        select count(*)
                        from user_coupon
                        where valid_end_at >= :startAt
                          and valid_end_at < :endExclusive
                          and valid_end_at < :now
                          and status in ('CLAIMED', 'EXPIRED')
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .param("now", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .query(Long.class)
                .single();
        CouponOrderAggregate couponOrders = jdbcClient.sql("""
                        select coalesce(sum(coupon_discount_cent), 0) as discount_cent,
                               coalesce(sum(paid_amount_cent), 0) as paid_amount_cent
                        from shop_order
                        where paid_at >= :startAt
                          and paid_at < :endExclusive
                          and coupon_discount_cent > 0
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new CouponOrderAggregate(
                        rs.getLong("discount_cent"),
                        rs.getLong("paid_amount_cent")
                ))
                .single();
        return new MarketingAggregate(issued, used, expired,
                couponOrders.discountCent(), couponOrders.paidAmountCent());
    }

    private ServiceAggregate serviceAggregate(LocalDateTime startAt, LocalDateTime endExclusive) {
        DurationAggregate durations = jdbcClient.sql("""
                        select avg(case when shipped_at is not null
                                        then timestampdiff(second, paid_at, shipped_at) else null end) as shipping_seconds,
                               avg(case when completed_at is not null
                                        then timestampdiff(second, shipped_at, completed_at) else null end) as completion_seconds
                        from shop_order
                        where paid_at >= :startAt
                          and paid_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new DurationAggregate(
                        roundedLong(rs, "shipping_seconds"),
                        roundedLong(rs, "completion_seconds")
                ))
                .single();
        long afterSaleApplications = countBetween(
                "after_sale_request", "created_at", startAt, endExclusive);
        AfterSaleAggregate afterSales = jdbcClient.sql("""
                        select coalesce(sum(case when status <> 'REJECTED' then 1 else 0 end), 0) as approved_count,
                               coalesce(sum(case when status = 'REJECTED' then 1 else 0 end), 0) as rejected_count,
                               avg(case when reviewed_at >= created_at
                                        then timestampdiff(second, created_at, reviewed_at) else null end)
                                   as review_seconds
                        from after_sale_request
                        where reviewed_at >= :startAt
                          and reviewed_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new AfterSaleAggregate(
                        afterSaleApplications,
                        rs.getLong("approved_count"),
                        rs.getLong("rejected_count"),
                        roundedLong(rs, "review_seconds")
                ))
                .single();
        RefundSuccessServiceAggregate successfulRefunds = jdbcClient.sql("""
                        select count(*) as success_count,
                               coalesce(sum(refund_amount_cent), 0) as success_amount_cent,
                               avg(case when success_at >= requested_at
                                        then timestampdiff(second, requested_at, success_at) else null end)
                                   as processing_seconds
                        from refund_order
                        where status = 'SUCCESS'
                          and success_at >= :startAt
                          and success_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new RefundSuccessServiceAggregate(
                        rs.getLong("success_count"),
                        rs.getLong("success_amount_cent"),
                        roundedLong(rs, "processing_seconds")
                ))
                .single();
        long failedRefundCount = jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where status = 'FAILED'
                          and failed_at >= :startAt
                          and failed_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();
        return new ServiceAggregate(
                durations.shippingSeconds(),
                durations.completionSeconds(),
                afterSales.applicationCount(),
                afterSales.approvedCount(),
                afterSales.rejectedCount(),
                afterSales.averageReviewSeconds(),
                successfulRefunds.successCount(),
                failedRefundCount,
                successfulRefunds.successAmountCent(),
                successfulRefunds.averageProcessingSeconds()
        );
    }

    private ServiceConversationAggregate serviceConversationAggregate(
            LocalDateTime startAt,
            LocalDateTime endExclusive
    ) {
        return jdbcClient.sql("""
                        with consultation_messages as (
                            select conversation_id,
                                   consultation_no,
                                   min(created_at) as started_at,
                                   min(case when sender_type = 'ADMIN' then created_at else null end)
                                       as first_response_at
                            from customer_service_message
                            group by conversation_id, consultation_no
                        ), consultation_windows as (
                            select conversation_id,
                                   consultation_no,
                                   started_at,
                                   first_response_at,
                                   lead(started_at) over (
                                       partition by conversation_id order by consultation_no
                                   ) as next_started_at
                            from consultation_messages
                        ), period_consultations as (
                            select consultation.*,
                                   (
                                       select min(log.created_at)
                                       from customer_service_assignment_log log
                                       where log.conversation_id = consultation.conversation_id
                                         and log.action = 'CLOSE'
                                         and log.created_at >= consultation.started_at
                                         and (consultation.next_started_at is null
                                              or log.created_at < consultation.next_started_at)
                                   ) as closed_at,
                                   case when exists (
                                       select 1
                                       from customer_service_assignment_log log
                                       where log.conversation_id = consultation.conversation_id
                                         and log.action in ('TRANSFER', 'FORCE_TRANSFER')
                                         and log.created_at >= consultation.started_at
                                         and (consultation.next_started_at is null
                                              or log.created_at < consultation.next_started_at)
                                   ) then 1 else 0 end as transferred
                            from consultation_windows consultation
                            where consultation.started_at >= :startAt
                              and consultation.started_at < :endExclusive
                        )
                        select count(*) as conversation_count,
                               avg(case when first_response_at is not null
                                        then timestampdiff(second, started_at, first_response_at)
                                        else null end) as first_response_seconds,
                               avg(case when closed_at is not null
                                        then timestampdiff(second, started_at, closed_at)
                                        else null end) as resolution_seconds,
                               coalesce(sum(case when closed_at is not null then 1 else 0 end), 0)
                                   as closed_count,
                               coalesce(sum(transferred), 0) as transferred_count
                        from period_consultations
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new ServiceConversationAggregate(
                        rs.getLong("conversation_count"),
                        roundedLong(rs, "first_response_seconds"),
                        roundedLong(rs, "resolution_seconds"),
                        rs.getLong("closed_count"),
                        rs.getLong("transferred_count")
                ))
                .single();
    }

    private Map<String, MetricValue> tradeSummary(
            ReportContext context,
            TradeAggregate current,
            TradeAggregate previous
    ) {
        Map<String, MetricValue> summary = new LinkedHashMap<>();
        summary.put("createdOrderCount", metric(current.createdOrderCount(), previous.createdOrderCount(), MetricUnit.COUNT));
        summary.put("paidOrderCount", metric(current.paidOrderCount(), previous.paidOrderCount(), MetricUnit.COUNT));
        summary.put("paidBuyerCount", metric(current.paidBuyerCount(), previous.paidBuyerCount(), MetricUnit.COUNT));
        summary.put("paidAmountCent", metric(current.paidAmountCent(), previous.paidAmountCent(), MetricUnit.CENT));
        summary.put("successfulRefundCount", metric(current.refundCount(), previous.refundCount(), MetricUnit.COUNT));
        summary.put("successfulRefundAmountCent", metric(current.refundAmountCent(), previous.refundAmountCent(), MetricUnit.CENT));
        summary.put("netReceiptAmountCent", metric(current.netReceiptAmountCent(), previous.netReceiptAmountCent(), MetricUnit.CENT));
        summary.put("couponDiscountCent", metric(current.couponDiscountCent(), previous.couponDiscountCent(), MetricUnit.CENT));
        summary.put("freightCent", metric(current.freightCent(), previous.freightCent(), MetricUnit.CENT));
        summary.put("averageOrderAmountCent", nullableApplicableMetric(
                divideAmount(current.paidAmountCent(), current.paidOrderCount()),
                divideAmount(previous.paidAmountCent(), previous.paidOrderCount()),
                MetricUnit.CENT));
        summary.put("customerUnitPriceCent", nullableApplicableMetric(
                divideAmount(current.paidAmountCent(), current.paidBuyerCount()),
                divideAmount(previous.paidAmountCent(), previous.paidBuyerCount()),
                MetricUnit.CENT));
        summary.put("orderPaymentConversionRate", ratioMetric(
                current.eventuallyPaidCreatedOrderCount(), current.createdOrderCount(),
                previous.eventuallyPaidCreatedOrderCount(), previous.createdOrderCount()));
        TradeDurationAggregate currentDurations = tradeDurationAggregate(
                context.startAt(), context.endExclusive());
        TradeDurationAggregate previousDurations = tradeDurationAggregate(
                context.comparisonStartAt(), context.comparisonEndExclusive());
        summary.put("createToPaySeconds", nullableApplicableMetric(
                currentDurations.createToPaySeconds(), previousDurations.createToPaySeconds(), MetricUnit.SECOND));
        summary.put("payToShipSeconds", nullableApplicableMetric(
                currentDurations.payToShipSeconds(), previousDurations.payToShipSeconds(), MetricUnit.SECOND));
        summary.put("shipToCompleteSeconds", nullableApplicableMetric(
                currentDurations.shipToCompleteSeconds(), previousDurations.shipToCompleteSeconds(),
                MetricUnit.SECOND));

        PaymentAttemptAggregate attempts = paymentAttemptAggregate(context.startAt(), context.endExclusive());
        LocalDateTime paymentCollectionStartedAt = paymentAttemptCollectionStartedAt();
        if (!collectionAvailable(paymentCollectionStartedAt, context.endExclusive())) {
            summary.put("paymentAttemptCount", unavailableMetric(MetricUnit.COUNT));
            summary.put("paymentAttemptSuccessRate", unavailableMetric(MetricUnit.BASIS_POINT));
        } else {
            summary.put("paymentAttemptCount", metric(attempts.attemptCount(), MetricUnit.COUNT));
            summary.put("paymentAttemptSuccessRate", ratioMetric(
                    attempts.paidCount(), attempts.attemptCount(), null, null));
        }
        return summary;
    }

    private TradeDurationAggregate tradeDurationAggregate(
            LocalDateTime startAt,
            LocalDateTime endExclusive
    ) {
        return jdbcClient.sql("""
                        select avg(case when paid_at is not null and paid_at >= created_at
                                        then timestampdiff(second, created_at, paid_at) else null end)
                                   as create_to_pay_seconds,
                               avg(case when shipped_at is not null and paid_at is not null
                                             and shipped_at >= paid_at
                                        then timestampdiff(second, paid_at, shipped_at) else null end)
                                   as pay_to_ship_seconds,
                               avg(case when completed_at is not null and shipped_at is not null
                                             and completed_at >= shipped_at
                                        then timestampdiff(second, shipped_at, completed_at) else null end)
                                   as ship_to_complete_seconds
                        from shop_order
                        where created_at >= :startAt
                          and created_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new TradeDurationAggregate(
                        roundedLong(rs, "create_to_pay_seconds"),
                        roundedLong(rs, "pay_to_ship_seconds"),
                        roundedLong(rs, "ship_to_complete_seconds")
                ))
                .single();
    }

    private List<TodoItem> todoItems() {
        OrderTodoAggregate orders = jdbcClient.sql("""
                        select coalesce(sum(case when status in ('CREATED', 'PAYING') then 1 else 0 end), 0) as unpaid_count,
                               coalesce(sum(case when status in ('PAID', 'PARTIALLY_SHIPPED') then 1 else 0 end), 0) as to_ship_count
                        from shop_order
                        """)
                .query((rs, rowNum) -> new OrderTodoAggregate(
                        rs.getLong("unpaid_count"),
                        rs.getLong("to_ship_count")
                ))
                .single();
        long pendingAfterSales = countWhere("after_sale_request", "status = 'REQUESTED'");
        long failedRefunds = countWhere("refund_order", "status = 'FAILED'");
        long shippingFailures = countWhere("order_shipment",
                "wechat_upload_status in ('FAILED', 'UNAVAILABLE', 'UNKNOWN')");
        long lowStockSkus = jdbcClient.sql("""
                        select count(*)
                        from product_sku k
                        join product_spu s on s.id = k.spu_id
                        where k.deleted_at is null
                          and k.status = 'ENABLED'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and s.status = 'ON_SALE'
                          and k.stock_available <= k.low_stock_threshold
                        """)
                .query(Long.class)
                .single();
        long waitingConversations = countWhere("customer_service_conversation", "status = 'WAITING'");
        return List.of(
                new TodoItem("unpaidOrders", "待付款订单", orders.unpaidCount(), "INFO"),
                new TodoItem("toShipOrders", "待发货订单", orders.toShipCount(), "WARNING"),
                new TodoItem("pendingAfterSales", "待审核售后", pendingAfterSales, "WARNING"),
                new TodoItem("failedRefunds", "退款失败", failedRefunds, "DANGER"),
                new TodoItem("wechatShippingFailures", "微信发货上传异常", shippingFailures, "DANGER"),
                new TodoItem("lowStockSkus", "低库存 SKU", lowStockSkus, "WARNING"),
                new TodoItem("waitingConversations", "待接待客服会话", waitingConversations, "WARNING")
        );
    }

    private List<RankingItem> productRanking(ReportContext context, int limit) {
        return jdbcClient.sql("""
                        select oi.spu_id,
                               coalesce(max(s.title), max(oi.product_title)) as product_name,
                               max(s.main_image) as main_image,
                               coalesce(sum(oi.quantity), 0) as sold_quantity,
                               coalesce(sum(oi.line_amount_cent), 0) as paid_item_amount_cent
                        from order_item oi
                        join shop_order o on o.id = oi.order_id
                        left join product_spu s on s.id = oi.spu_id
                        where o.paid_at >= :startAt
                          and o.paid_at < :endExclusive
                        group by oi.spu_id
                        order by sold_quantity desc, paid_item_amount_cent desc, oi.spu_id
                        limit :limit
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .param("limit", limit)
                .query((rs, rowNum) -> new RankingItem(
                        String.valueOf(rs.getLong("spu_id")),
                        rs.getString("product_name"),
                        rs.getString("main_image"),
                        rs.getLong("sold_quantity"),
                        MetricUnit.COUNT,
                        rs.getLong("paid_item_amount_cent"),
                        MetricUnit.CENT
                ))
                .list();
    }

    private List<RankingItem> categoryRanking(ReportContext context) {
        return jdbcClient.sql("""
                        select coalesce(c.id, 0) as category_id,
                               coalesce(max(c.name), '历史商品') as category_name,
                               coalesce(sum(oi.quantity), 0) as sold_quantity,
                               coalesce(sum(oi.line_amount_cent), 0) as paid_item_amount_cent
                        from order_item oi
                        join shop_order o on o.id = oi.order_id
                        left join product_spu s on s.id = oi.spu_id
                        left join product_category c on c.id = s.category_id
                        where o.paid_at >= :startAt
                          and o.paid_at < :endExclusive
                        group by c.id
                        order by sold_quantity desc, paid_item_amount_cent desc, category_id
                        limit :limit
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .param("limit", RANKING_LIMIT)
                .query((rs, rowNum) -> new RankingItem(
                        String.valueOf(rs.getLong("category_id")),
                        rs.getString("category_name"),
                        null,
                        rs.getLong("sold_quantity"),
                        MetricUnit.COUNT,
                        rs.getLong("paid_item_amount_cent"),
                        MetricUnit.CENT
                ))
                .list();
    }

    private List<AlertItem> stockAlerts() {
        return jdbcClient.sql("""
                        select k.id as sku_id, s.title, k.sku_code, k.spec_text,
                               k.stock_available, k.low_stock_threshold
                        from product_sku k
                        join product_spu s on s.id = k.spu_id
                        where k.deleted_at is null
                          and k.status = 'ENABLED'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and s.status = 'ON_SALE'
                          and k.stock_available <= k.low_stock_threshold
                        order by k.stock_available, k.id
                        limit :limit
                        """)
                .param("limit", RANKING_LIMIT)
                .query((rs, rowNum) -> new AlertItem(
                        String.valueOf(rs.getLong("sku_id")),
                        rs.getString("title"),
                        rs.getString("sku_code") + " / " + rs.getString("spec_text")
                                + " / 预警值 " + rs.getInt("low_stock_threshold"),
                        rs.getLong("stock_available") <= 0 ? "DANGER" : "WARNING",
                        rs.getLong("stock_available"),
                        MetricUnit.COUNT
                ))
                .list();
    }

    private List<RecentOrderItem> recentOrders(ReportContext context) {
        return jdbcClient.sql("""
                        select o.id, o.order_no, o.status, o.paid_amount_cent, o.paid_at,
                               coalesce(nullif(u.nickname, ''), concat('用户#', o.user_id)) as user_name
                        from shop_order o
                        left join app_user u on u.id = o.user_id
                        where o.paid_at >= :startAt
                          and o.paid_at < :endExclusive
                        order by o.paid_at desc, o.id desc
                        limit 5
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new RecentOrderItem(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("order_no"),
                        rs.getString("user_name"),
                        rs.getLong("paid_amount_cent"),
                        rs.getString("status"),
                        rs.getObject("paid_at", LocalDateTime.class)
                ))
                .list();
    }

    private List<BreakdownItem> breakdown(
            String table,
            String dimensionColumn,
            String timeColumn,
            ReportContext context
    ) {
        String sql = "select " + dimensionColumn + " as dimension_value, count(*) as item_count "
                + "from " + table + " where " + timeColumn + " >= :startAt and " + timeColumn + " < :endExclusive "
                + "group by " + dimensionColumn + " order by item_count desc, dimension_value";
        List<NamedCount> counts = jdbcClient.sql(sql)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new NamedCount(rs.getString("dimension_value"), rs.getLong("item_count")))
                .list();
        return toBreakdown(counts, table + "." + dimensionColumn);
    }

    private List<BreakdownItem> toBreakdown(List<NamedCount> counts) {
        return toBreakdown(counts, null);
    }

    private List<BreakdownItem> toBreakdown(List<NamedCount> counts, String labelContext) {
        long total = counts.stream().mapToLong(NamedCount::count).sum();
        return counts.stream()
                .map(item -> new BreakdownItem(
                        normalizeKey(item.name()),
                        labelFor(labelContext, item.name()),
                        item.count(),
                        basisPoints(item.count(), total)
                ))
                .toList();
    }

    private List<BreakdownItem> purchaseSegments(ReportContext context) {
        PurchaseSegments segments = jdbcClient.sql("""
                        select coalesce(sum(case when paid_orders = 0 then 1 else 0 end), 0) as no_purchase,
                               coalesce(sum(case when paid_orders = 1 then 1 else 0 end), 0) as one_order,
                               coalesce(sum(case when paid_orders between 2 and 4 then 1 else 0 end), 0) as two_to_four,
                               coalesce(sum(case when paid_orders >= 5 then 1 else 0 end), 0) as five_plus
                        from (
                            select u.id, count(o.id) as paid_orders
                            from app_user u
                            left join shop_order o on o.user_id = u.id
                                                   and o.paid_at is not null
                                                   and o.paid_at < :endExclusive
                            where u.created_at < :endExclusive
                            group by u.id
                        ) purchase_counts
                        """)
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new PurchaseSegments(
                        rs.getLong("no_purchase"),
                        rs.getLong("one_order"),
                        rs.getLong("two_to_four"),
                        rs.getLong("five_plus")
                ))
                .single();
        List<NamedCount> counts = List.of(
                new NamedCount("NO_PURCHASE", segments.noPurchase()),
                new NamedCount("ONE_ORDER", segments.oneOrder()),
                new NamedCount("TWO_TO_FOUR", segments.twoToFour()),
                new NamedCount("FIVE_PLUS", segments.fivePlus())
        );
        return toBreakdown(counts);
    }

    private List<RankingItem> topCustomers(ReportContext context) {
        return jdbcClient.sql("""
                        select o.user_id,
                               coalesce(nullif(max(u.nickname), ''), concat('用户#', o.user_id)) as user_name,
                               count(*) as paid_order_count,
                               coalesce(sum(o.paid_amount_cent), 0) as paid_amount_cent
                        from shop_order o
                        left join app_user u on u.id = o.user_id
                        where o.paid_at >= :startAt
                          and o.paid_at < :endExclusive
                        group by o.user_id
                        order by paid_amount_cent desc, paid_order_count desc, o.user_id
                        limit :limit
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .param("limit", RANKING_LIMIT)
                .query((rs, rowNum) -> new RankingItem(
                        String.valueOf(rs.getLong("user_id")),
                        rs.getString("user_name"),
                        null,
                        rs.getLong("paid_amount_cent"),
                        MetricUnit.CENT,
                        rs.getLong("paid_order_count"),
                        MetricUnit.COUNT
                ))
                .list();
    }

    private List<BreakdownItem> analyticsBreakdown(
            ReportContext context,
            String column,
            String eventType
    ) {
        String eventPredicate = eventType == null ? "" : " and event_type = :eventType";
        String sql = "select case when " + column + " = '' then 'UNKNOWN' else " + column + " end as dimension_value, "
                + "count(*) as item_count from analytics_event "
                + "where business_date >= :startDate and business_date <= :endDate" + eventPredicate
                + " group by case when " + column + " = '' then 'UNKNOWN' else " + column + " end"
                + " order by item_count desc, dimension_value";
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("startDate", context.startDate())
                .param("endDate", context.endDate());
        if (eventType != null) {
            statement = statement.param("eventType", eventType);
        }
        return toBreakdown(statement
                .query((rs, rowNum) -> new NamedCount(rs.getString("dimension_value"), rs.getLong("item_count")))
                .list());
    }

    private List<RankingItem> analyticsRanking(
            ReportContext context,
            String column,
            String eventType
    ) {
        String sql = "select " + column + " as ranking_name, count(*) as item_count, "
                + "count(distinct visitor_id) as visitor_count from analytics_event "
                + "where business_date >= :startDate and business_date <= :endDate "
                + "and event_type = :eventType and " + column + " <> '' "
                + "group by " + column + " order by item_count desc, ranking_name limit :limit";
        return jdbcClient.sql(sql)
                .param("startDate", context.startDate())
                .param("endDate", context.endDate())
                .param("eventType", eventType)
                .param("limit", RANKING_LIMIT)
                .query((rs, rowNum) -> new RankingItem(
                        rs.getString("ranking_name"),
                        rs.getString("ranking_name"),
                        null,
                        rs.getLong("item_count"),
                        MetricUnit.COUNT,
                        rs.getLong("visitor_count"),
                        MetricUnit.COUNT
                ))
                .list();
    }

    private List<FunnelStage> trafficFunnel(ReportContext context) {
        TrafficFunnelCounts counts = trafficStatisticsQueryRepository.loadFunnelCounts(
                context.startDate(),
                context.endDate(),
                context.startAt(),
                context.endExclusive());

        List<StageCount> stages = List.of(
                new StageCount("homeVisit", "首页访问", counts.homeCount()),
                new StageCount("productView", "商品详情", counts.productCount()),
                new StageCount("cartAdd", "加入购物车", counts.cartCount()),
                new StageCount("checkoutStart", "开始结算", counts.checkoutCount()),
                new StageCount("orderSubmit", "提交订单", counts.submittedCount()),
                new StageCount("paymentSuccess", "支付成功", counts.paidCount())
        );
        List<FunnelStage> result = new ArrayList<>();
        long previous = 0;
        for (int i = 0; i < stages.size(); i++) {
            StageCount stage = stages.get(i);
            Long conversion = i == 0
                    ? (stage.count() == 0 ? null : 10_000L)
                    : basisPoints(stage.count(), previous);
            result.add(new FunnelStage(stage.key(), stage.label(), stage.count(), conversion));
            previous = stage.count();
        }
        return result;
    }

    private List<RankingItem> couponTemplateRanking(ReportContext context) {
        return jdbcClient.sql("""
                        select t.id, t.name,
                               (select count(*)
                                from coupon_claim_record c
                                where c.template_id = t.id
                                  and c.claimed_at >= :startAt
                                  and c.claimed_at < :endExclusive) as issued_count,
                               (select count(*)
                                from user_coupon u
                                where u.template_id = t.id
                                  and u.used_at >= :startAt
                                  and u.used_at < :endExclusive) as used_count,
                               (select coalesce(sum(o.coupon_discount_cent), 0)
                                from user_coupon u
                                join shop_order o on o.id = u.used_order_id
                                where u.template_id = t.id
                                  and u.used_at >= :startAt
                                  and u.used_at < :endExclusive) as discount_cent
                        from coupon_template t
                        where exists (
                            select 1 from coupon_claim_record c
                            where c.template_id = t.id
                              and c.claimed_at >= :startAt
                              and c.claimed_at < :endExclusive
                        ) or exists (
                            select 1 from user_coupon u
                            where u.template_id = t.id
                              and u.used_at >= :startAt
                              and u.used_at < :endExclusive
                        )
                        order by used_count desc, issued_count desc, t.id
                        limit :limit
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .param("limit", RANKING_LIMIT)
                .query((rs, rowNum) -> new RankingItem(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("name"),
                        "发放 " + rs.getLong("issued_count") + " / 使用 " + rs.getLong("used_count"),
                        rs.getLong("discount_cent"),
                        MetricUnit.CENT,
                        rs.getLong("used_count"),
                        MetricUnit.COUNT
                ))
                .list();
    }

    private List<BreakdownItem> shippingCompanyBreakdown(ReportContext context) {
        List<NamedCount> counts = jdbcClient.sql("""
                        select coalesce(express_company_name, '其他') as dimension_value,
                               count(*) as item_count
                        from order_shipment
                        where shipped_at >= :startAt
                          and shipped_at < :endExclusive
                        group by express_company_name
                        order by item_count desc, dimension_value
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new NamedCount(rs.getString("dimension_value"), rs.getLong("item_count")))
                .list();
        return toBreakdown(counts);
    }

    private List<BreakdownItem> shippingStatusBreakdown(ReportContext context) {
        List<NamedCount> counts = jdbcClient.sql("""
                        select wechat_upload_status as dimension_value, count(*) as item_count
                        from order_shipment
                        where coalesce(last_attempt_at, shipped_at, created_at) >= :startAt
                          and coalesce(last_attempt_at, shipped_at, created_at) < :endExclusive
                        group by wechat_upload_status
                        order by item_count desc, dimension_value
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new NamedCount(rs.getString("dimension_value"), rs.getLong("item_count")))
                .list();
        return toBreakdown(counts, "order_shipment.wechat_upload_status");
    }

    private List<BreakdownItem> refundReasonBreakdown(ReportContext context) {
        List<NamedCount> counts = jdbcClient.sql("""
                        select reason as dimension_value, count(*) as item_count
                        from after_sale_request
                        where created_at >= :startAt
                          and created_at < :endExclusive
                        group by reason
                        order by item_count desc, dimension_value
                        limit :limit
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .param("limit", RANKING_LIMIT)
                .query((rs, rowNum) -> new NamedCount(rs.getString("dimension_value"), rs.getLong("item_count")))
                .list();
        return toBreakdown(counts);
    }

    private List<AgentLoadItem> agentLoads(ReportContext context) {
        return jdbcClient.sql("""
                        select u.id, u.display_name,
                               coalesce(sum(case when c.status = 'ACTIVE' then 1 else 0 end), 0) as active_count,
                               coalesce(sum(case when c.status = 'CLOSED'
                                                  and c.closed_at >= :startAt and c.closed_at < :endExclusive
                                                 then 1 else 0 end), 0) as closed_count,
                               avg(case when c.activated_at >= :startAt and c.activated_at < :endExclusive
                                             and response.first_response_at >= c.activated_at
                                        then timestampdiff(second, c.activated_at, response.first_response_at)
                                        else null end) as first_response_seconds
                        from admin_user u
                        join customer_service_conversation c on c.assigned_admin_user_id = u.id
                        left join (
                            select conversation_id, consultation_no, min(created_at) as first_response_at
                            from customer_service_message
                            where sender_type = 'ADMIN'
                            group by conversation_id, consultation_no
                        ) response on response.conversation_id = c.id
                                  and response.consultation_no = c.consultation_no
                        group by u.id, u.display_name
                        order by active_count desc, closed_count desc, u.id
                        limit :limit
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .param("limit", RANKING_LIMIT)
                .query((rs, rowNum) -> new AgentLoadItem(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("display_name"),
                        rs.getLong("active_count"),
                        rs.getLong("closed_count"),
                        roundedLong(rs, "first_response_seconds")
                ))
                .list();
    }

    private List<TrendSeries> overviewTrend(ReportContext context) {
        List<PaidTrendFact> paidFacts = paidTrendFacts(context);
        List<TimedValue> refundFacts = refundTrendFacts(context);
        List<TimedValue> newUserFacts = newUserTrendFacts(context);
        return List.of(
                new TrendSeries("paidAmountCent", "支付 GMV", MetricUnit.CENT,
                        sumPoints(context, paidFacts.stream()
                                .map(fact -> new TimedValue(fact.occurredAt(), fact.amountCent()))
                                .toList())),
                new TrendSeries("refundAmountCent", "成功退款", MetricUnit.CENT,
                        sumPoints(context, refundFacts)),
                new TrendSeries("paidOrderCount", "支付订单", MetricUnit.COUNT,
                        countPoints(context, paidFacts.stream()
                                .map(fact -> fact.occurredAt())
                                .toList())),
                new TrendSeries("paidBuyerCount", "支付用户", MetricUnit.COUNT,
                        distinctPoints(context, paidFacts.stream()
                                .map(fact -> new TimedKey(fact.occurredAt(), String.valueOf(fact.userId())))
                                .toList())),
                new TrendSeries("newUserCount", "新增用户", MetricUnit.COUNT,
                        countPoints(context, newUserFacts.stream().map(TimedValue::occurredAt).toList()))
        );
    }

    private List<TrendSeries> tradeTrend(ReportContext context) {
        List<PaidTrendFact> paidFacts = paidTrendFacts(context);
        List<TimedValue> refundFacts = refundTrendFacts(context);
        return List.of(
                new TrendSeries("paidAmountCent", "支付 GMV", MetricUnit.CENT,
                        sumPoints(context, paidFacts.stream()
                                .map(fact -> new TimedValue(fact.occurredAt(), fact.amountCent()))
                                .toList())),
                new TrendSeries("successfulRefundAmountCent", "成功退款", MetricUnit.CENT,
                        sumPoints(context, refundFacts)),
                new TrendSeries("netReceiptAmountCent", "净收款", MetricUnit.CENT,
                        subtractPoints(
                                sumPoints(context, paidFacts.stream()
                                        .map(fact -> new TimedValue(fact.occurredAt(), fact.amountCent()))
                                        .toList()),
                                sumPoints(context, refundFacts))),
                new TrendSeries("paidOrderCount", "支付订单", MetricUnit.COUNT,
                        countPoints(context, paidFacts.stream().map(PaidTrendFact::occurredAt).toList()))
        );
    }

    private List<TrendSeries> productTrend(ReportContext context) {
        List<Bucket> buckets = buckets(context);
        Map<Integer, ProductTrendBucket> aggregateByBucket = new HashMap<>();
        commerceTrendQueryRepository.loadProductTrendBuckets(
                        context.startDate(),
                        context.startAt(),
                        context.endExclusive(),
                        context.granularity())
                .forEach(bucket -> aggregateByBucket.put(bucket.bucketOrdinal(), bucket));
        return List.of(
                new TrendSeries("soldQuantity", "支付件数", MetricUnit.COUNT,
                        bucketTrendPoints(buckets, aggregateByBucket, ProductTrendBucket::soldQuantity)),
                new TrendSeries("paidItemAmountCent", "支付商品毛额", MetricUnit.CENT,
                        bucketTrendPoints(buckets, aggregateByBucket, ProductTrendBucket::paidItemAmountCent))
        );
    }

    private List<TrendSeries> userTrend(ReportContext context) {
        List<TimedValue> newUsers = newUserTrendFacts(context);
        List<TimedKey> activities = jdbcClient.sql("""
                        select activity_date, user_id
                        from app_user_daily_activity
                        where activity_date >= :startDate
                          and activity_date < :endDate
                        order by activity_date, user_id
                        """)
                .param("startDate", context.startDate())
                .param("endDate", context.endDate().plusDays(1))
                .query((rs, rowNum) -> new TimedKey(
                        TimePolicy.businessDayStartUtc(rs.getObject("activity_date", LocalDate.class)),
                        String.valueOf(rs.getLong("user_id"))
                ))
                .list();
        List<PaidTrendFact> paidFacts = paidTrendFacts(context);
        List<TimedKey> firstPurchases = jdbcClient.sql("""
                        select user_id, min(paid_at) as first_paid_at
                        from shop_order
                        where paid_at is not null
                        group by user_id
                        having min(paid_at) >= :startAt and min(paid_at) < :endExclusive
                        order by first_paid_at, user_id
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new TimedKey(
                        rs.getObject("first_paid_at", LocalDateTime.class),
                        String.valueOf(rs.getLong("user_id"))
                ))
                .list();
        return List.of(
                new TrendSeries("newUserCount", "新增用户", MetricUnit.COUNT,
                        countPoints(context, newUsers.stream().map(TimedValue::occurredAt).toList())),
                new TrendSeries("activeUserCount", "活跃用户", MetricUnit.COUNT,
                        distinctPoints(context, activities)),
                new TrendSeries("paidBuyerCount", "支付用户", MetricUnit.COUNT,
                        distinctPoints(context, paidFacts.stream()
                                .map(fact -> new TimedKey(fact.occurredAt(), String.valueOf(fact.userId())))
                                .toList())),
                new TrendSeries("newPayingBuyerCount", "新增支付用户", MetricUnit.COUNT,
                        distinctPoints(context, firstPurchases))
        );
    }

    private DataBlock<List<RetentionCohortItem>> retentionCohorts(
            ReportContext context,
            LocalDateTime collectionStartedAt
    ) {
        List<RegisteredUser> registeredUsers = jdbcClient.sql("""
                        select id, created_at
                        from app_user
                        where created_at >= :startAt
                          and created_at < :endExclusive
                        order by created_at, id
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new RegisteredUser(
                        rs.getLong("id"),
                        TimePolicy.businessDate(rs.getObject("created_at", LocalDateTime.class))
                ))
                .list();
        if (registeredUsers.isEmpty()) {
            return available(List.of());
        }

        Map<Long, Set<LocalDate>> activityDatesByUser = new HashMap<>();
        jdbcClient.sql("""
                        select user_id, activity_date
                        from app_user_daily_activity
                        where activity_date >= :startDate
                          and activity_date <= :endDate
                        order by activity_date, user_id
                        """)
                .param("startDate", context.startDate().plusDays(1))
                .param("endDate", context.endDate().plusDays(30))
                .query((rs, rowNum) -> new UserActivityDate(
                        rs.getLong("user_id"),
                        rs.getObject("activity_date", LocalDate.class)
                ))
                .list()
                .forEach(activity -> activityDatesByUser
                        .computeIfAbsent(activity.userId(), ignored -> new HashSet<>())
                        .add(activity.activityDate()));

        Map<RetentionBucket, List<RegisteredUser>> usersByCohort = new LinkedHashMap<>();
        for (RegisteredUser user : registeredUsers) {
            RetentionBucket bucket = retentionBucket(context, user.registeredDate());
            usersByCohort.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(user);
        }

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        boolean hasMatureWindow = false;
        boolean hasCollectedMatureWindow = false;
        List<RetentionCohortItem> cohorts = new ArrayList<>();
        for (Map.Entry<RetentionBucket, List<RegisteredUser>> cohortEntry : usersByCohort.entrySet()) {
            RetentionBucket bucket = cohortEntry.getKey();
            List<RegisteredUser> cohortUsers = cohortEntry.getValue();
            List<RetentionWindow> windows = new ArrayList<>();
            for (int dayOffset : List.of(1, 7, 30)) {
                boolean mature = cohortUsers.stream()
                        .allMatch(user -> user.registeredDate().plusDays(dayOffset).isBefore(today));
                hasMatureWindow = hasMatureWindow || mature;
                boolean fullyCollected = mature
                        && collectionStartedAt != null
                        && cohortUsers.stream().allMatch(user -> !user.registeredDate()
                                .plusDays(dayOffset)
                                .atStartOfDay()
                                .atZone(BUSINESS_ZONE)
                                .withZoneSameInstant(TimePolicy.UTC)
                                .toLocalDateTime()
                                .isBefore(collectionStartedAt));
                if (!fullyCollected) {
                    windows.add(new RetentionWindow(dayOffset, 0, null, null));
                    continue;
                }

                hasCollectedMatureWindow = true;
                long retainedUsers = cohortUsers.stream()
                        .filter(user -> activityDatesByUser
                                .getOrDefault(user.id(), Set.of())
                                .contains(user.registeredDate().plusDays(dayOffset)))
                        .count();
                windows.add(new RetentionWindow(
                        dayOffset,
                        cohortUsers.size(),
                        retainedUsers,
                        basisPoints(retainedUsers, cohortUsers.size())
                ));
            }
            cohorts.add(new RetentionCohortItem(
                    bucket.key(),
                    bucket.startDate(),
                    bucket.endDate(),
                    cohortUsers.size(),
                    windows
            ));
        }

        if (hasMatureWindow && !hasCollectedMatureWindow) {
            return notCollected(
                    "USER_RETENTION_NOT_COLLECTED",
                    "用户留存只能统计活跃采集完整覆盖后的成熟注册 cohort",
                    cohorts
            );
        }
        return available(cohorts);
    }

    private RetentionBucket retentionBucket(ReportContext context, LocalDate registeredDate) {
        boolean daily = context.granularity() == Granularity.HOUR
                || context.granularity() == Granularity.DAY;
        if (daily) {
            return new RetentionBucket(registeredDate.toString(), registeredDate, registeredDate);
        }
        long weekIndex = ChronoUnit.DAYS.between(context.startDate(), registeredDate) / 7;
        LocalDate startDate = context.startDate().plusDays(weekIndex * 7);
        LocalDate endDate = startDate.plusDays(6).isBefore(context.endDate())
                ? startDate.plusDays(6)
                : context.endDate();
        return new RetentionBucket(startDate + "/" + endDate, startDate, endDate);
    }

    private List<TrendSeries> trafficTrend(ReportContext context) {
        List<Bucket> buckets = buckets(context);
        Map<Integer, TrafficTrendBucket> aggregateByBucket = new HashMap<>();
        trafficStatisticsQueryRepository.loadTrendBuckets(
                        context.startDate(),
                        context.endDate(),
                        context.startAt(),
                        context.endExclusive(),
                        context.granularity())
                .forEach(bucket -> aggregateByBucket.put(bucket.bucketOrdinal(), bucket));
        return List.of(
                new TrendSeries("pageViewCount", "PV", MetricUnit.COUNT,
                        bucketTrendPoints(buckets, aggregateByBucket, TrafficTrendBucket::pageViewCount)),
                new TrendSeries("visitorCount", "UV", MetricUnit.COUNT,
                        bucketTrendPoints(buckets, aggregateByBucket, TrafficTrendBucket::visitorCount)),
                new TrendSeries("sessionCount", "会话", MetricUnit.COUNT,
                        bucketTrendPoints(buckets, aggregateByBucket, TrafficTrendBucket::sessionCount))
        );
    }

    private <T> List<TrendPoint> bucketTrendPoints(
            List<Bucket> buckets,
            Map<Integer, T> aggregateByBucket,
            ToLongFunction<T> valueExtractor
    ) {
        List<TrendPoint> points = new ArrayList<>(buckets.size());
        for (int index = 0; index < buckets.size(); index++) {
            Bucket bucket = buckets.get(index);
            T aggregate = aggregateByBucket.get(index);
            long value = aggregate == null ? 0L : valueExtractor.applyAsLong(aggregate);
            points.add(new TrendPoint(bucket.key(), bucket.label(), value));
        }
        return points;
    }

    private List<TrendSeries> marketingTrend(ReportContext context) {
        List<LocalDateTime> issued = timestampsBetween("coupon_claim_record", "claimed_at", context);
        List<LocalDateTime> used = timestampsBetween("user_coupon", "used_at", context);
        List<TimedValue> discounts = jdbcClient.sql("""
                        select paid_at, coupon_discount_cent
                        from shop_order
                        where paid_at >= :startAt
                          and paid_at < :endExclusive
                          and coupon_discount_cent > 0
                        order by paid_at, id
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new TimedValue(
                        rs.getObject("paid_at", LocalDateTime.class),
                        rs.getLong("coupon_discount_cent")
                ))
                .list();
        return List.of(
                new TrendSeries("issuedCouponCount", "发放量", MetricUnit.COUNT, countPoints(context, issued)),
                new TrendSeries("usedCouponCount", "使用量", MetricUnit.COUNT, countPoints(context, used)),
                new TrendSeries("couponDiscountCent", "优惠金额", MetricUnit.CENT, sumPoints(context, discounts))
        );
    }

    private List<TrendSeries> serviceTrend(ReportContext context) {
        List<LocalDateTime> shipped = timestampsBetween("order_shipment", "shipped_at", context);
        List<LocalDateTime> afterSales = timestampsBetween("after_sale_request", "created_at", context);
        List<LocalDateTime> conversations = timestampsBetween(
                "customer_service_conversation", "activated_at", context);
        List<LocalDateTime> refunds = jdbcClient.sql("""
                        select success_at
                        from refund_order
                        where status = 'SUCCESS'
                          and success_at >= :startAt
                          and success_at < :endExclusive
                        order by success_at, id
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query(LocalDateTime.class)
                .list();
        return List.of(
                new TrendSeries("conversationCount", "咨询会话", MetricUnit.COUNT,
                        countPoints(context, conversations)),
                new TrendSeries("shippedOrderCount", "发货订单", MetricUnit.COUNT, countPoints(context, shipped)),
                new TrendSeries("afterSaleApplicationCount", "售后申请", MetricUnit.COUNT, countPoints(context, afterSales)),
                new TrendSeries("successfulRefundCount", "退款成功", MetricUnit.COUNT, countPoints(context, refunds))
        );
    }

    private List<TrendPoint> hourlyPaidOrders(ReportContext context) {
        Map<Integer, Long> counts = new HashMap<>();
        jdbcClient.sql("""
                        select extract(hour from timestampadd(HOUR, 8, paid_at)) as paid_hour,
                               count(*) as item_count
                        from shop_order
                        where paid_at >= :startAt
                          and paid_at < :endExclusive
                        group by extract(hour from timestampadd(HOUR, 8, paid_at))
                        order by paid_hour
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new HourCount(rs.getInt("paid_hour"), rs.getLong("item_count")))
                .list()
                .forEach(item -> counts.put(item.hour(), item.count()));
        List<TrendPoint> points = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            String label = String.format(Locale.ROOT, "%02d:00", hour);
            points.add(new TrendPoint(label, label, counts.getOrDefault(hour, 0L)));
        }
        return points;
    }

    private List<PaidTrendFact> paidTrendFacts(ReportContext context) {
        return jdbcClient.sql("""
                        select paid_at, paid_amount_cent, user_id
                        from shop_order
                        where paid_at >= :startAt
                          and paid_at < :endExclusive
                        order by paid_at, id
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new PaidTrendFact(
                        rs.getObject("paid_at", LocalDateTime.class),
                        rs.getLong("paid_amount_cent"),
                        rs.getLong("user_id")
                ))
                .list();
    }

    private List<TimedValue> refundTrendFacts(ReportContext context) {
        return jdbcClient.sql("""
                        select success_at, refund_amount_cent
                        from refund_order
                        where status = 'SUCCESS'
                          and success_at >= :startAt
                          and success_at < :endExclusive
                        order by success_at, id
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new TimedValue(
                        rs.getObject("success_at", LocalDateTime.class),
                        rs.getLong("refund_amount_cent")
                ))
                .list();
    }

    private List<TimedValue> newUserTrendFacts(ReportContext context) {
        return jdbcClient.sql("""
                        select created_at
                        from app_user
                        where created_at >= :startAt
                          and created_at < :endExclusive
                        order by created_at, id
                        """)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query((rs, rowNum) -> new TimedValue(
                        rs.getObject("created_at", LocalDateTime.class),
                        1L
                ))
                .list();
    }

    private List<LocalDateTime> timestampsBetween(
            String table,
            String timestampColumn,
            ReportContext context
    ) {
        String sql = "select " + timestampColumn + " from " + table
                + " where " + timestampColumn + " >= :startAt and " + timestampColumn + " < :endExclusive"
                + " order by " + timestampColumn;
        return jdbcClient.sql(sql)
                .param("startAt", context.startAt())
                .param("endExclusive", context.endExclusive())
                .query(LocalDateTime.class)
                .list();
    }

    private List<TrendPoint> sumPoints(ReportContext context, List<TimedValue> facts) {
        List<Bucket> buckets = buckets(context);
        Map<String, Long> totals = new LinkedHashMap<>();
        TreeMap<LocalDateTime, Bucket> bucketStarts = new TreeMap<>();
        for (Bucket bucket : buckets) {
            totals.put(bucket.key(), 0L);
            bucketStarts.put(bucket.startAt(), bucket);
        }
        for (TimedValue fact : facts) {
            Map.Entry<LocalDateTime, Bucket> entry = bucketStarts.floorEntry(fact.occurredAt());
            if (entry != null && fact.occurredAt().isBefore(entry.getValue().endExclusive())) {
                totals.compute(entry.getValue().key(), (key, value) -> value + fact.value());
            }
        }
        return buckets.stream()
                .map(bucket -> new TrendPoint(bucket.key(), bucket.label(), totals.get(bucket.key())))
                .toList();
    }

    private List<TrendPoint> countPoints(ReportContext context, List<LocalDateTime> timestamps) {
        return sumPoints(context, timestamps.stream()
                .map(timestamp -> new TimedValue(timestamp, 1L))
                .toList());
    }

    private List<TrendPoint> distinctPoints(ReportContext context, List<TimedKey> facts) {
        List<Bucket> buckets = buckets(context);
        Map<String, Set<String>> distinct = new LinkedHashMap<>();
        TreeMap<LocalDateTime, Bucket> bucketStarts = new TreeMap<>();
        for (Bucket bucket : buckets) {
            distinct.put(bucket.key(), new HashSet<>());
            bucketStarts.put(bucket.startAt(), bucket);
        }
        for (TimedKey fact : facts) {
            Map.Entry<LocalDateTime, Bucket> entry = bucketStarts.floorEntry(fact.occurredAt());
            if (entry != null && fact.occurredAt().isBefore(entry.getValue().endExclusive())) {
                distinct.get(entry.getValue().key()).add(fact.key());
            }
        }
        return buckets.stream()
                .map(bucket -> new TrendPoint(
                        bucket.key(),
                        bucket.label(),
                        (long) distinct.get(bucket.key()).size()
                ))
                .toList();
    }

    private List<TrendPoint> subtractPoints(List<TrendPoint> left, List<TrendPoint> right) {
        Map<String, Long> rightValues = new HashMap<>();
        right.forEach(point -> rightValues.put(point.bucket(), point.value()));
        return left.stream()
                .map(point -> new TrendPoint(
                        point.bucket(),
                        point.label(),
                        point.value() - rightValues.getOrDefault(point.bucket(), 0L)
                ))
                .toList();
    }

    private List<Bucket> buckets(ReportContext context) {
        List<Bucket> buckets = new ArrayList<>();
        LocalDateTime cursor = context.startDate().atStartOfDay();
        LocalDateTime endExclusive = context.endDate().plusDays(1).atStartOfDay();
        while (cursor.isBefore(endExclusive)) {
            LocalDateTime next = endExclusive;
            String key = "";
            String label = "";
            switch (context.granularity()) {
                case HOUR -> {
                    next = cursor.plusHours(1);
                    key = cursor.toString();
                    label = cursor.toLocalDate() + " " + String.format(Locale.ROOT, "%02d:00", cursor.getHour());
                }
                case DAY -> {
                    next = cursor.toLocalDate().plusDays(1).atStartOfDay();
                    key = cursor.toLocalDate().toString();
                    label = cursor.toLocalDate().toString();
                }
                case WEEK -> {
                    next = cursor.plusDays(7);
                    LocalDate lastDate = min(next, endExclusive).minusNanos(1).toLocalDate();
                    key = cursor.toLocalDate() + "/" + lastDate;
                    label = cursor.toLocalDate() + " ~ " + lastDate;
                }
                case MONTH -> {
                    next = YearMonth.from(cursor).plusMonths(1).atDay(1).atStartOfDay();
                    key = YearMonth.from(cursor).toString();
                    label = YearMonth.from(cursor).toString();
                }
                case AUTO -> throw new IllegalStateException("AUTO must be resolved before building buckets");
            }
            LocalDateTime clippedEnd = min(next, endExclusive);
            buckets.add(new Bucket(
                    key,
                    label,
                    TimePolicy.businessWallTimeToUtc(cursor),
                    TimePolicy.businessWallTimeToUtc(clippedEnd)
            ));
            cursor = clippedEnd;
        }
        return buckets;
    }

    private LocalDateTime min(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private CurrentServiceQueue currentServiceQueue() {
        LocalDateTime overdueBefore = LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(48);
        OrderServiceQueue orders = jdbcClient.sql("""
                        select coalesce(sum(case when status in ('PAID', 'PARTIALLY_SHIPPED') then 1 else 0 end), 0) as to_ship_count,
                               coalesce(sum(case when status in ('PAID', 'PARTIALLY_SHIPPED') and paid_at < :overdueBefore then 1 else 0 end), 0) as overdue_count
                        from shop_order
                        """)
                .param("overdueBefore", overdueBefore)
                .query((rs, rowNum) -> new OrderServiceQueue(
                        rs.getLong("to_ship_count"),
                        rs.getLong("overdue_count")
                ))
                .single();
        ConversationQueue conversations = jdbcClient.sql("""
                        select coalesce(sum(case when status = 'WAITING' then 1 else 0 end), 0) as waiting_count,
                               coalesce(sum(case when status = 'ACTIVE' then 1 else 0 end), 0) as active_count,
                               coalesce(sum(case when status in ('WAITING', 'ACTIVE')
                                                 then admin_unread_count else 0 end), 0) as unread_count
                        from customer_service_conversation
                        """)
                .query((rs, rowNum) -> new ConversationQueue(
                        rs.getLong("waiting_count"),
                        rs.getLong("active_count"),
                        rs.getLong("unread_count")
                ))
                .single();
        return new CurrentServiceQueue(
                orders.toShipCount(),
                orders.overdueCount(),
                conversations.waitingCount(),
                conversations.activeCount(),
                conversations.unreadCount()
        );
    }

    private PaymentAttemptAggregate paymentAttemptAggregate(LocalDateTime startAt, LocalDateTime endExclusive) {
        return jdbcClient.sql("""
                        select count(*) as attempt_count,
                               coalesce(sum(case when status = 'PAID' or paid_at is not null then 1 else 0 end), 0) as paid_count
                        from payment_attempt
                        where started_at >= :startAt
                          and started_at < :endExclusive
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query((rs, rowNum) -> new PaymentAttemptAggregate(
                        rs.getLong("attempt_count"),
                        rs.getLong("paid_count")
                ))
                .single();
    }

    private long countUsersCreated(LocalDateTime startAt, LocalDateTime endExclusive) {
        return countBetween("app_user", "created_at", startAt, endExclusive);
    }

    private long countUsersBefore(LocalDateTime endExclusive) {
        return jdbcClient.sql("select count(*) from app_user where created_at < :endExclusive")
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();
    }

    private long countBetween(
            String table,
            String timestampColumn,
            LocalDateTime startAt,
            LocalDateTime endExclusive
    ) {
        String sql = "select count(*) from " + table
                + " where " + timestampColumn + " >= :startAt and " + timestampColumn + " < :endExclusive";
        return jdbcClient.sql(sql)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();
    }

    private long countWhere(String table, String predicate) {
        return jdbcClient.sql("select count(*) from " + table + " where " + predicate)
                .query(Long.class)
                .single();
    }

    private LocalDateTime analyticsCollectionStartedAt() {
        return collectionStartedAt("select min(received_at) as started_at from analytics_event");
    }

    private LocalDateTime activityCollectionStartedAt() {
        return collectionStartedAt("select min(first_active_at) as started_at from app_user_daily_activity");
    }

    private LocalDateTime paymentAttemptCollectionStartedAt() {
        return collectionStartedAt("select min(started_at) as started_at from payment_attempt");
    }

    private LocalDateTime phoneAuthorizationCollectionStartedAt() {
        return collectionStartedAt("select min(phone_authorized_at) as started_at from app_user");
    }

    private LocalDateTime orderCostCollectionStartedAt() {
        return collectionStartedAt("""
                select min(o.paid_at) as started_at
                from order_item oi
                join shop_order o on o.id = oi.order_id
                where oi.line_cost_cent is not null
                  and oi.paid_amount_allocated_cent is not null
                """);
    }

    private LocalDateTime collectionStartedAt(String factMinimumSql) {
        LocalDateTime factStartedAt = minimumTimestamp(factMinimumSql);
        LocalDateTime migrationStartedAt = jdbcClient.sql("""
                        select installed_on
                        from flyway_schema_history
                        where version = '32'
                          and success = true
                        order by installed_rank
                        limit 1
                        """)
                .query(LocalDateTime.class)
                .optional()
                .orElse(null);
        if (factStartedAt == null) {
            return migrationStartedAt;
        }
        if (migrationStartedAt == null || factStartedAt.isBefore(migrationStartedAt)) {
            return factStartedAt;
        }
        return migrationStartedAt;
    }

    private boolean collectionAvailable(LocalDateTime collectionStartedAt, LocalDateTime endExclusive) {
        return collectionStartedAt != null && collectionStartedAt.isBefore(endExclusive);
    }

    private LocalDateTime minimumTimestamp(String sql) {
        return jdbcClient.sql(sql)
                .query((rs, rowNum) -> new NullableTimestamp(
                        rs.getObject("started_at", LocalDateTime.class)))
                .single()
                .value();
    }

    private MetricValue metric(long value, MetricUnit unit) {
        return new MetricValue(value, unit, Availability.AVAILABLE, null, null);
    }

    private MetricValue metric(Long value, Long comparisonValue, MetricUnit unit) {
        return new MetricValue(
                value,
                unit,
                Availability.AVAILABLE,
                comparisonValue,
                changeBasisPoints(value, comparisonValue)
        );
    }

    private MetricValue nullableMetric(Long value, Long comparisonValue, MetricUnit unit) {
        if (value == null) {
            return new MetricValue(null, unit, Availability.NOT_COLLECTED, comparisonValue, null);
        }
        return metric(value, comparisonValue, unit);
    }

    private MetricValue nullableApplicableMetric(Long value, Long comparisonValue, MetricUnit unit) {
        if (value == null) {
            return new MetricValue(null, unit, Availability.NOT_APPLICABLE, comparisonValue, null);
        }
        return metric(value, comparisonValue, unit);
    }

    private MetricValue ratioMetric(
            long numerator,
            long denominator,
            long comparisonNumerator,
            long comparisonDenominator
    ) {
        Long value = basisPoints(numerator, denominator);
        Long comparison = basisPoints(comparisonNumerator, comparisonDenominator);
        return nullableApplicableMetric(value, comparison, MetricUnit.BASIS_POINT);
    }

    private MetricValue ratioMetric(
            long numerator,
            long denominator,
            Long comparisonNumerator,
            Long comparisonDenominator
    ) {
        Long value = basisPoints(numerator, denominator);
        Long comparison = comparisonNumerator == null || comparisonDenominator == null
                ? null
                : basisPoints(comparisonNumerator, comparisonDenominator);
        return nullableApplicableMetric(value, comparison, MetricUnit.BASIS_POINT);
    }

    private MetricValue unavailableMetric(MetricUnit unit) {
        return new MetricValue(null, unit, Availability.NOT_COLLECTED, null, null);
    }

    private Long divideAmount(long amount, long divisor) {
        if (divisor == 0) {
            return null;
        }
        return BigDecimal.valueOf(amount)
                .divide(BigDecimal.valueOf(divisor), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    private Long basisPoints(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    private Long changeBasisPoints(Long current, Long previous) {
        if (current == null || previous == null || previous == 0) {
            return null;
        }
        return BigDecimal.valueOf(current - previous)
                .multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(Math.abs(previous)), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    private <T> DataBlock<T> available(T data) {
        return new DataBlock<>(Availability.AVAILABLE, null, null, data);
    }

    private <T> DataBlock<T> notCollected(String reasonCode, String message, T data) {
        return new DataBlock<>(Availability.NOT_COLLECTED, reasonCode, message, data);
    }

    private <T> DataBlock<List<T>> notCollectedList(String reasonCode) {
        return new DataBlock<>(
                Availability.NOT_COLLECTED,
                reasonCode,
                "该数据从采集启用后开始统计",
                List.of()
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Long roundedLong(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return result.toString();
    }

    private String labelFor(String value) {
        if (value == null || value.isBlank()) {
            return "未知";
        }
        return switch (value) {
            case "CREATED" -> "待付款";
            case "PAYING" -> "支付中";
            case "PAID" -> "待发货";
            case "PARTIALLY_SHIPPED" -> "部分发货";
            case "SHIPPED" -> "已发货";
            case "COMPLETED" -> "已完成";
            case "CLOSED" -> "已关闭";
            case "REFUNDING", "APPROVED" -> "退款中";
            case "REFUNDED", "SUCCESS" -> "已退款";
            case "REQUESTED" -> "待审核";
            case "REJECTED" -> "已拒绝";
            case "REFUND_FAILED", "FAILED" -> "退款失败";
            case "WAITING" -> "待接待";
            case "ACTIVE" -> "服务中";
            case "UPLOADED" -> "上传成功";
            case "UPLOADING" -> "上传中";
            case "SKIPPED" -> "已跳过";
            case "UNAVAILABLE" -> "能力不可用";
            case "UNKNOWN" -> "结果未知";
            case "SELF_CLAIM" -> "用户领取";
            case "ADMIN_ISSUE" -> "后台发放";
            case "CART" -> "购物车";
            case "BUY_NOW" -> "立即购买";
            case "NO_PURCHASE" -> "未购买";
            case "ONE_ORDER" -> "1 单";
            case "TWO_TO_FOUR" -> "2-4 单";
            case "FIVE_PLUS" -> "5 单及以上";
            default -> value;
        };
    }

    private String labelFor(String context, String value) {
        if (context == null || value == null || value.isBlank()) {
            return labelFor(value);
        }
        String contextualLabel = switch (context) {
            case "shop_order.status" -> switch (value) {
                case "CREATED" -> "待付款";
                case "PAYING" -> "支付中";
                case "PAID" -> "待发货";
                case "PARTIALLY_SHIPPED" -> "部分发货";
                case "SHIPPED" -> "已发货";
                case "COMPLETED" -> "已完成";
                case "CLOSED" -> "已关闭";
                case "REFUNDING" -> "退款中";
                case "REFUNDED" -> "已退款";
                default -> null;
            };
            case "payment_order.status" -> switch (value) {
                case "PAYING" -> "支付中";
                case "PAID" -> "支付成功";
                case "CLOSED" -> "已关闭";
                case "FAILED" -> "支付失败";
                default -> null;
            };
            case "refund_order.status" -> switch (value) {
                case "REQUESTED" -> "待退款";
                case "PROCESSING", "REFUNDING" -> "退款中";
                case "SUCCESS" -> "退款成功";
                case "FAILED" -> "退款失败";
                case "CLOSED" -> "已关闭";
                default -> null;
            };
            case "after_sale_request.status" -> switch (value) {
                case "REQUESTED" -> "待审核";
                case "APPROVED" -> "已通过";
                case "REFUNDING" -> "退款中";
                case "REFUNDED" -> "已退款";
                case "REJECTED" -> "已拒绝";
                case "REFUND_FAILED" -> "退款失败";
                default -> null;
            };
            case "order_shipment.wechat_upload_status" -> switch (value) {
                case "UPLOADED" -> "上传成功";
                case "UPLOADING" -> "上传中";
                case "FAILED" -> "上传失败";
                case "SKIPPED" -> "已跳过";
                case "UNAVAILABLE" -> "能力不可用";
                case "UNKNOWN" -> "结果未知";
                default -> null;
            };
            default -> null;
        };
        return contextualLabel == null ? labelFor(value) : contextualLabel;
    }

    private record ReportContext(
            LocalDate startDate,
            LocalDate endDate,
            LocalDate comparisonStartDate,
            LocalDate comparisonEndDate,
            Granularity granularity,
            LocalDateTime startAt,
            LocalDateTime endExclusive,
            LocalDateTime comparisonStartAt,
            LocalDateTime comparisonEndExclusive
    ) {
        private ReportMeta meta(LocalDateTime collectionStartedAt) {
            return new ReportMeta(
                    new DateRange(startDate, endDate),
                    new DateRange(comparisonStartDate, comparisonEndDate),
                    granularity,
                    BUSINESS_TIMEZONE,
                    LocalDateTime.now(java.time.ZoneOffset.UTC),
                    collectionStartedAt
            );
        }
    }

    private record PaidAggregate(long paidOrderCount, long paidBuyerCount, long paidAmountCent,
                                 long couponDiscountCent, long freightCent) {
    }

    private record RefundAggregate(long refundCount, long refundAmountCent) {
    }

    private record CreatedAggregate(long createdOrderCount, long eventuallyPaidCount) {
    }

    private record TradeAggregate(long createdOrderCount, long eventuallyPaidCreatedOrderCount,
                                  long paidOrderCount, long paidBuyerCount, long paidAmountCent,
                                  long refundCount, long refundAmountCent, long netReceiptAmountCent,
                                  long couponDiscountCent, long freightCent) {
    }

    private record TradeDurationAggregate(Long createToPaySeconds, Long payToShipSeconds,
                                          Long shipToCompleteSeconds) {
    }

    private record ProductPaidAggregate(long soldQuantity, long paidItemAmountCent,
                                        long paidOrderCount, long paidBuyerCount,
                                        long coverageBaseAmountCent, long coveredAmountCent,
                                        Long grossProfitAmountCent) {
    }

    private record ProductAggregate(long soldQuantity, long refundedQuantity, long netSoldQuantity,
                                    long paidItemAmountCent, long paidOrderCount, long paidBuyerCount,
                                    long coverageBaseAmountCent, long coveredAmountCent,
                                    Long grossProfitAmountCent) {
    }

    private record CatalogAggregate(long activeSpuCount, long onSaleSpuCount, long enabledSkuCount,
                                    long totalAvailableStock, long outOfStockSkuCount,
                                    long lowStockSkuCount) {
    }

    private record UserAggregate(long newUserCount, long activeUserCount, long paidBuyerCount,
                                 long newPayingBuyerCount, long repeatBuyerCount,
                                 long phoneAuthorizedUserCount) {
    }

    private record TrafficAggregate(long pageViewCount, long visitorCount,
                                    long sessionCount, long loginActiveUserCount) {
    }

    private record CouponOrderAggregate(long discountCent, long paidAmountCent) {
    }

    private record MarketingAggregate(long issuedCouponCount, long usedCouponCount,
                                      long expiredCouponCount, long couponDiscountCent,
                                      long couponPaidAmountCent) {
    }

    private record DurationAggregate(Long shippingSeconds, Long completionSeconds) {
    }

    private record AfterSaleAggregate(long applicationCount, long approvedCount, long rejectedCount,
                                      Long averageReviewSeconds) {
    }

    private record RefundSuccessServiceAggregate(long successCount, long successAmountCent,
                                                 Long averageProcessingSeconds) {
    }

    private record ServiceAggregate(Long averageShippingSeconds, Long averageCompletionSeconds,
                                    long afterSaleApplicationCount, long approvedAfterSaleCount,
                                    long rejectedAfterSaleCount, Long averageAfterSaleReviewSeconds,
                                    long successfulRefundCount, long failedRefundCount,
                                    long successfulRefundAmountCent, Long averageRefundProcessingSeconds) {
    }

    private record ServiceConversationAggregate(long conversationCount, Long averageFirstResponseSeconds,
                                                Long averageResolutionSeconds, long closedConversationCount,
                                                long transferredConversationCount) {
    }

    private record OrderTodoAggregate(long unpaidCount, long toShipCount) {
    }

    private record NamedCount(String name, long count) {
    }

    private record PurchaseSegments(long noPurchase, long oneOrder, long twoToFour, long fivePlus) {
    }

    private record StageCount(String key, String label, long count) {
    }

    private record OrderServiceQueue(long toShipCount, long overdueCount) {
    }

    private record ConversationQueue(long waitingCount, long activeCount, long unreadCount) {
    }

    private record CurrentServiceQueue(long toShipOrderCount, long overdueToShipOrderCount,
                                       long waitingConversationCount, long activeConversationCount,
                                       long adminUnreadMessageCount) {
    }

    private record PaymentAttemptAggregate(long attemptCount, long paidCount) {
    }

    private record PaidTrendFact(LocalDateTime occurredAt, long amountCent, long userId) {
    }

    private record TimedValue(LocalDateTime occurredAt, long value) {
    }

    private record TimedKey(LocalDateTime occurredAt, String key) {
    }

    private record RegisteredUser(long id, LocalDate registeredDate) {
    }

    private record UserActivityDate(long userId, LocalDate activityDate) {
    }

    private record RetentionBucket(String key, LocalDate startDate, LocalDate endDate) {
    }

    private record Bucket(String key, String label, LocalDateTime startAt,
                          LocalDateTime endExclusive) {
    }

    private record HourCount(int hour, long count) {
    }

    private record NullableTimestamp(LocalDateTime value) {
    }
}
