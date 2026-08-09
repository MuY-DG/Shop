import assert from 'node:assert/strict'
import test from 'node:test'

import {
  formatTrackingSourceError,
  formatTrackingSyncStatus,
  trackingPathEmptyText
} from './tracking-state'

test('formats independent query_trace and getPath statuses', () => {
  assert.equal(formatTrackingSyncStatus('SYNCED'), '同步成功')
  assert.equal(formatTrackingSyncStatus('UNKNOWN'), '结果待确认')
  assert.equal(formatTrackingSyncStatus('UNSUPPORTED'), '暂不支持')
})

test('formats sanitized source errors without empty separators', () => {
  assert.equal(formatTrackingSourceError(null, null), '-')
  assert.equal(formatTrackingSourceError('WECHAT_930001', null), 'WECHAT_930001')
  assert.equal(
    formatTrackingSourceError('REQUEST_AMBIGUOUS', '物流查询结果待确认'),
    'REQUEST_AMBIGUOUS：物流查询结果待确认'
  )
})

test('keeps a visible getPath empty state for every capability outcome', () => {
  assert.equal(trackingPathEmptyText(null), '尚未读取微信物流数据')
  assert.equal(
    trackingPathEmptyText({ pathSupported: false, pathSyncStatus: 'UNSUPPORTED', pathItems: [] }),
    '当前运单不具备 getPath 查询条件'
  )
  assert.equal(
    trackingPathEmptyText({ pathSupported: true, pathSyncStatus: 'SYNCED', pathItems: [] }),
    'getPath 已同步，当前暂无轨迹节点'
  )
  assert.equal(
    trackingPathEmptyText({ pathSupported: true, pathSyncStatus: 'FAILED', pathItems: [] }),
    'getPath 暂不可用，已保留最近一次成功轨迹（如有）'
  )
})
