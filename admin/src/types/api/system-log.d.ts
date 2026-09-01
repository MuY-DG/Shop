declare namespace Api {
  namespace SystemLog {
    type LogType = 'SECURITY' | 'OPERATION' | 'REQUEST' | 'EXCEPTION'
    type LogLevel = 'INFO' | 'WARN' | 'ERROR'
    type LogResult = 'SUCCESS' | 'FAILURE'

    interface LogListItem {
      id: string
      type: LogType
      level: LogLevel
      result: LogResult
      eventCode: string
      summary: string
      targetType: string
      targetId: string
      relatedTargetType?: string
      relatedTargetId?: string
      module: string
      action: string
      operatorUserId?: string
      operatorUsername: string
      requestMethod: string
      requestPath: string
      requestPattern?: string
      requestId: string
      clientIp: string
      userAgent: string
      statusCode: number
      durationMs: number
      errorCode?: string
      providerErrorCode?: string
      errorMessage?: string
      createdAt: string
    }

    type LogPage = Api.Common.PaginatedResponse<LogListItem>

    type SearchParams = Partial<
      Api.Common.CommonSearchParams & {
        type: LogType
        result: LogResult
        keyword: string
        module: string
        operator: string
        clientIp: string
        requestId: string
        occurredStart: string
        occurredEnd: string
      }
    >
  }
}
