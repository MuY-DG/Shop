import assert from 'node:assert/strict'
import test from 'node:test'

import {
  canLoadWechatExpressConfig,
  canSaveWechatExpressConfig,
  createWechatExpressConfigForm,
  isWechatExpressConfigRevisionConflict,
  resolveEffectiveExpressAccount,
  toWechatExpressConfigUpdate,
  validateWechatExpressConfig,
  wechatExpressConfigSnapshot
} from './logistics-config-state'

const config = (
  overrides: Partial<Api.Waybill.WechatExpressConfig> = {}
): Api.Waybill.WechatExpressConfig => ({
  revision: 7,
  mode: 'DISABLED',
  messageEnabled: false,
  sender: {
    name: '',
    mobile: '',
    company: '',
    province: '',
    city: '',
    district: '',
    detailAddress: ''
  },
  production: {
    deliveryId: '',
    deliveryName: '',
    bizIdMasked: '',
    serviceType: null,
    serviceName: ''
  },
  effective: {
    deliveryId: '',
    deliveryName: '',
    bizIdMasked: '',
    serviceType: null,
    serviceName: ''
  },
  defaultParcel: {
    count: 1,
    weightKg: 1,
    lengthCm: 20,
    widthCm: 15,
    heightCm: 10
  },
  updatedAt: '2026-08-08T10:00:00Z',
  ...overrides
})

const completeSender = {
  name: '测试商家',
  mobile: '13800138000',
  company: '',
  province: '广东省',
  city: '深圳市',
  district: '南山区',
  detailAddress: '科技园 1 号'
}

test('always displays official forced sandbox identifiers instead of stored production values', () => {
  const form = createWechatExpressConfigForm(
    config({
      mode: 'SANDBOX',
      production: {
        deliveryId: 'SF',
        deliveryName: '顺丰速运',
        bizIdMasked: '123***890',
        serviceType: 2,
        serviceName: '顺丰特快'
      }
    })
  )

  assert.deepEqual(resolveEffectiveExpressAccount(form), {
    deliveryId: 'TEST',
    deliveryName: '微信官方测试运力',
    bizId: 'test_biz_id',
    serviceType: 1,
    serviceName: 'test_service_name',
    sandbox: true
  })
})

test('validates sender and parcel fields only when express mode is enabled', () => {
  const disabled = createWechatExpressConfigForm(config())
  assert.deepEqual(validateWechatExpressConfig(disabled), [])

  const sandbox = createWechatExpressConfigForm(config({ mode: 'SANDBOX' }))
  assert.match(validateWechatExpressConfig(sandbox)[0], /寄件人姓名/)

  Object.assign(sandbox.sender, completeSender)
  sandbox.defaultParcel.weightKg = 0
  assert.match(validateWechatExpressConfig(sandbox)[0], /默认包裹重量/)

  sandbox.defaultParcel.weightKg = 1.2
  assert.deepEqual(validateWechatExpressConfig(sandbox), [])
})

test('requires complete production identifiers but permits a retained masked biz id', () => {
  const production = createWechatExpressConfigForm(
    config({
      mode: 'PRODUCTION',
      sender: completeSender,
      production: {
        deliveryId: 'SF',
        deliveryName: '顺丰速运',
        bizIdMasked: '123***890',
        serviceType: 1,
        serviceName: '顺丰标快'
      }
    })
  )

  assert.deepEqual(validateWechatExpressConfig(production), [])

  production.production.deliveryId = ''
  assert.match(validateWechatExpressConfig(production)[0], /快递公司 ID/)

  production.production.deliveryId = 'SF'
  production.production.bizIdMasked = ''
  assert.match(validateWechatExpressConfig(production)[0], /客户编码/)
})

test('accepts official production service type zero but rejects negative or fractional ids', () => {
  const production = createWechatExpressConfigForm(
    config({
      mode: 'PRODUCTION',
      sender: completeSender,
      production: {
        deliveryId: 'SF',
        deliveryName: '顺丰速运',
        bizIdMasked: '123***890',
        serviceType: 0,
        serviceName: '标准服务'
      }
    })
  )

  assert.deepEqual(validateWechatExpressConfig(production), [])
  production.production.serviceType = -1
  assert.match(validateWechatExpressConfig(production)[0], /服务类型/)
  production.production.serviceType = 1.5
  assert.match(validateWechatExpressConfig(production)[0], /服务类型/)
})

test('builds a trimmed revision-aware update without password or token fields', () => {
  const form = createWechatExpressConfigForm(
    config({
      mode: 'PRODUCTION',
      sender: { ...completeSender, name: '  测试商家  ' },
      production: {
        deliveryId: ' SF ',
        deliveryName: ' 顺丰速运 ',
        bizIdMasked: '',
        serviceType: 1,
        serviceName: ' 顺丰标快 '
      }
    })
  )
  form.production.bizId = '  test-biz-id  '

  const payload = toWechatExpressConfigUpdate(form)

  assert.equal(payload.revision, 7)
  assert.equal(payload.sender.name, '测试商家')
  assert.equal(payload.production.deliveryId, 'SF')
  assert.equal(payload.production.bizId, 'test-biz-id')
  assert.equal(payload.production.serviceName, '顺丰标快')
  assert.equal('password' in payload, false)
  assert.equal('token' in payload, false)
})

test('omits a blank production biz id so an existing configured value is retained', () => {
  const form = createWechatExpressConfigForm(
    config({
      production: {
        deliveryId: 'SF',
        deliveryName: '顺丰速运',
        bizIdMasked: '123***890',
        serviceType: 1,
        serviceName: '顺丰标快'
      }
    })
  )

  const payload = toWechatExpressConfigUpdate(form)

  assert.equal(Object.hasOwn(payload.production, 'bizId'), false)
})

test('tracks editable changes without treating response-only metadata as form state', () => {
  const first = createWechatExpressConfigForm(config({ revision: 3 }))
  const second = createWechatExpressConfigForm(
    config({
      revision: 3,
      effective: {
        deliveryId: 'IGNORED',
        deliveryName: 'IGNORED',
        bizIdMasked: 'IGNORED',
        serviceType: 99,
        serviceName: 'IGNORED'
      },
      updatedAt: '2026-08-09T10:00:00Z'
    })
  )

  assert.equal(wechatExpressConfigSnapshot(first), wechatExpressConfigSnapshot(second))

  second.messageEnabled = true
  assert.notEqual(wechatExpressConfigSnapshot(first), wechatExpressConfigSnapshot(second))
})

test('gates reads and writes with their dedicated permissions', () => {
  assert.equal(canLoadWechatExpressConfig(false), false)
  assert.equal(canLoadWechatExpressConfig(true), true)
  assert.equal(canSaveWechatExpressConfig(false, true), false)
  assert.equal(canSaveWechatExpressConfig(true, false), false)
  assert.equal(canSaveWechatExpressConfig(true, true), true)
})

test('recognizes optimistic-lock conflicts without treating ordinary failures as stale revisions', () => {
  assert.equal(isWechatExpressConfigRevisionConflict({ httpStatus: 409 }), true)
  assert.equal(isWechatExpressConfigRevisionConflict({ httpStatus: 400 }), false)
  assert.equal(isWechatExpressConfigRevisionConflict(new Error('network')), false)
})
