import assert from 'node:assert/strict'
import test from 'node:test'

import {
  afterSaleStatusGroupFromQuery,
  customerServiceStatusFromQuery,
  orderStatusGroupFromQuery
} from '@/utils/business-route-query'
import { productDrilldown, recentOrderDrilldown, todoDrilldown } from './operations-drilldown'

test('maps supported overview todos to real business-list filters', () => {
  assert.deepEqual(todoDrilldown('unpaidOrders'), {
    path: '/trade/orders',
    query: { statusGroup: 'UNPAID' }
  })
  assert.deepEqual(todoDrilldown('toShipOrders'), {
    path: '/trade/orders',
    query: { statusGroup: 'TO_SHIP' }
  })
  assert.deepEqual(todoDrilldown('pendingAfterSales'), {
    path: '/trade/after-sales',
    query: { statusGroup: 'PENDING_REVIEW' }
  })
  assert.deepEqual(todoDrilldown('failedRefunds'), {
    path: '/trade/after-sales',
    query: { statusGroup: 'REFUND_FAILED' }
  })
  assert.deepEqual(todoDrilldown('waitingConversations'), {
    path: '/customer-service',
    query: { status: 'WAITING' }
  })
})

test('does not invent unsupported business-list filters', () => {
  assert.equal(todoDrilldown('wechatShippingFailures'), undefined)
  assert.equal(todoDrilldown('lowStockSkus'), undefined)
  assert.equal(todoDrilldown('unknownTodo'), undefined)
})

test('builds exact product and order drilldowns only from valid identifiers', () => {
  assert.deepEqual(productDrilldown('92001'), {
    path: '/product/spu',
    query: { mode: 'edit', id: '92001' }
  })
  assert.equal(productDrilldown('not-a-product-id'), undefined)
  assert.equal(productDrilldown('9007199254740993'), undefined)
  assert.deepEqual(recentOrderDrilldown(' OPS-ORDER-1 '), {
    path: '/trade/orders',
    query: { orderNo: 'OPS-ORDER-1' }
  })
  assert.equal(recentOrderDrilldown('   '), undefined)
})

test('business pages accept only their documented route-query filters', () => {
  assert.equal(orderStatusGroupFromQuery('UNPAID'), 'UNPAID')
  assert.equal(orderStatusGroupFromQuery(['TO_SHIP', 'UNPAID']), 'TO_SHIP')
  assert.equal(orderStatusGroupFromQuery('FAILED'), undefined)

  assert.equal(afterSaleStatusGroupFromQuery('PENDING_REVIEW'), 'PENDING_REVIEW')
  assert.equal(afterSaleStatusGroupFromQuery('REFUND_FAILED'), 'REFUND_FAILED')
  assert.equal(afterSaleStatusGroupFromQuery('FAILED'), undefined)

  assert.equal(customerServiceStatusFromQuery('WAITING'), 'WAITING')
  assert.equal(customerServiceStatusFromQuery('DRAFT'), undefined)
  assert.equal(customerServiceStatusFromQuery('UNKNOWN'), undefined)
})
