declare namespace Api {
  namespace DataCleanup {
    type TaskCode =
      | 'ANALYTICS_EVENT'
      | 'ADMIN_SYSTEM_LOG'
      | 'CUSTOMER_SERVICE_MESSAGE'
      | 'ORDER_AGGREGATE'
      | 'STORAGE_ASSET'
      | 'DIRECT_UPLOAD_SESSION'

    type RunStatus = 'NEVER' | 'RUNNING' | 'SUCCESS' | 'FAILED'

    interface TaskConfig {
      taskCode: TaskCode
      title?: string
      description?: string
      enabled: boolean
      retentionDays?: number | null
      minRetentionDays?: number | null
      maxRetentionDays?: number | null
      batchSize: number
      maxBatchSize?: number
      cronExpression: string
      zoneId?: string
      batchIntervalSeconds: number
      uploadPendingGraceMinutes?: number | null
      retainReviews: boolean | null
      nextRunAt?: string | null
      lastStartedAt?: string | null
      lastCompletedAt?: string | null
      lastStatus?: RunStatus | string | null
      lastProcessedCount?: number | null
      lastError?: string | null
      updatedAt?: string | null
    }

    interface Config {
      revision: number
      tasks: TaskConfig[]
    }

    interface TaskConfigForm {
      taskCode: TaskCode
      enabled: boolean
      retentionDays: number | null
      batchSize: number
      cronExpression: string
      batchIntervalSeconds: number
      uploadPendingGraceMinutes: number | null
      retainReviews: boolean | null
    }

    interface ConfigForm {
      revision: number
      tasks: TaskConfigForm[]
    }
  }
}
