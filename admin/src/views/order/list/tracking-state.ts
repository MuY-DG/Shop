const TRACKING_SYNC_STATUS_TEXT: Record<Api.Order.TrackingSyncStatus, string> = {
  NOT_REQUESTED: '尚未同步',
  SYNCING: '同步中',
  SYNCED: '同步成功',
  UNSUPPORTED: '暂不支持',
  FAILED: '同步失败',
  UNKNOWN: '结果待确认',
  UNAVAILABLE: '服务暂不可用'
}

export function formatTrackingSyncStatus(status: Api.Order.TrackingSyncStatus): string {
  return TRACKING_SYNC_STATUS_TEXT[status] || '未知状态'
}

export function formatTrackingSourceError(code?: string | null, message?: string | null): string {
  const normalizedCode = code?.trim() || ''
  const normalizedMessage = message?.trim() || ''
  if (!normalizedCode && !normalizedMessage) return '-'
  if (!normalizedCode) return normalizedMessage
  if (!normalizedMessage) return normalizedCode
  return `${normalizedCode}：${normalizedMessage}`
}

export function trackingPathEmptyText(
  tracking: Pick<
    Api.Order.ShipmentTracking,
    'pathSupported' | 'pathSyncStatus' | 'pathItems'
  > | null
): string {
  if (!tracking) return '尚未读取微信物流数据'
  if (tracking.pathItems.length > 0) return ''
  if (!tracking.pathSupported || tracking.pathSyncStatus === 'UNSUPPORTED') {
    return '当前运单不具备 getPath 查询条件'
  }
  if (tracking.pathSyncStatus === 'SYNCED') return 'getPath 已同步，当前暂无轨迹节点'
  if (tracking.pathSyncStatus === 'NOT_REQUESTED') return 'getPath 尚未同步'
  if (tracking.pathSyncStatus === 'SYNCING') return 'getPath 正在同步'
  return 'getPath 暂不可用，已保留最近一次成功轨迹（如有）'
}
