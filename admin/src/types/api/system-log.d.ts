declare namespace Api {
  namespace SystemLog {
    type LogType = 'LOGIN' | 'OPERATION' | 'ACCESS' | 'EXCEPTION'
    type LogLevel = 'INFO' | 'WARN' | 'ERROR'
    type LogResult = 'SUCCESS' | 'FAILURE'

    interface LogListItem {
      id: string
      type: LogType
      level: LogLevel
      result: LogResult
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
      errorMessage?: string
      createdAt: string
    }

    type LogPage = Api.Common.PaginatedResponse<LogListItem>

    type SearchParams = Partial<
      Api.Common.CommonSearchParams & {
        type: LogType
        result: LogResult
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
