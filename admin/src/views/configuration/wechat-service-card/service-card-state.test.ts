import assert from 'node:assert/strict'
import test from 'node:test'

import {
  deliveryStateLabel,
  deliveryStateTone,
  messageResultLabel,
  runtimeBlockers,
  runtimeChanged,
  runtimeConfirmation,
  runtimeStatusChanged,
  targetStatusLabel,
  validateConfirmationPhrase,
  validateRuntimeDraft,
  validateRuntimeReason
} from './service-card-state'

const status = (
  overrides: Partial<Api.WechatServiceCard.Status> = {}
): Api.WechatServiceCard.Status => ({
  captureEnabled: false,
  workerEnabled: false,
  captureReady: false,
  templateConfigured: true,
  imageReady: true,
  miniProgramCredentialsReady: true,
  workerReady: false,
  callbackEnabled: true,
  callbackReady: true,
  blockedCards: 0,
  pendingDeliveries: 0,
  sendingDeliveries: 0,
  unknownDeliveries: 0,
  failedDeliveries: 0,
  runtimePersisted: true,
  version: 3,
  defaultCaptureEnabled: false,
  defaultWorkerEnabled: false,
  reason: '保持关闭',
  updatedBy: '1',
  updatedAt: '2026-08-11T05:00:00Z',
  repairEligibleCount: 2,
  repairEligibleEarliestPaidAt: '2026-08-11T02:23:20Z',
  repairEligibleLatestPaidAt: '2026-08-11T02:56:15Z',
  ...overrides
})

test('renders delivery and WeChat target states in Chinese', () => {
  assert.equal(deliveryStateLabel('RECONCILING'), '核对中')
  assert.equal(deliveryStateTone('FAILED'), 'danger')
  assert.equal(targetStatusLabel(2), '2 · 待商家发货')
  assert.equal(targetStatusLabel(99), '99 · 未知状态')
  assert.equal(messageResultLabel('UNKNOWN'), '未收到失败回调')
})

test('requires capture whenever the worker is enabled', () => {
  assert.equal(
    validateRuntimeDraft({ captureEnabled: false, workerEnabled: true }),
    '开启外呼时必须同时开启采集'
  )
  assert.equal(validateRuntimeDraft({ captureEnabled: true, workerEnabled: true }), null)
  assert.deepEqual(
    runtimeBlockers(status({ captureEnabled: true, callbackReady: false }), {
      captureEnabled: true,
      workerEnabled: true
    }),
    ['微信安全回调未就绪']
  )
})

test('requires a persisted capture-only stage before enabling the worker', () => {
  assert.deepEqual(runtimeBlockers(status(), { captureEnabled: true, workerEnabled: true }), [
    '必须先单独保存“采集开启、外呼关闭”，验收队列后才能开启外呼'
  ])
  assert.deepEqual(
    runtimeBlockers(status({ captureEnabled: true }), {
      captureEnabled: true,
      workerEnabled: true
    }),
    []
  )
})

test('readiness failures never block emergency shutdown', () => {
  assert.deepEqual(
    runtimeBlockers(
      status({
        captureEnabled: true,
        workerEnabled: true,
        imageReady: false,
        callbackReady: false
      }),
      { captureEnabled: false, workerEnabled: false }
    ),
    []
  )
})

test('high-risk confirmation exposes repair candidates and requires an exact phrase', () => {
  const confirmation = runtimeConfirmation(status(), {
    captureEnabled: true,
    workerEnabled: false
  })

  assert.match(confirmation.message, /2 笔候选/)
  assert.match(confirmation.message, /有效更新窗口内非终态卡/)
  assert.equal(confirmation.phrase, '确认开启服务动态采集')
  assert.equal(validateConfirmationPhrase('确认开启服务动态采集', confirmation.phrase), null)
  assert.equal(
    validateConfirmationPhrase('开启', confirmation.phrase),
    '请输入“确认开启服务动态采集”'
  )
})

test('worker confirmation calls out existing outbound work', () => {
  const confirmation = runtimeConfirmation(status({ pendingDeliveries: 4, unknownDeliveries: 1 }), {
    captureEnabled: true,
    workerEnabled: true
  })

  assert.match(confirmation.message, /当前相关队列 5 条/)
  assert.equal(confirmation.phrase, '确认开启微信外呼')
  assert.equal(confirmation.tone, 'error')
})

test('validates change detection and audited reason bounds', () => {
  const current = status()
  assert.equal(runtimeChanged(current, { captureEnabled: false, workerEnabled: false }), false)
  assert.equal(runtimeChanged(current, { captureEnabled: true, workerEnabled: false }), true)
  assert.equal(validateRuntimeReason(' '), '请输入至少 2 个字符的真实变更原因')
  assert.equal(validateRuntimeReason('合理原因'), null)
  assert.equal(validateRuntimeReason('x'.repeat(201)), '变更原因不能超过 200 个字符')
})

test('detects an unknown write result from version or effective switch changes', () => {
  const previous = status({ version: 3, captureEnabled: false, workerEnabled: false })
  assert.equal(runtimeStatusChanged(previous, status({ version: 3 })), false)
  assert.equal(runtimeStatusChanged(previous, status({ version: 4 })), true)
  assert.equal(runtimeStatusChanged(previous, status({ captureEnabled: true })), true)
  assert.equal(runtimeStatusChanged(previous, status({ workerEnabled: true })), true)
})
