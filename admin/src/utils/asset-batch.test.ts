import assert from 'node:assert/strict'
import test from 'node:test'
import {
  appendUniqueAssetValues,
  ASSET_UPLOAD_CONCURRENCY,
  settleWithConcurrency
} from './asset-batch'

test('asset uploads use the bounded production concurrency', () => {
  assert.equal(ASSET_UPLOAD_CONCURRENCY, 2)
})

test('appendUniqueAssetValues preserves order, removes duplicates, and enforces the limit', () => {
  const values = appendUniqueAssetValues(
    [
      { fileId: 1, url: 'one' },
      { fileId: null, url: '' }
    ],
    [
      { fileId: 1, url: 'one-new-snapshot' },
      { fileId: 2, url: 'two' },
      { fileId: 3, url: 'three' }
    ],
    3
  )

  assert.deepEqual(values, [
    { fileId: 1, url: 'one' },
    { fileId: 2, url: 'two' },
    { fileId: 3, url: 'three' }
  ])
})

test('settleWithConcurrency bounds active work and preserves input result order', async () => {
  let active = 0
  let maxActive = 0
  const results = await settleWithConcurrency([30, 10, 20, 5], 2, async (delay) => {
    active += 1
    maxActive = Math.max(maxActive, active)
    await new Promise((resolve) => setTimeout(resolve, delay))
    active -= 1
    if (delay === 20) throw new Error('expected failure')
    return delay
  })

  assert.equal(maxActive, 2)
  assert.deepEqual(
    results.map((result) => result.status),
    ['fulfilled', 'fulfilled', 'rejected', 'fulfilled']
  )
  assert.equal(results[0].status === 'fulfilled' ? results[0].value : null, 30)
})
