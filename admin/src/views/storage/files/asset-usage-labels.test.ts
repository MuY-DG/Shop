import assert from 'node:assert/strict'
import test from 'node:test'
import { formatAssetUsageOwnerType, formatAssetUsageType } from './asset-usage-labels'

test('formats known asset usage and owner types in Chinese', () => {
  assert.equal(formatAssetUsageType('PRODUCT_SPU_MAIN'), '商品主图')
  assert.equal(formatAssetUsageType('ORDER_ITEM_SNAPSHOT'), '订单商品快照')
  assert.equal(formatAssetUsageOwnerType('PRODUCT_SPU'), '商品')
  assert.equal(formatAssetUsageOwnerType('ORDER_ITEM'), '订单商品项')
})

test('keeps unknown future types visible and handles empty values', () => {
  assert.equal(formatAssetUsageType('FUTURE_USAGE'), 'FUTURE_USAGE')
  assert.equal(formatAssetUsageOwnerType('FUTURE_OWNER'), 'FUTURE_OWNER')
  assert.equal(formatAssetUsageType(), '-')
})
