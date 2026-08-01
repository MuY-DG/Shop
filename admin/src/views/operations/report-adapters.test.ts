import assert from 'node:assert/strict'
import test from 'node:test'

import {
  adaptMarketingReport,
  adaptOverviewReport,
  adaptProductReport,
  adaptTradeReport,
  adaptTrafficReport,
  adaptUserReport
} from './report-adapters'

const meta: Api.Operations.ReportMeta = {
  range: { startDate: '2026-07-15', endDate: '2026-07-15' },
  comparisonRange: { startDate: '2026-07-14', endDate: '2026-07-14' },
  granularity: 'HOUR',
  timezone: 'Asia/Shanghai',
  generatedAt: '2026-07-15T12:00:00Z'
}

test('labels the overview product ranking with its primary count unit', () => {
  const model = adaptOverviewReport({
    meta,
    trade: {},
    users: {},
    todos: { data: [] },
    trend: { data: [] },
    topProducts: { data: [] },
    recentOrders: { data: [] }
  })

  assert.equal(model.lists.find((list) => list.key === 'topProducts')?.valueLabel, '支付件数')
})

test('attaches reliable overview drilldowns without inventing unsupported filters', () => {
  const model = adaptOverviewReport({
    meta,
    trade: {},
    users: {},
    todos: {
      data: [
        { key: 'unpaidOrders', label: '待付款订单', count: 2 },
        { key: 'lowStockSkus', label: '低库存 SKU', count: 1 }
      ]
    },
    trend: { data: [] },
    topProducts: {
      data: [
        {
          id: '92001',
          name: '统计商品',
          subtitle: 'https://cdn.example.com/product.jpg',
          primaryValue: 3,
          primaryUnit: 'COUNT',
          secondaryValue: 9500,
          secondaryUnit: 'CENT'
        }
      ]
    },
    recentOrders: {
      data: [
        {
          orderId: '94001',
          orderNo: 'OPS-ORDER-1',
          userName: '用户 A',
          paidAmountCent: 9500,
          status: 'PAID',
          createdAt: '2026-07-15T12:00:00Z'
        }
      ]
    }
  })

  assert.equal(model.breakdowns[0].kind, 'ACTION_LIST')
  assert.deepEqual(model.breakdowns[0].block.data[0].drilldown, {
    path: '/trade/orders',
    query: { statusGroup: 'UNPAID' }
  })
  assert.equal(model.breakdowns[0].block.data[1].drilldown, undefined)
  assert.deepEqual(model.lists[0].block.data[0].drilldown, {
    path: '/product/spu',
    query: { mode: 'edit', id: '92001' }
  })
  assert.equal(model.lists[0].block.data[0].imageUrl, 'https://cdn.example.com/product.jpg')
  assert.equal(model.lists[0].block.data[0].description, '支付金额 ¥95.00')
  assert.deepEqual(model.lists[1].block.data[0].drilldown, {
    path: '/trade/orders',
    query: { orderNo: 'OPS-ORDER-1' }
  })
})

test('labels the marketing ranking with its primary amount unit', () => {
  const model = adaptMarketingReport({
    meta,
    summary: {},
    trend: { data: [] },
    issueSources: { data: [] },
    templateRanking: { data: [] }
  })

  assert.equal(model.lists.find((list) => list.key === 'templateRanking')?.valueLabel, '优惠金额')
})

test('keeps each ranking secondary value business-specific instead of leaking generic labels', () => {
  const product = adaptProductReport({
    meta,
    summary: {},
    trend: { data: [] },
    topProducts: {
      data: [
        {
          id: '1',
          name: '商品 A',
          subtitle: 'https://cdn.example.com/a.jpg',
          primaryValue: 5,
          primaryUnit: 'COUNT',
          secondaryValue: 1200,
          secondaryUnit: 'CENT'
        }
      ]
    },
    topCategories: {
      data: [
        {
          id: '2',
          name: '分类 A',
          primaryValue: 5,
          primaryUnit: 'COUNT',
          secondaryValue: 1200,
          secondaryUnit: 'CENT'
        }
      ]
    },
    stockAlerts: { data: [] }
  })
  const marketing = adaptMarketingReport({
    meta,
    summary: {},
    trend: { data: [] },
    issueSources: { data: [] },
    templateRanking: {
      data: [
        {
          id: '3',
          name: '新人券',
          subtitle: '发放 10 / 使用 3',
          primaryValue: 500,
          primaryUnit: 'CENT',
          secondaryValue: 3,
          secondaryUnit: 'COUNT'
        }
      ]
    }
  })
  const user = adaptUserReport({
    meta,
    summary: {},
    trend: { data: [] },
    purchaseSegments: { data: [] },
    topCustomers: {
      data: [
        {
          id: '4',
          name: '用户 A',
          primaryValue: 9900,
          primaryUnit: 'CENT',
          secondaryValue: 2,
          secondaryUnit: 'COUNT'
        }
      ]
    },
    retentionCohorts: { data: [] }
  })

  assert.equal(product.lists[0].block.data[0].imageUrl, 'https://cdn.example.com/a.jpg')
  assert.equal(product.lists[0].block.data[0].description, '支付金额 ¥12.00')
  assert.deepEqual(product.lists[0].block.data[0].drilldown, {
    path: '/product/spu',
    query: { mode: 'edit', id: '1' }
  })
  assert.equal(product.lists[1].block.data[0].description, '支付金额 ¥12.00')
  assert.equal(marketing.lists[0].block.data[0].description, '发放 10 / 使用 3')
  assert.equal(user.lists[0].block.data[0].description, '支付订单 2')
})

test('preserves todo severity and marks operational distributions as comparison-friendly bars', () => {
  const overview = adaptOverviewReport({
    meta,
    trade: {},
    users: {},
    todos: {
      data: [{ key: 'failedRefunds', label: '退款失败', count: 2, severity: 'DANGER' }]
    },
    trend: { data: [] },
    topProducts: { data: [] },
    recentOrders: { data: [] }
  })
  const trade = adaptTradeReport({
    meta,
    summary: {},
    trend: { data: [] },
    orderStatuses: { data: [] },
    paymentStatuses: { data: [] },
    refundStatuses: { data: [] },
    orderSources: { data: [] },
    hourlyOrders: { data: [] }
  })

  assert.equal(overview.breakdowns[0].block.data[0].tone, 'DANGER')
  assert.ok(trade.breakdowns.every((section) => section.kind === 'BAR'))
})

test('keeps funnel conversion rates available to the chart layer', () => {
  const model = adaptTrafficReport({
    meta,
    summary: {},
    trend: { data: [] },
    entryScenes: { data: [] },
    topPages: { data: [] },
    topSearches: { data: [] },
    funnel: {
      data: [
        {
          key: 'paid',
          label: '支付用户',
          users: 4,
          conversionRateBasisPoints: 5000
        }
      ]
    }
  })

  assert.equal(model.breakdowns[1].block.data[0].ratioBasisPoints, 5000)
  assert.equal(model.breakdowns[1].kind, 'BAR')
})

test('preserves retention cohort availability and null windows for the user page', () => {
  const retentionCohorts: Api.Operations.DataBlock<Api.Operations.RetentionCohortItem[]> = {
    availability: 'NOT_COLLECTED',
    message: '采集范围尚未完整覆盖',
    data: [
      {
        cohort: '2026-07-15',
        cohortStartDate: '2026-07-15',
        cohortEndDate: '2026-07-15',
        registeredUserCount: 2,
        windows: [
          {
            dayOffset: 1,
            eligibleUserCount: 0,
            retainedUserCount: null,
            retentionRateBasisPoints: null
          }
        ]
      }
    ]
  }
  const model = adaptUserReport({
    meta,
    summary: {},
    trend: { data: [] },
    purchaseSegments: { data: [] },
    topCustomers: { data: [] },
    retentionCohorts
  })

  assert.deepEqual(model.retentionCohorts, retentionCohorts)
})
