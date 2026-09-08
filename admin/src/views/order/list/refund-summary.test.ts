import assert from 'node:assert/strict'
import test from 'node:test'
import { buildOrderRefundSummary } from './refund-summary'

test('后台订单按累计成功退款区分部分和全部，多次退满后展示全部退款', () => {
  const partial = buildOrderRefundSummary({ paidAmountCent: 10000, refundedAmountCent: 3000 })
  assert.equal(partial?.text, '部分退款')
  assert.equal(partial?.amountCent, 3000)
  const full = buildOrderRefundSummary({ paidAmountCent: 10000, refundedAmountCent: 10000 })
  assert.equal(full?.text, '全部退款')
  assert.equal(full?.amountCent, 10000)
})

test('未实际退款、兼容旧接口和异常金额不会误标为退款成功', () => {
  for (const refundedAmountCent of [undefined, null, 0, -1, Number.NaN, 0.5]) {
    assert.equal(buildOrderRefundSummary({ paidAmountCent: 10000, refundedAmountCent }), null)
  }
  assert.equal(buildOrderRefundSummary({ paidAmountCent: 0, refundedAmountCent: 100 }), null)
})

test('出现新的进行中售后时，已完成的累计部分退款仍可展示', () => {
  const order = {
    paidAmountCent: 10000,
    refundedAmountCent: 3000,
    activeAfterSale: { requestedAmountCent: 7000, status: 'REQUESTED' }
  }
  assert.equal(buildOrderRefundSummary(order)?.text, '部分退款')
  assert.equal(buildOrderRefundSummary(order)?.amountCent, 3000)
})
