import {
  formatUnitValue,
  type OperationDrilldownTarget,
  type OperationListItem,
  type OperationPageModel
} from './operations-state'
import { productDrilldown, recentOrderDrilldown, todoDrilldown } from './operations-drilldown'

const statusLabels: Record<string, string> = {
  CREATED: '已创建',
  PENDING_PAYMENT: '待付款',
  PAID: '已支付',
  TO_SHIP: '待发货',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  CANCELLED: '已取消',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
  SUCCESS: '成功',
  FAILED: '失败',
  PROCESSING: '处理中'
}

function mapBlock<T, U>(
  block: Api.Operations.DataBlock<T[]>,
  mapper: (item: T) => U
): Api.Operations.DataBlock<U[]> {
  return { ...block, data: block.data.map(mapper) }
}

interface RankingPresentation {
  resolveDrilldown?: (item: Api.Operations.RankingItem) => OperationDrilldownTarget | undefined
  secondaryLabel?: string
  subtitleKind?: 'TEXT' | 'IMAGE'
  includeSecondary?: boolean
}

function rankingDescription(
  item: Api.Operations.RankingItem,
  presentation: RankingPresentation
): string | null {
  const details: string[] = []
  if (presentation.subtitleKind !== 'IMAGE' && item.subtitle) details.push(item.subtitle)
  if (
    presentation.includeSecondary !== false &&
    presentation.secondaryLabel &&
    item.secondaryValue !== null &&
    item.secondaryValue !== undefined &&
    item.secondaryUnit
  ) {
    details.push(
      `${presentation.secondaryLabel} ${formatUnitValue(item.secondaryValue, item.secondaryUnit)}`
    )
  }
  return details.join(' · ') || null
}

function rankingBlock(
  block: Api.Operations.DataBlock<Api.Operations.RankingItem[]>,
  presentation: RankingPresentation = {}
): Api.Operations.DataBlock<OperationListItem[]> {
  return mapBlock(block, (item) => {
    const drilldown = presentation.resolveDrilldown?.(item)
    return {
      id: item.id,
      title: item.name,
      description: rankingDescription(item, presentation),
      imageUrl: presentation.subtitleKind === 'IMAGE' ? item.subtitle : undefined,
      value: item.primaryValue,
      unit: item.primaryUnit,
      ...(drilldown ? { drilldown } : {})
    }
  })
}

function todoBreakdown(
  block: Api.Operations.DataBlock<Api.Operations.TodoItem[]>
): Api.Operations.DataBlock<Api.Operations.BreakdownItem[]> {
  return mapBlock(block, (item) => {
    const drilldown = todoDrilldown(item.key)
    return {
      key: item.key,
      label: item.label,
      value: item.count,
      tone: item.severity,
      ...(drilldown ? { drilldown } : {})
    }
  })
}

function recentOrderBlock(
  block: Api.Operations.DataBlock<Api.Operations.RecentOrderItem[]>
): Api.Operations.DataBlock<OperationListItem[]> {
  return mapBlock(block, (item) => {
    const drilldown = recentOrderDrilldown(item.orderNo)
    return {
      id: item.orderId,
      title: item.orderNo,
      description: `${item.userName} · ${item.createdAt.replace('T', ' ')}`,
      value: item.paidAmountCent,
      unit: 'CENT',
      tag: statusLabels[item.status] || item.status,
      tone: item.status === 'FAILED' || item.status === 'CANCELLED' ? 'DANGER' : 'INFO',
      ...(drilldown ? { drilldown } : {})
    }
  })
}

function trendPointBreakdown(
  block: Api.Operations.DataBlock<Api.Operations.TrendPoint[]>
): Api.Operations.DataBlock<Api.Operations.BreakdownItem[]> {
  return mapBlock(block, (item) => ({
    key: item.bucket,
    label: item.label,
    value: item.value ?? 0
  }))
}

function alertBlock(
  block: Api.Operations.DataBlock<Api.Operations.AlertItem[]>
): Api.Operations.DataBlock<OperationListItem[]> {
  return mapBlock(block, (item) => ({
    id: item.id,
    title: item.name,
    description: item.detail,
    value: item.value,
    unit: item.unit,
    tag: item.severity === 'DANGER' ? '严重' : item.severity === 'WARNING' ? '预警' : '提示',
    tone: item.severity
  }))
}

function funnelBlock(
  block: Api.Operations.DataBlock<Api.Operations.FunnelStage[]>
): Api.Operations.DataBlock<Api.Operations.BreakdownItem[]> {
  return mapBlock(block, (item) => ({
    key: item.key,
    label: item.label,
    value: item.users,
    ratioBasisPoints: item.conversionRateBasisPoints
  }))
}

function agentLoadBlock(
  block: Api.Operations.DataBlock<Api.Operations.AgentLoadItem[]>
): Api.Operations.DataBlock<OperationListItem[]> {
  return mapBlock(block, (item) => ({
    id: item.adminUserId,
    title: item.displayName,
    description: [
      `已关闭 ${item.closedConversations}`,
      item.firstResponseSeconds === null || item.firstResponseSeconds === undefined
        ? null
        : `首响 ${formatUnitValue(item.firstResponseSeconds, 'SECOND')}`
    ]
      .filter(Boolean)
      .join(' · '),
    value: item.activeConversations,
    unit: 'COUNT',
    tag: item.activeConversations > 0 ? '服务中' : null,
    tone: 'INFO'
  }))
}

export function adaptOverviewReport(report: Api.Operations.OverviewReport): OperationPageModel {
  return {
    meta: report.meta,
    metrics: { ...report.trade, ...report.users },
    trend: report.trend,
    breakdowns: [
      {
        key: 'todos',
        title: '当前运营待办',
        kind: 'ACTION_LIST',
        block: todoBreakdown(report.todos)
      }
    ],
    lists: [
      {
        key: 'topProducts',
        title: '热销商品',
        valueLabel: '支付件数',
        block: rankingBlock(report.topProducts, {
          resolveDrilldown: (item) => productDrilldown(item.id),
          secondaryLabel: '支付金额',
          subtitleKind: 'IMAGE'
        })
      },
      {
        key: 'recentOrders',
        title: '最近成交订单',
        valueLabel: '实付金额',
        block: recentOrderBlock(report.recentOrders)
      }
    ]
  }
}

export function adaptTradeReport(report: Api.Operations.TradeStatisticsReport): OperationPageModel {
  return {
    meta: report.meta,
    metrics: report.summary,
    trend: report.trend,
    breakdowns: [
      { key: 'orderStatuses', title: '订单状态', kind: 'BAR', block: report.orderStatuses },
      { key: 'paymentStatuses', title: '支付状态', kind: 'BAR', block: report.paymentStatuses },
      { key: 'refundStatuses', title: '退款状态', kind: 'BAR', block: report.refundStatuses },
      { key: 'orderSources', title: '订单来源', kind: 'BAR', block: report.orderSources },
      {
        key: 'hourlyOrders',
        title: '支付订单小时分布',
        kind: 'BAR',
        block: trendPointBreakdown(report.hourlyOrders)
      }
    ],
    lists: []
  }
}

export function adaptProductReport(
  report: Api.Operations.ProductStatisticsReport
): OperationPageModel {
  return {
    meta: report.meta,
    metrics: report.summary,
    trend: report.trend,
    breakdowns: [],
    lists: [
      {
        key: 'topProducts',
        title: '商品经营排行',
        valueLabel: '支付件数',
        block: rankingBlock(report.topProducts, {
          resolveDrilldown: (item) => productDrilldown(item.id),
          secondaryLabel: '支付金额',
          subtitleKind: 'IMAGE'
        })
      },
      {
        key: 'topCategories',
        title: '分类经营排行',
        valueLabel: '支付件数',
        block: rankingBlock(report.topCategories, { secondaryLabel: '支付金额' })
      },
      {
        key: 'stockAlerts',
        title: '库存预警',
        valueLabel: '可用库存',
        block: alertBlock(report.stockAlerts)
      }
    ]
  }
}

export function adaptUserReport(report: Api.Operations.UserStatisticsReport): OperationPageModel {
  return {
    meta: report.meta,
    metrics: report.summary,
    trend: report.trend,
    breakdowns: [
      { key: 'purchaseSegments', title: '购买次数分层', block: report.purchaseSegments }
    ],
    lists: [
      {
        key: 'topCustomers',
        title: '用户价值排行',
        valueLabel: '支付金额',
        block: rankingBlock(report.topCustomers, { secondaryLabel: '支付订单' })
      }
    ],
    retentionCohorts: report.retentionCohorts
  }
}

export function adaptTrafficReport(
  report: Api.Operations.TrafficStatisticsReport
): OperationPageModel {
  return {
    meta: report.meta,
    metrics: report.summary,
    trend: report.trend,
    breakdowns: [
      { key: 'entryScenes', title: '入口场景', kind: 'BAR', block: report.entryScenes },
      { key: 'funnel', title: '用户转化漏斗', kind: 'BAR', block: funnelBlock(report.funnel) }
    ],
    lists: [
      {
        key: 'topPages',
        title: '热门页面',
        valueLabel: '浏览量',
        block: rankingBlock(report.topPages, { secondaryLabel: '访客数' })
      },
      {
        key: 'topSearches',
        title: '热门搜索词',
        valueLabel: '搜索次数',
        block: rankingBlock(report.topSearches, { secondaryLabel: '访客数' })
      }
    ]
  }
}

export function adaptMarketingReport(
  report: Api.Operations.MarketingStatisticsReport
): OperationPageModel {
  return {
    meta: report.meta,
    metrics: report.summary,
    trend: report.trend,
    breakdowns: [{ key: 'issueSources', title: '优惠券发放来源', block: report.issueSources }],
    lists: [
      {
        key: 'templateRanking',
        title: '优惠券模板排行',
        valueLabel: '优惠金额',
        block: rankingBlock(report.templateRanking, { includeSecondary: false })
      }
    ]
  }
}

export function adaptServiceReport(
  report: Api.Operations.ServiceStatisticsReport
): OperationPageModel {
  return {
    meta: report.meta,
    metrics: report.summary,
    trend: report.trend,
    breakdowns: [
      {
        key: 'shippingCompanies',
        title: '物流公司分布',
        kind: 'BAR',
        block: report.shippingCompanies
      },
      {
        key: 'wechatShippingStatuses',
        title: '微信发货上传结果',
        kind: 'BAR',
        block: report.wechatShippingStatuses
      },
      {
        key: 'afterSaleStatuses',
        title: '售后状态',
        kind: 'BAR',
        block: report.afterSaleStatuses
      },
      { key: 'refundReasons', title: '退款原因', kind: 'BAR', block: report.refundReasons }
    ],
    lists: [
      {
        key: 'agentLoads',
        title: '客服坐席负载',
        valueLabel: '进行中会话',
        block: agentLoadBlock(report.agentLoads)
      }
    ]
  }
}
