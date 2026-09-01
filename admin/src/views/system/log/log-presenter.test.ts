import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import {
  formatLogDateTime,
  formatLogDevice,
  formatLogDuration,
  formatLogOperator,
  formatLogRequest,
  formatLogSummary,
  formatLogTarget,
  logLevelLabel,
  logLevelTone,
  logResultLabel,
  logResultTone,
  logTypeLabel,
  logTypeTone,
  normalizeLogSearchParams
} from './log-presenter'

test('normalizes system log filters and maps the occurrence range', () => {
  assert.deepEqual(
    normalizeLogSearchParams({
      type: 'OPERATION',
      result: 'FAILURE',
      keyword: '  商品 100  ',
      module: '  system-user  ',
      operator: '  Super  ',
      clientIp: '  127.0.0.1 ',
      requestId: ' request-123 ',
      occurredRange: ['2026-07-20T00:00:00Z', '2026-07-26T23:59:59Z']
    }),
    {
      type: 'OPERATION',
      result: 'FAILURE',
      keyword: '商品 100',
      module: 'system-user',
      operator: 'Super',
      clientIp: '127.0.0.1',
      requestId: 'request-123',
      occurredStart: '2026-07-20T00:00:00Z',
      occurredEnd: '2026-07-26T23:59:59Z'
    }
  )
})

test('omits blank filters and incomplete occurrence ranges', () => {
  assert.deepEqual(
    normalizeLogSearchParams({
      type: 'ALL',
      module: ' ',
      operator: '',
      clientIp: '  ',
      requestId: '',
      occurredRange: ['2026-07-20T00:00:00Z']
    }),
    {}
  )
})

test('uses dedicated log routes and no longer asks users to apply a type filter', () => {
  const source = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')
  const routeSource = readFileSync(
    new URL('../../../router/modules/audit-log.ts', import.meta.url),
    'utf8'
  )

  assert.doesNotMatch(source, /label: '日志类型'/)
  assert.match(source, /AuditOperation:[\s\S]*?type: 'OPERATION'/)
  assert.match(source, /AuditRequest:[\s\S]*?type: 'REQUEST'/)
  assert.match(routeSource, /path: 'operation'/)
  assert.match(routeSource, /path: 'security'/)
  assert.match(routeSource, /path: 'exceptions'/)
  assert.match(routeSource, /path: 'requests'/)
})

test('maps log types, levels, and results to stable labels and tag tones', () => {
  assert.equal(logTypeLabel('SECURITY'), '安全日志')
  assert.equal(logTypeTone('EXCEPTION'), 'danger')
  assert.equal(logLevelLabel('WARN'), '警告')
  assert.equal(logLevelTone('INFO'), 'info')
  assert.equal(logResultLabel('SUCCESS'), '成功')
  assert.equal(logResultTone('FAILURE'), 'danger')
})

test('formats list and detail values in the browser timezone without inventing request content', () => {
  const instant = new Date('2026-07-26T10:20:30Z')
  const pad = (value: number) => String(value).padStart(2, '0')
  const expectedLocalTime = `${instant.getFullYear()}-${pad(instant.getMonth() + 1)}-${pad(
    instant.getDate()
  )} ${pad(instant.getHours())}:${pad(instant.getMinutes())}:${pad(instant.getSeconds())}`

  assert.equal(formatLogDateTime('2026-07-26T10:20:30Z'), expectedLocalTime)
  assert.equal(formatLogDateTime(undefined), '-')
  assert.equal(formatLogDuration(0), '0 ms')
  assert.equal(formatLogDuration(-1), '-')
  assert.equal(formatLogOperator({ operatorUserId: '1', operatorUsername: ' Super ' }), 'Super')
  assert.equal(formatLogOperator({ operatorUserId: '2', operatorUsername: '' }), '管理员 2')
  assert.equal(formatLogOperator({ operatorUsername: '' }), '未识别')
  assert.equal(
    formatLogRequest({ requestMethod: 'POST', requestPath: '/admin/system/users' }),
    'POST /admin/system/users'
  )
  assert.equal(
    formatLogSummary({ summary: ' 修改商品成功 ', action: 'PUT /legacy' }),
    '修改商品成功'
  )
  assert.equal(formatLogSummary({ summary: '', action: 'login' }), 'login')
  assert.equal(formatLogTarget({ targetType: 'spuId', targetId: '100' }), 'spuId：100')
  assert.equal(
    formatLogTarget({
      targetType: 'afterSaleId',
      targetId: '7',
      relatedTargetType: 'refundOrderId',
      relatedTargetId: '4'
    }),
    'afterSaleId：7 / refundOrderId：4'
  )
  assert.equal(formatLogTarget({ targetType: '', targetId: '' }), '-')
  assert.equal(
    formatLogDevice(
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36'
    ),
    'Chrome 140 · macOS'
  )
})

test('system log page never renders request or response bodies', () => {
  const source = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')

  assert.doesNotMatch(
    source,
    /currentLog\.(?:requestBody|responseBody|requestHeaders|responseHeaders)/
  )
  assert.doesNotMatch(source, /已脱敏/)
  assert.match(source, /最小化请求元数据/)
})

test('system log detail adapts to narrow screens and tooltip triggers are accessible', () => {
  const source = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')

  assert.match(source, /:size="detailDrawerSize"/)
  assert.match(source, /useWindowSize/)
  assert.equal(source.match(/tabindex="0"/g)?.length, 2)
  assert.equal(source.match(/:aria-label=/g)?.length, 2)
})
