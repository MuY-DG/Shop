import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildWaybillCreateRequest,
  canRetryWaybillRegistration,
  canUseManualShipment,
  createWaybillIdempotencyKey,
  formatWaybillRegistrationKind,
  formatWaybillRegistrationStatus,
  initialShipmentMode,
  isActiveWaybillAttempt,
  isCurrentWaybillResponse,
  logisticsDetailPriority,
  releaseBlobUrl,
  replaceBlobUrl,
  resolveWaybillCancelFeedback,
  resolveWaybillPanelPhase,
  visibleSandboxActions,
  waybillActionEnabled
} from './waybill-workflow'

const parcel: Api.Waybill.Parcel = {
  count: 1,
  weightKg: 0.8,
  lengthCm: 20,
  widthCm: 15,
  heightCm: 10
}

const attempt = (
  status: Api.Waybill.AttemptStatus = 'CREATED',
  overrides: Partial<Api.Waybill.Attempt> = {}
): Api.Waybill.Attempt => ({
  id: 7,
  orderId: 42,
  environment: 'SANDBOX',
  status,
  deliveryId: 'TEST',
  deliveryName: '微信官方测试运力',
  bizIdMasked: 'test***_id',
  serviceType: 1,
  serviceName: 'test_service_name',
  waybillNo: 'TEST-WAYBILL-001',
  parcel,
  remark: null,
  expectTime: null,
  printCount: 0,
  lastPrintedAt: null,
  createdAt: '2026-08-08T12:00:00Z',
  cancelledAt: null,
  confirmedAt: null,
  canRefresh: true,
  canCancel: true,
  canPrint: true,
  canConfirmShipment: true,
  canSimulate: true,
  ...overrides
})

const context = (
  currentAttempt: Api.Waybill.Attempt | null = null,
  overrides: Partial<Api.Waybill.Context> = {}
): Api.Waybill.Context => ({
  mode: 'SANDBOX',
  canCreate: currentAttempt === null,
  blockers: [],
  sender: null,
  receiver: null,
  defaultParcel: parcel,
  currentAttempt,
  sandboxActions: [
    { actionType: 100001, actionMessage: '快件已揽收' },
    { actionType: 300003, actionMessage: '快件已签收' }
  ],
  ...overrides
})

test('maps dialog opening and server attempts to the documented panel phases', () => {
  assert.equal(resolveWaybillPanelPhase(false, false, null), 'CLOSED')
  assert.equal(resolveWaybillPanelPhase(true, true, null), 'OPENING')
  assert.equal(resolveWaybillPanelPhase(true, false, null), 'EDITING')
  assert.equal(resolveWaybillPanelPhase(true, false, attempt()), 'READY')
  assert.equal(resolveWaybillPanelPhase(true, false, attempt('UNKNOWN')), 'READY')
  assert.equal(resolveWaybillPanelPhase(true, false, attempt('FAILED')), 'EDITING')
})

test('rejects stale async responses after an order switch or dialog close', () => {
  assert.equal(isCurrentWaybillResponse(3, 3, 42, 42, true), true)
  assert.equal(isCurrentWaybillResponse(3, 4, 42, 42, true), false)
  assert.equal(isCurrentWaybillResponse(3, 3, 42, 43, true), false)
  assert.equal(isCurrentWaybillResponse(3, 3, 42, 42, false), false)
})

test('locks manual shipment for every active waybill status', () => {
  for (const status of ['CREATING', 'CREATED', 'CANCELING', 'UNKNOWN'] as const) {
    assert.equal(isActiveWaybillAttempt(attempt(status)), true)
    assert.equal(canUseManualShipment(true, attempt(status)), false)
    assert.equal(initialShipmentMode(true, true, attempt(status)), 'electronic')
  }

  for (const status of ['FAILED', 'CANCELED', 'CONFIRMED'] as const) {
    assert.equal(isActiveWaybillAttempt(attempt(status)), false)
    assert.equal(canUseManualShipment(true, attempt(status)), true)
  }
  assert.equal(initialShipmentMode(true, true, null), 'manual')
  assert.equal(initialShipmentMode(false, true, null), 'electronic')
})

test('guards double clicks and gates every lifecycle action with server flags and permissions', () => {
  const ready = attempt()
  const editable = context(null)
  const access = {
    canManage: true,
    canPrint: true,
    canTest: true,
    canConfirmShipment: true
  }

  assert.equal(waybillActionEnabled('create', editable, null, null, access), true)
  assert.equal(waybillActionEnabled('refresh', context(ready), ready, null, access), true)
  assert.equal(waybillActionEnabled('cancel', context(ready), ready, null, access), true)
  assert.equal(waybillActionEnabled('preview', context(ready), ready, null, access), true)
  assert.equal(waybillActionEnabled('print', context(ready), ready, null, access), true)
  assert.equal(waybillActionEnabled('confirm', context(ready), ready, null, access), true)
  assert.equal(waybillActionEnabled('simulate', context(ready), ready, null, access), true)

  for (const action of [
    'create',
    'refresh',
    'cancel',
    'preview',
    'print',
    'confirm',
    'simulate'
  ] as const) {
    assert.equal(waybillActionEnabled(action, context(ready), ready, 'refresh', access), false)
  }
  assert.equal(
    waybillActionEnabled('confirm', context(ready), ready, null, {
      ...access,
      canManage: false
    }),
    false
  )
  assert.equal(
    waybillActionEnabled('confirm', context(ready), ready, null, {
      ...access,
      canConfirmShipment: false
    }),
    false
  )
  assert.equal(
    waybillActionEnabled('print', context(ready), ready, null, {
      ...access,
      canPrint: false
    }),
    false
  )
})

test('generates a valid RFC 4122 UUID v4 when crypto.randomUUID is unavailable', () => {
  assert.equal(
    createWaybillIdempotencyKey({ randomUUID: () => '9f297890-8e9b-4be6-8dcf-d7118c108876' }),
    '9f297890-8e9b-4be6-8dcf-d7118c108876'
  )

  const generated = createWaybillIdempotencyKey({
    getRandomValues(values) {
      values.set([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15])
      return values
    }
  })

  assert.equal(generated, '00010203-0405-4607-8809-0a0b0c0d0e0f')
  assert.match(generated, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})

test('reports cancellation as unlocked only after the server returns CANCELED', () => {
  assert.deepEqual(resolveWaybillCancelFeedback('CANCELED'), {
    tone: 'success',
    manualUnlocked: true,
    message: '电子面单已取消，可切换为手动填写运单'
  })

  for (const status of ['UNKNOWN', 'CANCELING'] as const) {
    const feedback = resolveWaybillCancelFeedback(status)
    assert.equal(feedback.tone, 'warning')
    assert.equal(feedback.manualUnlocked, false)
    assert.match(feedback.message, /仍不能手动发货/)
  }

  const rejected = resolveWaybillCancelFeedback('CREATED')
  assert.equal(rejected.tone, 'warning')
  assert.equal(rejected.manualUnlocked, false)
  assert.match(rejected.message, /取消未成功/)
})

test('formats registration diagnostics and restricts retry to recoverable server states', () => {
  assert.equal(formatWaybillRegistrationKind('TRACE'), '物流查询')
  assert.equal(formatWaybillRegistrationKind('FOLLOW'), '物流订阅')
  assert.equal(formatWaybillRegistrationKind(null), '-')
  assert.equal(formatWaybillRegistrationStatus('REGISTERED'), '登记成功')
  assert.equal(formatWaybillRegistrationStatus('UNAVAILABLE'), '服务暂不可用')
  assert.equal(formatWaybillRegistrationStatus(null), '-')

  for (const status of ['PENDING', 'FAILED', 'UNKNOWN', 'UNAVAILABLE'] as const) {
    assert.equal(canRetryWaybillRegistration({ waybillRegistrationStatus: status }), true)
  }
  for (const status of ['REGISTERING', 'REGISTERED', 'SKIPPED', null] as const) {
    assert.equal(canRetryWaybillRegistration({ waybillRegistrationStatus: status }), false)
  }
})

test('builds the restricted create payload without shipment carrier or tracking fields', () => {
  const request = buildWaybillCreateRequest({
    idempotencyKey: '  key-42  ',
    parcel,
    remark: '  易碎  ',
    expectTime: 1786219200
  })

  assert.deepEqual(request, {
    idempotencyKey: 'key-42',
    ...parcel,
    remark: '易碎',
    expectTime: 1786219200
  })
  assert.equal(Object.hasOwn(request, 'deliveryId'), false)
  assert.equal(Object.hasOwn(request, 'waybillNo'), false)
  assert.equal(
    Object.hasOwn(buildWaybillCreateRequest({ idempotencyKey: 'k', parcel }), 'remark'),
    false
  )
})

test('shows only server-provided sandbox actions when environment, record flag, and permission agree', () => {
  const ready = attempt()
  assert.deepEqual(visibleSandboxActions(context(ready), ready, true), [
    { actionType: 100001, actionMessage: '快件已揽收' },
    { actionType: 300003, actionMessage: '快件已签收' }
  ])
  assert.deepEqual(visibleSandboxActions(context(ready), ready, false), [])
  assert.deepEqual(visibleSandboxActions(context(ready, { mode: 'PRODUCTION' }), ready, true), [])
  assert.deepEqual(
    visibleSandboxActions(context(ready), attempt('CREATED', { canSimulate: false }), true),
    []
  )
})

test('prioritizes the final shipment over a pre-shipment waybill summary', () => {
  assert.equal(logisticsDetailPriority({ shipment: {}, electronicWaybill: attempt() }), 'shipment')
  assert.equal(logisticsDetailPriority({ shipment: null, electronicWaybill: attempt() }), 'waybill')
  assert.equal(logisticsDetailPriority({ shipment: null, electronicWaybill: null }), 'empty')
})

test('revokes each replaced or released Blob URL exactly once', () => {
  const revoked: string[] = []
  const revoke = (url: string) => revoked.push(url)

  assert.equal(replaceBlobUrl('blob:old', 'blob:new', revoke), 'blob:new')
  assert.equal(replaceBlobUrl('blob:new', 'blob:new', revoke), 'blob:new')
  assert.equal(releaseBlobUrl('blob:new', revoke), null)
  assert.equal(releaseBlobUrl(null, revoke), null)
  assert.deepEqual(revoked, ['blob:old', 'blob:new'])
})
