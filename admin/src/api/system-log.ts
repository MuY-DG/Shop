import request from '@/utils/http'

export function fetchSystemLogs(params: Api.SystemLog.SearchParams) {
  return request.get<Api.SystemLog.LogPage>({
    url: '/admin/system/logs',
    params
  })
}
