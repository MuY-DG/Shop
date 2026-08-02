export const DATA_CLEANUP_TASK_ORDER: Api.DataCleanup.TaskCode[] = [
  'ANALYTICS_EVENT',
  'ADMIN_SYSTEM_LOG',
  'CUSTOMER_SERVICE_MESSAGE',
  'ORDER_AGGREGATE',
  'STORAGE_ASSET',
  'DIRECT_UPLOAD_SESSION'
]

export interface DataCleanupTaskPresentation {
  title: string
  description: string
  retentionLabel: string
  batchLabel: string
  complianceWarning?: string
}

export const DATA_CLEANUP_TASK_PRESENTATION: Record<
  Api.DataCleanup.TaskCode,
  DataCleanupTaskPresentation
> = {
  ANALYTICS_EVENT: {
    title: '访问统计',
    description: '删除超过保留期限的访问与行为统计数据。',
    retentionLabel: '统计保留天数',
    batchLabel: '单批记录上限'
  },
  ADMIN_SYSTEM_LOG: {
    title: '系统日志',
    description: '删除超过保留期限的后台操作与系统日志。',
    retentionLabel: '日志保留天数',
    batchLabel: '单批记录上限'
  },
  CUSTOMER_SERVICE_MESSAGE: {
    title: '客服消息',
    description: '删除超过保留期限的客服消息，关联素材由素材清理任务继续回收。',
    retentionLabel: '消息保留天数',
    batchLabel: '单批消息上限',
    complianceWarning: '启用前请确认消息保留期限符合适用法规、售后及争议处理政策。'
  },
  ORDER_AGGREGATE: {
    title: '订单及关联数据',
    description:
      '清理超过保留期限的订单、订单项及商品快照、支付与回调、退款、售后与附件、物流、状态日志和客服关联。',
    retentionLabel: '订单保留天数',
    batchLabel: '单批订单上限',
    complianceWarning:
      '订单及关联数据会先写入私有归档，再从热库删除；请确认保留期限符合财税、售后和争议处理要求。'
  },
  STORAGE_ASSET: {
    title: '素材文件',
    description: '回收过期、删除待处理、上传未完成及失去业务引用的素材。',
    retentionLabel: '素材保留天数',
    batchLabel: '单批素材上限'
  },
  DIRECT_UPLOAD_SESSION: {
    title: '直传会话',
    description: '清理直传暂存对象，并删除超过保留期限的已结束会话记录。',
    retentionLabel: '会话保留天数',
    batchLabel: '单批会话上限'
  }
}

const TASK_ORDER_INDEX = new Map(
  DATA_CLEANUP_TASK_ORDER.map((taskCode, index) => [taskCode, index])
)

export function createDataCleanupConfigForm(
  config: Api.DataCleanup.Config
): Api.DataCleanup.ConfigForm {
  return {
    revision: config.revision,
    tasks: [...config.tasks]
      .sort(
        (left, right) =>
          (TASK_ORDER_INDEX.get(left.taskCode) ?? Number.MAX_SAFE_INTEGER) -
          (TASK_ORDER_INDEX.get(right.taskCode) ?? Number.MAX_SAFE_INTEGER)
      )
      .map((task) => ({
        taskCode: task.taskCode,
        enabled: task.enabled,
        retentionDays: task.retentionDays ?? null,
        batchSize: task.batchSize,
        cronExpression: task.cronExpression,
        batchIntervalSeconds: task.batchIntervalSeconds,
        uploadPendingGraceMinutes: task.uploadPendingGraceMinutes ?? null,
        retainReviews: task.taskCode === 'ORDER_AGGREGATE' ? (task.retainReviews ?? true) : null
      }))
  }
}

export function toDataCleanupConfigPayload(
  config: Api.DataCleanup.ConfigForm
): Api.DataCleanup.ConfigForm {
  return {
    revision: config.revision,
    tasks: config.tasks.map((task) => ({
      taskCode: task.taskCode,
      enabled: task.enabled,
      retentionDays: task.retentionDays,
      batchSize: task.batchSize,
      cronExpression: task.cronExpression.trim(),
      batchIntervalSeconds: task.batchIntervalSeconds,
      uploadPendingGraceMinutes: task.uploadPendingGraceMinutes,
      retainReviews: task.taskCode === 'ORDER_AGGREGATE' ? task.retainReviews !== false : null
    }))
  }
}

export function dataCleanupConfigSnapshot(config: Api.DataCleanup.ConfigForm): string {
  return JSON.stringify(toDataCleanupConfigPayload(config))
}
