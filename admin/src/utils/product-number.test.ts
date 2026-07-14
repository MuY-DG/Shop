import assert from 'node:assert/strict'
import test from 'node:test'
import { centToYuan, toNullableNumber, yuanToCent } from './product-number'

test('converts between cents and yuan without losing empty optional values', () => {
  assert.equal(centToYuan(1234), 12.34)
  assert.equal(centToYuan(null), null)
  assert.equal(yuanToCent('12.34'), 1234)
  assert.equal(yuanToCent(''), null)
})

test('normalizes optional numeric values', () => {
  assert.equal(toNullableNumber('1.25'), 1.25)
  assert.equal(toNullableNumber(0), 0)
  assert.equal(toNullableNumber(''), null)
  assert.equal(toNullableNumber('not-a-number'), null)
})
