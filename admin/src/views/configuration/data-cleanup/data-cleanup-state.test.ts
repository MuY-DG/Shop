import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createDataCleanupConfigForm,
  dataCleanupConfigSnapshot,
  toDataCleanupConfigPayload
} from './data-cleanup-state'

const task = (
  taskCode: Api.DataCleanup.TaskCode,
  overrides: Partial<Api.DataCleanup.TaskConfig> = {}
): Api.DataCleanup.TaskConfig => ({
  taskCode,
  enabled: true,
  retentionDays: 400,
  batchSize: 1000,
  cronExpression: '0 15 3 * * *',
  batchIntervalSeconds: 60,
  uploadPendingGraceMinutes: null,
  retainReviews: null,
  ...overrides
})

test('creates an editable form in stable business order without runtime fields', () => {
  const form = createDataCleanupConfigForm({
    revision: 8,
    tasks: [
      task('DIRECT_UPLOAD_SESSION', { lastStatus: 'SUCCESS', lastProcessedCount: 12 }),
      task('ANALYTICS_EVENT'),
      task('STORAGE_ASSET', { retentionDays: undefined, uploadPendingGraceMinutes: 30 }),
      task('CUSTOMER_SERVICE_MESSAGE'),
      task('ORDER_AGGREGATE', { retentionDays: 1095, retainReviews: undefined }),
      task('ADMIN_SYSTEM_LOG')
    ]
  })

  assert.equal(form.revision, 8)
  assert.deepEqual(
    form.tasks.map((item) => item.taskCode),
    [
      'ANALYTICS_EVENT',
      'ADMIN_SYSTEM_LOG',
      'CUSTOMER_SERVICE_MESSAGE',
      'ORDER_AGGREGATE',
      'STORAGE_ASSET',
      'DIRECT_UPLOAD_SESSION'
    ]
  )
  assert.equal('lastStatus' in form.tasks[5], false)
  assert.equal(form.tasks[2].uploadPendingGraceMinutes, null)
  assert.equal(form.tasks[3].retainReviews, true)
  assert.equal(form.tasks[4].retentionDays, null)
  assert.equal(form.tasks[4].uploadPendingGraceMinutes, 30)
  assert.ok(
    form.tasks
      .filter((item) => item.taskCode !== 'ORDER_AGGREGATE')
      .every((item) => item.retainReviews === null)
  )
})

test('builds a complete update payload and trims cron expressions', () => {
  const payload = toDataCleanupConfigPayload({
    revision: 4,
    tasks: [
      {
        taskCode: 'STORAGE_ASSET',
        enabled: false,
        retentionDays: null,
        batchSize: 75,
        cronExpression: '  0 */10 * * * *  ',
        batchIntervalSeconds: 120,
        uploadPendingGraceMinutes: 45,
        retainReviews: true
      }
    ]
  })

  assert.deepEqual(payload, {
    revision: 4,
    tasks: [
      {
        taskCode: 'STORAGE_ASSET',
        enabled: false,
        retentionDays: null,
        batchSize: 75,
        cronExpression: '0 */10 * * * *',
        batchIntervalSeconds: 120,
        uploadPendingGraceMinutes: 45,
        retainReviews: null
      }
    ]
  })
})

test('serializes the order review preference and defaults it to retained', () => {
  const form = createDataCleanupConfigForm({
    revision: 5,
    tasks: [task('ORDER_AGGREGATE', { retainReviews: undefined })]
  })

  assert.equal(form.tasks[0].retainReviews, true)
  assert.equal(toDataCleanupConfigPayload(form).tasks[0].retainReviews, true)

  form.tasks[0].retainReviews = false

  assert.equal(toDataCleanupConfigPayload(form).tasks[0].retainReviews, false)
})

test('snapshot changes when any editable setting changes', () => {
  const form = createDataCleanupConfigForm({
    revision: 2,
    tasks: [task('ANALYTICS_EVENT')]
  })
  const baseline = dataCleanupConfigSnapshot(form)

  form.tasks[0].batchSize = 500

  assert.notEqual(dataCleanupConfigSnapshot(form), baseline)
})

test('snapshot changes when the order review preference changes', () => {
  const form = createDataCleanupConfigForm({
    revision: 3,
    tasks: [task('ORDER_AGGREGATE', { retainReviews: true })]
  })
  const baseline = dataCleanupConfigSnapshot(form)

  form.tasks[0].retainReviews = false

  assert.notEqual(dataCleanupConfigSnapshot(form), baseline)
})
