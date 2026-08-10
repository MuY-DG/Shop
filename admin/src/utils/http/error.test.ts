import assert from 'node:assert/strict'
import test from 'node:test'
import { decodeErrorResponse } from './blob-error-response'

test('decodes a JSON business error wrapped as Blob by a download request', async () => {
  const response = { code: 40901, msg: '对账批次版本已变化', data: null }
  const blob = new Blob([JSON.stringify(response)], { type: 'application/json' })

  assert.deepEqual(await decodeErrorResponse(blob), response)
})

test('ignores non-JSON and oversized Blob error bodies', async () => {
  assert.equal(await decodeErrorResponse(new Blob(['not-json'])), null)
  assert.equal(await decodeErrorResponse(new Blob(['x'.repeat(64 * 1024 + 1)])), null)
})
