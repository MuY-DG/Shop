import assert from 'node:assert/strict'
import test from 'node:test'

import {
  describeWechatShippingCapabilityFailure,
  disabledWechatShippingRuntimeUpdate,
  isWechatShippingCapabilityAvailable
} from './wechat-shipping-runtime-state'

const capability = (
  overrides: Partial<Api.Order.WechatShippingCapability> = {}
): Api.Order.WechatShippingCapability => ({
  uploadEnabled: true,
  providerMode: 'REAL',
  state: 'AVAILABLE',
  tradeManaged: true,
  errorCode: null,
  errorMessage: null,
  checkedAt: '2026-09-04T10:00:00Z',
  ...overrides
})

test('only treats an enabled real trade-managed capability as available', () => {
  assert.equal(isWechatShippingCapabilityAvailable(capability()), true)
  assert.equal(isWechatShippingCapabilityAvailable(capability({ uploadEnabled: false })), false)
  assert.equal(isWechatShippingCapabilityAvailable(capability({ providerMode: 'MOCK' })), false)
  assert.equal(isWechatShippingCapabilityAvailable(capability({ state: 'UNKNOWN' })), false)
  assert.equal(isWechatShippingCapabilityAvailable(capability({ tradeManaged: false })), false)
})

test('explains known unavailable and unknown capability results in Chinese', () => {
  assert.equal(
    describeWechatShippingCapabilityFailure(
      capability({
        state: 'UNAVAILABLE',
        tradeManaged: false,
        errorCode: 'TRADE_NOT_MANAGED'
      })
    ),
    '小程序尚未接入微信发货信息管理服务'
  )
  assert.equal(
    describeWechatShippingCapabilityFailure(
      capability({ state: 'UNAVAILABLE', tradeManaged: null, errorCode: 'WECHAT_48001' })
    ),
    '微信接口未授权，请先开通发货信息管理服务'
  )
  assert.equal(
    describeWechatShippingCapabilityFailure(
      capability({ state: 'UNKNOWN', tradeManaged: null, errorCode: 'REQUEST_AMBIGUOUS' })
    ),
    '微信接口请求失败，当前状态无法确认'
  )
})

test('builds a revision-aware update that closes the parent and dependent switches', () => {
  assert.deepEqual(disabledWechatShippingRuntimeUpdate(8), {
    uploadEnabled: false,
    deliveryEnabled: false,
    receiptReconciliationEnabled: false,
    version: 8
  })
})
