import assert from 'node:assert/strict'
import test from 'node:test'
import { parseSellingPoints, serializeSellingPoints } from './editor-model'

test('parses comma, Chinese comma, and newline separated selling points', () => {
  assert.deepEqual(parseSellingPoints(' 新鲜,香辣， 当日发货\n新鲜 '), ['新鲜', '香辣', '当日发货'])
})

test('serializes created selling-point tags into the backend format', () => {
  assert.equal(serializeSellingPoints([' 新鲜 ', '香辣，包邮', '新鲜']), '新鲜,香辣,包邮')
})
