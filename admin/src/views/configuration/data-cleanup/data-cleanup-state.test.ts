import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createDataCleanupConfigForm,
  dataCleanupConfigSnapshot,
  DATA_CLEANUP_TASK_ORDER,
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
      task('ADMIN_SYSTEM_LOG')
    ]
  })

  assert.equal(form.revision, 8)
  assert.deepEqual(
    form.tasks.map((item) => item.taskCode),
    DATA_CLEANUP_TASK_ORDER
  )
  assert.equal('lastStatus' in form.tasks[4], false)
  assert.equal(form.tasks[2].uploadPendingGraceMinutes, null)
  assert.equal(form.tasks[3].retentionDays, null)
  assert.equal(form.tasks[3].uploadPendingGraceMinutes, 30)
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
        uploadPendingGraceMinutes: 45
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
        uploadPendingGraceMinutes: 45
      }
    ]
  })
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
