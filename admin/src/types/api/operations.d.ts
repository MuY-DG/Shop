declare namespace Api {
  namespace Operations {
    type Granularity = 'AUTO' | 'HOUR' | 'DAY' | 'WEEK' | 'MONTH'
    type MetricUnit = 'COUNT' | 'CENT' | 'BASIS_POINT' | 'SECOND'
    type Availability = 'AVAILABLE' | 'NOT_COLLECTED' | 'NOT_APPLICABLE' | 'DELAYED'

    interface ReportQuery {
      startDate?: string
      endDate?: string
      granularity?: Granularity
    }

    interface DateRange {
      startDate: string
      endDate: string
    }

    interface ReportMeta {
      range: DateRange
      comparisonRange: DateRange
      granularity: Exclude<Granularity, 'AUTO'>
      timezone: string
      generatedAt: string
      collectionStartedAt?: string | null
    }

    interface MetricValue {
      value?: number | null
      unit: MetricUnit
      availability?: Availability
      comparisonValue?: number | null
      changeRateBasisPoints?: number | null
    }

    type MetricGroup = Record<string, MetricValue>

    interface DataBlock<T> {
      availability?: Availability
      reasonCode?: string | null
      message?: string | null
      data: T
    }

    interface TrendPoint {
      bucket: string
      label: string
      value?: number | null
    }

    interface TrendSeries {
      key: string
      name: string
      unit: MetricUnit
      points: TrendPoint[]
    }

    interface BreakdownItem {
      key: string
      label: string
      value: number
      ratioBasisPoints?: number | null
    }

    interface RankingItem {
      id: string
      name: string
      subtitle?: string | null
      primaryValue?: number | null
      primaryUnit: MetricUnit
      secondaryValue?: number | null
      secondaryUnit?: MetricUnit
    }

    interface TodoItem {
      key: string
      label: string
      count: number
      severity?: 'INFO' | 'WARNING' | 'DANGER'
    }

    interface RecentOrderItem {
      orderId: string
      orderNo: string
      userName: string
      paidAmountCent: number
      status: string
      createdAt: string
    }

    interface AlertItem {
      id: string
      name: string
      detail?: string | null
      severity?: 'INFO' | 'WARNING' | 'DANGER'
      value?: number | null
      unit?: MetricUnit
    }

    interface FunnelStage {
      key: string
      label: string
      users: number
      conversionRateBasisPoints?: number | null
    }

    interface AgentLoadItem {
      adminUserId: string
      displayName: string
      activeConversations: number
      closedConversations: number
      firstResponseSeconds?: number | null
    }

    interface RetentionWindow {
      dayOffset: 1 | 7 | 30
      eligibleUserCount: number
      retainedUserCount?: number | null
      retentionRateBasisPoints?: number | null
    }

    interface RetentionCohortItem {
      cohort: string
      cohortStartDate: string
      cohortEndDate: string
      registeredUserCount: number
      windows: RetentionWindow[]
    }

    interface OverviewReport {
      meta: ReportMeta
      trade: MetricGroup
      users: MetricGroup
      todos: DataBlock<TodoItem[]>
      trend: DataBlock<TrendSeries[]>
      topProducts: DataBlock<RankingItem[]>
      recentOrders: DataBlock<RecentOrderItem[]>
    }

    interface TradeStatisticsReport {
      meta: ReportMeta
      summary: MetricGroup
      trend: DataBlock<TrendSeries[]>
      orderStatuses: DataBlock<BreakdownItem[]>
      paymentStatuses: DataBlock<BreakdownItem[]>
      refundStatuses: DataBlock<BreakdownItem[]>
      orderSources: DataBlock<BreakdownItem[]>
      hourlyOrders: DataBlock<TrendPoint[]>
    }

    interface ProductStatisticsReport {
      meta: ReportMeta
      summary: MetricGroup
      trend: DataBlock<TrendSeries[]>
      topProducts: DataBlock<RankingItem[]>
      topCategories: DataBlock<RankingItem[]>
      stockAlerts: DataBlock<AlertItem[]>
    }

    interface UserStatisticsReport {
      meta: ReportMeta
      summary: MetricGroup
      trend: DataBlock<TrendSeries[]>
      purchaseSegments: DataBlock<BreakdownItem[]>
      topCustomers: DataBlock<RankingItem[]>
      retentionCohorts: DataBlock<RetentionCohortItem[]>
    }

    interface TrafficStatisticsReport {
      meta: ReportMeta
      summary: MetricGroup
      trend: DataBlock<TrendSeries[]>
      entryScenes: DataBlock<BreakdownItem[]>
      topPages: DataBlock<RankingItem[]>
      topSearches: DataBlock<RankingItem[]>
      funnel: DataBlock<FunnelStage[]>
    }

    interface MarketingStatisticsReport {
      meta: ReportMeta
      summary: MetricGroup
      trend: DataBlock<TrendSeries[]>
      issueSources: DataBlock<BreakdownItem[]>
      templateRanking: DataBlock<RankingItem[]>
    }

    interface ServiceStatisticsReport {
      meta: ReportMeta
      summary: MetricGroup
      trend: DataBlock<TrendSeries[]>
      shippingCompanies: DataBlock<BreakdownItem[]>
      wechatShippingStatuses: DataBlock<BreakdownItem[]>
      afterSaleStatuses: DataBlock<BreakdownItem[]>
      refundReasons: DataBlock<BreakdownItem[]>
      agentLoads: DataBlock<AgentLoadItem[]>
    }
  }
}
