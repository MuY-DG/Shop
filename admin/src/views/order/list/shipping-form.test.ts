import assert from 'node:assert/strict'
import test from 'node:test'

import {
  canRetryWechatUpload,
  canLoadWechatShippingCatalog,
  canStartCarrierSync,
  clearExpressFields,
  contextualizeRetryOutcome,
  formatOptionalDateTime,
  formatShipmentModeDetail,
  formatWechatUploadError,
  itemDescLength,
  logisticsTypeLabel,
  shippingCapabilityMessage,
  shippingOutcomeMessage,
  suggestItemDesc,
  trimItemDesc,
  validateShippingForm,
  visibleShippingFields
} from './shipping-form'

const validForm = (
  logisticsType: Api.Order.LogisticsType,
  overrides: Partial<Api.Order.ShipOrderForm> = {}
): Api.Order.ShipOrderForm => ({
  logisticsType,
  itemDesc: '菌汤锅底 300g x2',
  expressCompanyCode: logisticsType === 1 ? 'SF' : undefined,
  trackingNo: logisticsType === 1 ? 'SF123456789' : undefined,
  consignorContact: logisticsType === 1 ? '13800138000' : undefined,
  shipmentNote: '仓库备注',
  ...overrides
})

const shipment = (
  providerMode: Api.Order.WechatProviderMode,
  wechatUploadStatus: Api.Order.WechatShippingUploadStatus,
  overrides: Partial<Api.Order.Shipment> = {}
): Api.Order.Shipment => ({
  shipmentId: 1,
  orderId: 10,
  logisticsType: 1,
  deliveryMode: 1,
  itemDesc: '菌汤锅底 x2',
  expressCompanyCode: 'SF',
  expressCompanyName: '顺丰速运',
  trackingNo: 'SF123456789',
  localShipmentStatus: 'SHIPPED',
  wechatProviderMode: providerMode,
  wechatUploadStatus,
  retryCount: 0,
  waybillTrackingSupported: false,
  waybillRegistrationKind: null,
  waybillRegistrationStatus: null,
  waybillRegistrationMessage: null,
  ...overrides
})

test('shows express fields only for type 1', () => {
  assert.deepEqual(visibleShippingFields(1), [
    'logisticsType',
    'itemDesc',
    'expressCompanyCode',
    'trackingNo',
    'consignorContact',
    'shipmentNote'
  ])
  for (const type of [2, 3, 4] as const) {
    assert.deepEqual(visibleShippingFields(type), ['logisticsType', 'itemDesc', 'shipmentNote'])
  }
})

test('validates all four modes conditionally', () => {
  for (const type of [1, 2, 3, 4] as const) {
    assert.deepEqual(validateShippingForm(validForm(type)), [])
  }

  assert.match(validateShippingForm(validForm(1, { trackingNo: '' }))[0], /快递单号/)
  assert.match(validateShippingForm(validForm(1, { expressCompanyCode: '' }))[0], /快递公司/)
  assert.match(validateShippingForm(validForm(4, { itemDesc: '' }))[0], /商品描述/)
  assert.deepEqual(
    validateShippingForm(
      validForm(2, {
        expressCompanyCode: '',
        trackingNo: '',
        consignorContact: ''
      })
    ),
    []
  )
})

test('counts, validates, and truncates item description by Unicode code point', () => {
  assert.equal(itemDescLength('🔥'.repeat(120)), 120)
  assert.equal(validateShippingForm(validForm(3, { itemDesc: '🔥'.repeat(121) })).length, 1)
  assert.equal(itemDescLength(trimItemDesc(`  ${'🔥'.repeat(121)}  `)), 120)
  assert.equal(trimItemDesc(`  ${'🔥'.repeat(121)}  `), '🔥'.repeat(120))
})

test('suggests an editable item description from order snapshots within 120 code points', () => {
  const suggestion = suggestItemDesc([
    { productTitle: '菌汤锅底', specText: '300g', quantity: 2 },
    { productTitle: '牛油锅底', specText: '', quantity: 1 },
    { productTitle: '🔥'.repeat(120), specText: '大份', quantity: 3 }
  ])

  assert.match(suggestion, /菌汤锅底.*300g.*2/)
  assert.match(suggestion, /牛油锅底.*1/)
  assert.ok(itemDescLength(suggestion) <= 120)
})

test('clears express-only fields when switching to a non-express mode', () => {
  const cleared = clearExpressFields(validForm(3))
  assert.deepEqual(cleared, {
    logisticsType: 3,
    itemDesc: '菌汤锅底 300g x2',
    shipmentNote: '仓库备注'
  })
  assert.deepEqual(Object.keys(cleared).sort(), ['itemDesc', 'logisticsType', 'shipmentNote'])
})

test('only REAL plus UPLOADED claims real WeChat platform acceptance', () => {
  assert.equal(
    shippingOutcomeMessage(shipment('REAL', 'UPLOADED')),
    '本地发货成功，真实微信发货信息已上传'
  )
  assert.match(shippingOutcomeMessage(shipment('MOCK', 'UNAVAILABLE')), /模拟环境/)

  for (const status of ['PENDING', 'SKIPPED', 'FAILED', 'UNAVAILABLE', 'UNKNOWN'] as const) {
    const message = shippingOutcomeMessage(shipment('REAL', status))
    assert.match(message, /本地发货成功/)
    assert.doesNotMatch(message, /真实微信发货信息已上传/)
  }
  assert.doesNotMatch(
    shippingOutcomeMessage(shipment('DISABLED', 'SKIPPED')),
    /真实微信发货信息已上传/
  )

  const providerModes: Api.Order.WechatProviderMode[] = ['REAL', 'MOCK', 'DISABLED', 'UNKNOWN']
  const uploadStatuses: Api.Order.WechatShippingUploadStatus[] = [
    'PENDING',
    'SKIPPED',
    'UPLOADING',
    'UPLOADED',
    'FAILED',
    'UNAVAILABLE',
    'UNKNOWN'
  ]
  for (const providerMode of providerModes) {
    for (const uploadStatus of uploadStatuses) {
      const message = shippingOutcomeMessage(shipment(providerMode, uploadStatus))
      const isRealAcceptance = providerMode === 'REAL' && uploadStatus === 'UPLOADED'
      assert.equal(
        message.includes('真实微信发货信息已上传'),
        isRealAcceptance,
        `${providerMode}/${uploadStatus}`
      )
    }
  }
})

test('allows retry only for FAILED, UNAVAILABLE, or an eligible SKIPPED state', () => {
  for (const status of ['PENDING', 'UPLOADING', 'UPLOADED', 'UNKNOWN'] as const) {
    assert.equal(canRetryWechatUpload(shipment('REAL', status)), false)
  }
  assert.equal(canRetryWechatUpload(shipment('REAL', 'FAILED')), true)
  assert.equal(canRetryWechatUpload(shipment('REAL', 'UNAVAILABLE')), true)
  const disabledCapability: Api.Order.WechatShippingCapability = {
    uploadEnabled: false,
    providerMode: 'DISABLED',
    state: 'UNAVAILABLE',
    tradeManaged: null,
    checkedAt: '2026-07-10T10:00:00Z'
  }
  assert.equal(canRetryWechatUpload(shipment('REAL', 'FAILED'), disabledCapability), false)
  assert.equal(canRetryWechatUpload(shipment('REAL', 'UNAVAILABLE'), disabledCapability), false)
  assert.equal(canRetryWechatUpload(shipment('REAL', 'SKIPPED')), false)
  assert.equal(
    canRetryWechatUpload(shipment('REAL', 'SKIPPED'), {
      uploadEnabled: true,
      providerMode: 'REAL',
      state: 'AVAILABLE',
      tradeManaged: true,
      checkedAt: '2026-07-10T10:00:00Z'
    }),
    true
  )
  assert.equal(
    canRetryWechatUpload(shipment('REAL', 'SKIPPED'), {
      uploadEnabled: true,
      providerMode: 'REAL',
      state: 'UNKNOWN',
      tradeManaged: null,
      checkedAt: '2026-07-10T10:00:00Z'
    }),
    true
  )
  assert.equal(
    canRetryWechatUpload(shipment('MOCK', 'SKIPPED'), {
      uploadEnabled: true,
      providerMode: 'MOCK',
      state: 'UNAVAILABLE',
      tradeManaged: null,
      checkedAt: '2026-07-10T10:00:00Z'
    }),
    true
  )
  assert.equal(canRetryWechatUpload(shipment('REAL', 'SKIPPED'), disabledCapability), false)
})

test('formats non-express shipment facts when express-only keys are absent', () => {
  const localDelivery: Api.Order.Shipment = {
    shipmentId: 2,
    orderId: 20,
    logisticsType: 2,
    deliveryMode: 1,
    itemDesc: '同城商品',
    localShipmentStatus: 'SHIPPED',
    wechatProviderMode: 'REAL',
    wechatUploadStatus: 'FAILED',
    retryCount: 0,
    waybillTrackingSupported: false,
    waybillRegistrationKind: null,
    waybillRegistrationStatus: null,
    waybillRegistrationMessage: null
  }

  assert.equal(Object.hasOwn(localDelivery, 'expressCompanyCode'), false)
  assert.equal(Object.hasOwn(localDelivery, 'wechatErrorCode'), false)
  assert.equal(Object.hasOwn(localDelivery, 'shippedAt'), false)
  assert.equal(logisticsTypeLabel(localDelivery.logisticsType), '同城配送')
  assert.equal(formatShipmentModeDetail(localDelivery), '同城配送，无快递单号')
  assert.equal(formatWechatUploadError(localDelivery), '-')
  assert.equal(formatOptionalDateTime(localDelivery.shippedAt), '-')
  assert.equal(formatOptionalDateTime(localDelivery.uploadTime), '-')
  assert.equal(formatOptionalDateTime(localDelivery.wechatUploadedAt), '-')
  assert.equal(formatOptionalDateTime(localDelivery.lastAttemptAt), '-')
  assert.equal(
    formatShipmentModeDetail(shipment('REAL', 'SKIPPED', { logisticsType: 3 })),
    '虚拟商品交付'
  )
  assert.equal(
    formatShipmentModeDetail(shipment('REAL', 'SKIPPED', { logisticsType: 4 })),
    '用户自提'
  )
})

test('keeps carrier display names separate from delivery codes and tolerates legacy express rows', () => {
  const current = shipment('REAL', 'UPLOADED', {
    expressCompanyCode: 'SF',
    expressCompanyName: '顺丰速运',
    trackingNo: 'SF100'
  })
  assert.equal(formatShipmentModeDetail(current), '顺丰速运（SF） / SF100')

  const legacy = shipment('UNKNOWN', 'SKIPPED', {
    expressCompanyCode: undefined,
    expressCompanyName: '历史快递文本',
    trackingNo: 'LEGACY100'
  })
  assert.equal(formatShipmentModeDetail(legacy), '历史快递文本 / LEGACY100')

  const expressPayload = clearExpressFields(validForm(1, { expressCompanyCode: 'SF' }))
  assert.equal(expressPayload.expressCompanyCode, 'SF')
  assert.equal(Object.hasOwn(expressPayload, 'expressCompanyName'), false)
})

test('does not start carrier sync while the initial catalog or another sync is loading', () => {
  assert.equal(canStartCarrierSync(false, false), true)
  assert.equal(canStartCarrierSync(true, false), false)
  assert.equal(canStartCarrierSync(false, true), false)
  assert.equal(canStartCarrierSync(true, true), false)
})

test('loads WeChat capability and carrier endpoints only with order:ship authority', () => {
  assert.equal(canLoadWechatShippingCatalog(false), false)
  assert.equal(canLoadWechatShippingCatalog(true), true)
})

test('keeps the exact retry outcome in place and adds the original order after detail switches', () => {
  const outcome = '本地发货成功，真实微信发货信息已上传'
  assert.equal(contextualizeRetryOutcome(outcome, 'ORDER-A', false), outcome)
  assert.equal(contextualizeRetryOutcome(outcome, 'ORDER-A', true), `订单 ORDER-A：${outcome}`)
})

test('describes capability without overstating disabled, mock, unavailable, or unknown states', () => {
  assert.match(
    shippingCapabilityMessage({
      uploadEnabled: false,
      providerMode: 'DISABLED',
      state: 'UNAVAILABLE',
      tradeManaged: null,
      checkedAt: '2026-07-10T10:00:00Z'
    }),
    /未启用/
  )
  assert.match(
    shippingCapabilityMessage({
      uploadEnabled: true,
      providerMode: 'MOCK',
      state: 'UNAVAILABLE',
      tradeManaged: null,
      checkedAt: '2026-07-10T10:00:00Z'
    }),
    /模拟/
  )
  assert.match(
    shippingCapabilityMessage({
      uploadEnabled: false,
      providerMode: 'MOCK',
      state: 'UNAVAILABLE',
      tradeManaged: null,
      checkedAt: '2026-07-10T10:00:00Z'
    }),
    /模拟/
  )
  assert.match(
    shippingCapabilityMessage({
      uploadEnabled: true,
      providerMode: 'REAL',
      state: 'UNKNOWN',
      tradeManaged: null,
      checkedAt: '2026-07-10T10:00:00Z'
    }),
    /未知/
  )
  assert.match(
    shippingCapabilityMessage({
      uploadEnabled: true,
      providerMode: 'REAL',
      state: 'AVAILABLE',
      tradeManaged: true,
      checkedAt: '2026-07-10T10:00:00Z'
    }),
    /可用/
  )
})
