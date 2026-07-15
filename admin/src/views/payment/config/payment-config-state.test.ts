import assert from 'node:assert/strict'
import test from 'node:test'

import { environmentConfigUseState, paymentConfigUseState } from './payment-config-state'

const config = (overrides: Partial<Api.Payment.Config> = {}): Api.Payment.Config => ({
  id: 7,
  source: 'DB',
  configName: '正式环境',
  appIdMasked: 'wx_***_123',
  mchIdMasked: '12***34',
  merchantSerialNoMasked: 'ABC***XYZ',
  apiV3KeyConfigured: true,
  privateKeyFileId: 1,
  merchantCertificateFileId: null,
  verifyMode: 'PUBLIC_KEY',
  wechatPublicKeyIdMasked: 'PUB***KEY',
  wechatPublicKeyFileId: 2,
  notifyUrl: 'https://example.com/wxpay/pay/notify',
  refundNotifyUrl: 'https://example.com/wxpay/refund/notify',
  enabled: true,
  status: 'ACTIVE',
  ...overrides
})

const effective = (
  overrides: Partial<Api.Payment.EffectiveConfig> = {}
): Api.Payment.EffectiveConfig => ({
  ...config(),
  ...overrides
})

test('the effective DB config is marked as in use and cannot be selected again', () => {
  assert.deepEqual(paymentConfigUseState(config(), effective()), {
    active: true,
    disabled: true,
    label: '正在使用'
  })
})

test('an enabled DB candidate is still selectable when ENV is actually effective', () => {
  assert.deepEqual(paymentConfigUseState(config(), effective({ id: null, source: 'ENV' })), {
    active: false,
    disabled: false,
    label: '使用此配置'
  })
})

test('an edited inactive config is saved before it becomes effective', () => {
  assert.deepEqual(paymentConfigUseState(config(), effective({ id: 9 }), true), {
    active: false,
    disabled: false,
    label: '保存并使用'
  })
})

test('the effective ENV config is marked as in use and cannot be selected again', () => {
  assert.deepEqual(environmentConfigUseState(effective({ id: null, source: 'ENV' }), true), {
    active: true,
    disabled: true,
    label: '正在使用'
  })
})

test('ENV can be selected again after a DB config becomes effective', () => {
  assert.deepEqual(environmentConfigUseState(effective(), true), {
    active: false,
    disabled: false,
    label: '使用此配置'
  })
})

test('an incomplete ENV config cannot be selected', () => {
  assert.deepEqual(environmentConfigUseState(effective(), false), {
    active: false,
    disabled: true,
    label: '配置不完整'
  })
})
