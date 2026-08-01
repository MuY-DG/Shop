import { formatLocalDateTime } from '@/utils/date-time'

export type LogTagTone = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export interface SystemLogSearchForm {
  type?: Api.SystemLog.LogType | 'ALL'
  result?: Api.SystemLog.LogResult
  module?: string
  operator?: string
  clientIp?: string
  requestId?: string
  occurredRange?: string[]
}

const TYPE_LABELS: Record<Api.SystemLog.LogType, string> = {
  LOGIN: '登录日志',
  OPERATION: '操作日志',
  ACCESS: '访问日志',
  EXCEPTION: '异常日志'
}

const TYPE_TONES: Record<Api.SystemLog.LogType, LogTagTone> = {
  LOGIN: 'primary',
  OPERATION: 'success',
  ACCESS: 'info',
  EXCEPTION: 'danger'
}

const LEVEL_LABELS: Record<Api.SystemLog.LogLevel, string> = {
  INFO: '信息',
  WARN: '警告',
  ERROR: '错误'
}

const LEVEL_TONES: Record<Api.SystemLog.LogLevel, LogTagTone> = {
  INFO: 'info',
  WARN: 'warning',
  ERROR: 'danger'
}

const RESULT_LABELS: Record<Api.SystemLog.LogResult, string> = {
  SUCCESS: '成功',
  FAILURE: '失败'
}

const RESULT_TONES: Record<Api.SystemLog.LogResult, LogTagTone> = {
  SUCCESS: 'success',
  FAILURE: 'danger'
}

const normalizedText = (value?: string) => {
  const normalized = value?.trim()
  return normalized || undefined
}

export function normalizeLogSearchParams(form: SystemLogSearchForm): Api.SystemLog.SearchParams {
  const params: Api.SystemLog.SearchParams = {}

  if (form.type && form.type !== 'ALL') params.type = form.type
  if (form.result) params.result = form.result

  const module = normalizedText(form.module)
  const operator = normalizedText(form.operator)
  const clientIp = normalizedText(form.clientIp)
  const requestId = normalizedText(form.requestId)

  if (module) params.module = module
  if (operator) params.operator = operator
  if (clientIp) params.clientIp = clientIp
  if (requestId) params.requestId = requestId

  if (form.occurredRange?.length === 2) {
    const [occurredStart, occurredEnd] = form.occurredRange
    if (occurredStart && occurredEnd) {
      params.occurredStart = occurredStart
      params.occurredEnd = occurredEnd
    }
  }

  return params
}

export const logTypeLabel = (type: Api.SystemLog.LogType) => TYPE_LABELS[type]
export const logTypeTone = (type: Api.SystemLog.LogType) => TYPE_TONES[type]
export const logLevelLabel = (level: Api.SystemLog.LogLevel) => LEVEL_LABELS[level]
export const logLevelTone = (level: Api.SystemLog.LogLevel) => LEVEL_TONES[level]
export const logResultLabel = (result: Api.SystemLog.LogResult) => RESULT_LABELS[result]
export const logResultTone = (result: Api.SystemLog.LogResult) => RESULT_TONES[result]

export const formatLogText = (value?: string | null) => value?.trim() || '-'

export const formatLogDateTime = (value?: string | null) => formatLocalDateTime(value)

export const formatLogDuration = (durationMs?: number | null) =>
  typeof durationMs === 'number' && Number.isFinite(durationMs) && durationMs >= 0
    ? `${durationMs} ms`
    : '-'

export const formatLogOperator = (
  log: Pick<Api.SystemLog.LogListItem, 'operatorUserId' | 'operatorUsername'>
) => {
  const username = normalizedText(log.operatorUsername)
  if (username) return username
  return log.operatorUserId ? `管理员 ${log.operatorUserId}` : '未识别'
}

export const formatLogRequest = (
  log: Pick<Api.SystemLog.LogListItem, 'requestMethod' | 'requestPath'>
) => `${formatLogText(log.requestMethod)} ${formatLogText(log.requestPath)}`
