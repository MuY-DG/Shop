import assert from 'node:assert/strict'
import test from 'node:test'

import {
  formatPriceRange,
  toHomeCategoryPayload,
  toHomeProductPayload,
  trimPhone
} from './home-decoration-state'

test('builds category and product payloads without leaking display fields', () => {
  assert.deepEqual(
    toHomeCategoryPayload({ categoryId: 12, imageFileId: 98, sortOrder: 3, status: 'ENABLED' }),
    { categoryId: 12, imageFileId: 98, sortOrder: 3, status: 'ENABLED' }
  )
  assert.deepEqual(
    toHomeProductPayload({
      spuId: 36,
      imageFileId: null,
      sortOrder: 5,
      status: 'DISABLED'
    }),
    {
      spuId: 36,
      imageFileId: null,
      sortOrder: 5,
      status: 'DISABLED'
    }
  )
})

test('formats a single price and a price range in yuan', () => {
  assert.equal(formatPriceRange(1290, 1290), '¥12.90')
  assert.equal(formatPriceRange(1290, 2580), '¥12.90 - ¥25.80')
  assert.equal(formatPriceRange(null, null), '-')
})

test('normalizes the contact phone before saving', () => {
  assert.equal(trimPhone('  400-800-1234  '), '400-800-1234')
})
