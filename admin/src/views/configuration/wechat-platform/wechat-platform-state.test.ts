import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildWechatPlatformUpdate,
  canRetainWechatPlatformSecret,
  createWechatPlatformForm
} from './wechat-platform-state'

const config = (overrides: Partial<Api.WechatPlatform.Config> = {}): Api.WechatPlatform.Config => ({
  configured: true,
  source: 'DATABASE',
  appId: 'wx-platform-app',
  appSecretMasked: '********',
  appSecretConfigured: true,
  legacyEnvironmentImportAvailable: false,
  version: 3,
  updatedBy: null,
  updatedAt: null,
  ...overrides
})

test('form never copies a masked or plaintext secret from the response', () => {
  assert.deepEqual(createWechatPlatformForm(config()), {
    appId: 'wx-platform-app',
    appSecret: ''
  })
})

test('only a database-backed secret can be retained by leaving the field blank', () => {
  assert.equal(canRetainWechatPlatformSecret(config()), true)
  assert.equal(canRetainWechatPlatformSecret(config({ source: 'ENVIRONMENT', version: 0 })), false)
  assert.equal(canRetainWechatPlatformSecret(config({ source: 'NONE', configured: false })), false)
})

test('update payload omits an unchanged secret and preserves the CAS version', () => {
  assert.deepEqual(
    buildWechatPlatformUpdate(config(), {
      appId: ' wx-next-app ',
      appSecret: ''
    }),
    { appId: 'wx-next-app', version: 3 }
  )
})
