import assert from 'node:assert/strict'
import test from 'node:test'
import { formatProductSearchMatches } from './product-search'

test('商品搜索说明区分停用、缺货和只有 SKU 编码的匹配', () => {
  assert.equal(formatProductSearchMatches(), '')
  assert.equal(
    formatProductSearchMatches([
      {
        skuId: 1,
        specText: '牛油 500g',
        skuCode: 'BEEF',
        status: 'DISABLED',
        saleState: 'AVAILABLE'
      },
      { skuId: 2, specText: '', skuCode: 'TOMATO', status: 'ENABLED', saleState: 'SOLD_OUT' }
    ]),
    '相关规格：牛油 500g（已停用）；TOMATO（缺货）'
  )
})
