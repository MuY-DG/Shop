import assert from 'node:assert/strict'
import test from 'node:test'
import {
  isPersistedCustomerServiceMessageId,
  parseCustomerServiceMessageDate,
  preserveCustomerServiceMessageTimeVisibility,
  requirePersistedCustomerServiceMessageId,
  shouldShowCustomerServiceMessageTime
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

test('客服消息时间遵循带偏移的 API 时间契约', () => {
  const date = parseCustomerServiceMessageDate('2026-08-01T08:27:30.123456Z')
  assert.ok(date)
  assert.equal(date.toISOString(), '2026-08-01T08:27:30.123Z')
  assert.equal(parseCustomerServiceMessageDate('2026-08-01T16:27:30'), null)
})

test('后台乐观消息回填正式 ID 后保留时间分组结果', () => {
  const previous = { consultationNo: 1, createdAt: '2026-08-01T08:20:00Z' }
  const pending = {
    consultationNo: 1,
    createdAt: '2026-08-01T08:27:00Z',
    localShowTime: true
  }
  assert.equal(shouldShowCustomerServiceMessageTime(pending, previous), true)

  const persisted = preserveCustomerServiceMessageTimeVisibility(
    { consultationNo: 1, createdAt: '2026-08-01T08:21:00Z' },
    pending
  )
  assert.equal(shouldShowCustomerServiceMessageTime(persisted, previous), true)
})
