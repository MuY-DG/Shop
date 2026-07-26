export type QuotaProgressStatus = 'success' | 'warning' | 'exception'

type QuotaSnapshot = Pick<
  Api.ImageCompression.Config,
  'monthlyLimit' | 'compressionCount' | 'remainingCount'
>

export function calculateQuotaUsagePercentage(quota?: QuotaSnapshot | null): number | null {
  const limit = quota?.monthlyLimit
  if (limit == null || !Number.isFinite(limit) || limit <= 0) return null

  const used =
    quota?.compressionCount ??
    (quota?.remainingCount == null ? null : Math.max(limit - quota.remainingCount, 0))
  if (used == null || !Number.isFinite(used) || used < 0) return null

  return Math.min(100, Math.max(0, Math.round((used / limit) * 100)))
}

export function quotaProgressStatus(
  quota: QuotaSnapshot | null | undefined,
  percentage: number | null
): QuotaProgressStatus | undefined {
  if (quota?.remainingCount != null && quota.remainingCount <= 0) return 'exception'
  if (percentage == null) return undefined
  if (percentage >= 90) return 'warning'
  return 'success'
}

export function configSourceLabel(source: Api.ImageCompression.ConfigSource): string {
  const labels: Record<Api.ImageCompression.ConfigSource, string> = {
    AUTO: '自动选择',
    ENV: '配置文件 / 环境变量',
    DB: '后台加密配置'
  }
  return labels[source]
}

export function formatConfigDateTime(value?: string | null): string {
  if (!value) return '-'
  return value
    .replace('T', ' ')
    .replace(/\.\d+(?=Z|[+-]\d{2}:?\d{2}$|$)/, '')
    .replace(/Z$/, ' UTC')
}

export function formatAutoDisabledReason(value?: string | null): string {
  if (!value) return '未知'
  const labels: Record<string, string> = {
    QUOTA_EXHAUSTED: '本月额度已耗尽',
    INVALID_KEY: '密钥无效'
  }
  return labels[value] || value
}

export function canReuseDatabaseKey(config?: Api.ImageCompression.Config | null): boolean {
  if (!config?.keyConfigured) return false
  return (
    config.configSource === 'DB' ||
    (config.configSource === 'AUTO' && config.defaultConfigSource === 'DB')
  )
}

export function buildImageCompressionConfigPayload(
  form: Api.ImageCompression.ConfigForm
): Api.ImageCompression.ConfigForm {
  const payload: Api.ImageCompression.ConfigForm = {
    requestedEnabled: form.requestedEnabled,
    configSource: form.configSource
  }

  const apiKey = String(form.apiKey || '').trim()
  if (apiKey) payload.apiKey = apiKey
  if (form.monthlyLimit != null) payload.monthlyLimit = form.monthlyLimit

  return payload
}
