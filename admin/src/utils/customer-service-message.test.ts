import assert from 'node:assert/strict'
import test from 'node:test'
import {
  isPersistedCustomerServiceMessageId,
  requirePersistedCustomerServiceMessageId
} from './customer-service-message'

test('客服图片接口只接受服务端持久化后的正整数消息 ID', () => {
  assert.equal(isPersistedCustomerServiceMessageId(1), true)
  assert.equal(isPersistedCustomerServiceMessageId(Number.MAX_SAFE_INTEGER), true)
  assert.equal(isPersistedCustomerServiceMessageId(-1), false)
  assert.equal(isPersistedCustomerServiceMessageId(0), false)
  assert.equal(isPersistedCustomerServiceMessageId(1.5), false)
  assert.equal(isPersistedCustomerServiceMessageId('1'), false)

  assert.equal(requirePersistedCustomerServiceMessageId(42), 42)
  assert.throws(() => requirePersistedCustomerServiceMessageId(-1), RangeError)
})
