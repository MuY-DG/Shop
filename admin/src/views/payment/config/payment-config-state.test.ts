import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { paymentConfigDeleteState, paymentConfigUseState } from './payment-config-state'

const pageSource = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')
const apiSource = readFileSync(new URL('../../../api/payment.ts', import.meta.url), 'utf8')

const config = (overrides: Partial<Api.Payment.Config> = {}): Api.Payment.Config => ({
  id: 7,
  source: 'DB',
  configName: '正式环境',
  appIdMasked: 'wx_***_123',
  mchIdMasked: '12***34',
  merchantSerialNoMasked: 'ABC***XYZ',
  apiV3KeyConfigured: true,
  privateKeyConfigured: true,
  verifyMode: 'PUBLIC_KEY',
  wechatPublicKeyIdMasked: 'PUB***KEY',
  wechatPublicKeyConfigured: true,
  legacySecretFilesPendingImport: false,
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

test('an enabled DB candidate is selectable when no effective config exists', () => {
  assert.deepEqual(paymentConfigUseState(config(), null), {
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

test('the effective DB config cannot be deleted even if a stale list row is not enabled', () => {
  assert.deepEqual(
    paymentConfigDeleteState(config({ enabled: false }), effective({ enabled: false })),
    {
      disabled: true,
      reason: '正在使用的配置不能删除'
    }
  )
})

test('an enabled DB config cannot be deleted when no effective config exists', () => {
  assert.deepEqual(paymentConfigDeleteState(config(), null), {
    disabled: true,
    reason: '已启用的配置不能删除'
  })
})

test('an inactive DB config can be deleted', () => {
  assert.deepEqual(paymentConfigDeleteState(config({ enabled: false }), effective({ id: 9 })), {
    disabled: false,
    reason: ''
  })
})

test('a config with legacy secret files cannot be deleted before migration', () => {
  assert.deepEqual(
    paymentConfigDeleteState(
      config({ enabled: false, legacySecretFilesPendingImport: true }),
      effective({ id: 9 })
    ),
    {
      disabled: true,
      reason: '请先迁移旧秘密文件'
    }
  )
})

test('an edited inactive config remains deletable based on persisted state', () => {
  assert.deepEqual(paymentConfigDeleteState(config({ enabled: false }), null), {
    disabled: false,
    reason: ''
  })
})

test('the payment config Admin source exposes only database configurations', () => {
  for (const removedEnvironmentContract of [
    '环境变量配置',
    'fetchEnvironmentPaymentConfig',
    'importEnvironmentPaymentConfig',
    'value="ENV"'
  ]) {
    assert.equal(pageSource.includes(removedEnvironmentContract), false)
    assert.equal(apiSource.includes(removedEnvironmentContract), false)
  }
})

test('the payment config Admin source keeps the protected soft-delete action', () => {
  assert.ok(pageSource.includes('v-auth="\'payment:config:delete\'"'))
  assert.ok(pageSource.includes('@click="handleDelete"'))
  assert.ok(pageSource.includes('deletePaymentConfig(config.id, false)'))
  assert.ok(apiSource.includes('export function deletePaymentConfig'))
  assert.ok(apiSource.includes('request.del<void>'))
  assert.ok(apiSource.includes('url: `/admin/pay/configs/${configId}`'))
})

test('the first database payment config creation has no ineffective cancel action', () => {
  assert.ok(pageSource.includes('v-if="creating && configs.length > 0"'))
})
