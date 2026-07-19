package org.muybaby.shopserver.operation.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class OperationsStatisticsDtos {

    private OperationsStatisticsDtos() {
    }

    public enum Granularity {
        AUTO,
        HOUR,
        DAY,
        WEEK,
        MONTH
    }

    public enum MetricUnit {
        COUNT,
        CENT,
        BASIS_POINT,
        SECOND
    }

    public enum Availability {
        AVAILABLE,
        NOT_COLLECTED,
        NOT_APPLICABLE,
        DELAYED
    }

    public record ReportQuery(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Granularity granularity
    ) {
    }

    public record DateRange(LocalDate startDate, LocalDate endDate) {
    }

    public record ReportMeta(
            DateRange range,
            DateRange comparisonRange,
            Granularity granularity,
            String timezone,
            LocalDateTime generatedAt,
            LocalDateTime collectionStartedAt
    ) {
    }

    public record MetricValue(
            Long value,
            MetricUnit unit,
            Availability availability,
            Long comparisonValue,
            Long changeRateBasisPoints
    ) {
    }

    public record DataBlock<T>(
            Availability availability,
            String reasonCode,
            String message,
            T data
    ) {
    }

    public record TrendPoint(String bucket, String label, Long value) {
    }

    public record TrendSeries(
            String key,
            String name,
            MetricUnit unit,
            List<TrendPoint> points
    ) {
    }

    public record BreakdownItem(
            String key,
            String label,
            long value,
            Long ratioBasisPoints
    ) {
    }

    public record RankingItem(
            String id,
            String name,
            String subtitle,
            Long primaryValue,
            MetricUnit primaryUnit,
            Long secondaryValue,
            MetricUnit secondaryUnit
    ) {
    }

    public record TodoItem(String key, String label, long count, String severity) {
    }

    public record RecentOrderItem(
            String orderId,
            String orderNo,
            String userName,
            long paidAmountCent,
            String status,
            LocalDateTime createdAt
    ) {
    }

    public record AlertItem(
            String id,
            String name,
            String detail,
            String severity,
            Long value,
            MetricUnit unit
    ) {
    }

    public record FunnelStage(
            String key,
            String label,
            long users,
            Long conversionRateBasisPoints
    ) {
    }

    public record AgentLoadItem(
            String adminUserId,
            String displayName,
            long activeConversations,
            long closedConversations,
            Long firstResponseSeconds
    ) {
    }

    public record RetentionWindow(
            int dayOffset,
            long eligibleUserCount,
            Long retainedUserCount,
            Long retentionRateBasisPoints
    ) {
    }

    public record RetentionCohortItem(
            String cohort,
            LocalDate cohortStartDate,
            LocalDate cohortEndDate,
            long registeredUserCount,
            List<RetentionWindow> windows
    ) {
    }

    public record OverviewReport(
            ReportMeta meta,
            Map<String, MetricValue> trade,
            Map<String, MetricValue> users,
            DataBlock<List<TodoItem>> todos,
            DataBlock<List<TrendSeries>> trend,
            DataBlock<List<RankingItem>> topProducts,
            DataBlock<List<RecentOrderItem>> recentOrders
    ) {
    }

    public record TradeStatisticsReport(
            ReportMeta meta,
            Map<String, MetricValue> summary,
            DataBlock<List<TrendSeries>> trend,
            DataBlock<List<BreakdownItem>> orderStatuses,
            DataBlock<List<BreakdownItem>> paymentStatuses,
            DataBlock<List<BreakdownItem>> refundStatuses,
            DataBlock<List<BreakdownItem>> orderSources,
            DataBlock<List<TrendPoint>> hourlyOrders
    ) {
    }

    public record ProductStatisticsReport(
            ReportMeta meta,
            Map<String, MetricValue> summary,
            DataBlock<List<TrendSeries>> trend,
            DataBlock<List<RankingItem>> topProducts,
            DataBlock<List<RankingItem>> topCategories,
            DataBlock<List<AlertItem>> stockAlerts
    ) {
    }

    public record UserStatisticsReport(
            ReportMeta meta,
            Map<String, MetricValue> summary,
            DataBlock<List<TrendSeries>> trend,
            DataBlock<List<BreakdownItem>> purchaseSegments,
            DataBlock<List<RankingItem>> topCustomers,
            DataBlock<List<RetentionCohortItem>> retentionCohorts
    ) {
    }

    public record TrafficStatisticsReport(
            ReportMeta meta,
            Map<String, MetricValue> summary,
            DataBlock<List<TrendSeries>> trend,
            DataBlock<List<BreakdownItem>> entryScenes,
            DataBlock<List<RankingItem>> topPages,
            DataBlock<List<RankingItem>> topSearches,
            DataBlock<List<FunnelStage>> funnel
    ) {
    }

    public record MarketingStatisticsReport(
            ReportMeta meta,
            Map<String, MetricValue> summary,
            DataBlock<List<TrendSeries>> trend,
            DataBlock<List<BreakdownItem>> issueSources,
            DataBlock<List<RankingItem>> templateRanking
    ) {
    }

    public record ServiceStatisticsReport(
            ReportMeta meta,
            Map<String, MetricValue> summary,
            DataBlock<List<TrendSeries>> trend,
            DataBlock<List<BreakdownItem>> shippingCompanies,
            DataBlock<List<BreakdownItem>> wechatShippingStatuses,
            DataBlock<List<BreakdownItem>> afterSaleStatuses,
            DataBlock<List<BreakdownItem>> refundReasons,
            DataBlock<List<AgentLoadItem>> agentLoads
    ) {
    }
}
