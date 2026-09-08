import assert from 'node:assert/strict'
import { test } from 'node:test'
import { buildOrderItemAfterSaleView } from './order-item-after-sale'

const completed = {
  afterSaleId: 71,
  afterSaleNo: 'AS71',
  status: 'REFUNDED',
  quantity: 1,
  amountCent: 1000,
  appVisible: true
}
function item(quantity = 3) {
  return {
    quantity,
    afterSale: {
      refundedQuantity: 1,
      refundedAmountCent: 1000,
      fullyRefunded: false,
      records: [completed]
    }
  }
}

test('全部退款、部分件数退款和仅退部分金额分别表达', () => {
  assert.equal(buildOrderItemAfterSaleView(item()).refundText, '已退款 1/3 件 · ¥10.00')
  const full = item(1)
  full.afterSale.fullyRefunded = true
  assert.equal(buildOrderItemAfterSaleView(full).refundText, '已退款 · ¥10.00')
  full.afterSale.fullyRefunded = false
  full.afterSale.refundedAmountCent = 500
  assert.equal(buildOrderItemAfterSaleView(full).refundText, '部分退款 · ¥5.00')
})

test('新一笔待审核或退款处理中与历史成功退款并列，金额不混入已退', () => {
  for (const [status, text] of [
    ['REQUESTED', '等待商家审核'],
    ['REFUNDING', '退款处理中'],
    ['REFUND_FAILED', '退款待处理']
  ]) {
    const value = item()
    value.afterSale.records.unshift({
      ...completed,
      afterSaleId: 72,
      status: status!,
      amountCent: 2000
    })
    const view = buildOrderItemAfterSaleView(value)
    assert.equal(view.refundText, '已退款 1/3 件 · ¥10.00')
    assert.equal(view.activeText, `1 件${text}`)
  }
})

test('没有成功退款的商品不显示已退款，原购买数量保持不变', () => {
  assert.equal(buildOrderItemAfterSaleView({ quantity: 2 }).refundText, '')
  const value = item()
  value.afterSale.refundedAmountCent = 0
  value.afterSale.refundedQuantity = 0
  value.afterSale.records = [{ ...completed, status: 'REQUESTED' }]
  assert.equal(buildOrderItemAfterSaleView(value).refundText, '')
  assert.equal(value.quantity, 3)
})
