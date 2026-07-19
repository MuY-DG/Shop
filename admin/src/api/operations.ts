import request from '@/utils/http'

const reportRequest = <T>(url: string, params: Api.Operations.ReportQuery) =>
  request.get<T>({
    url,
    params,
    showErrorMessage: false
  })

export function fetchOperationsOverview(params: Api.Operations.ReportQuery) {
  return reportRequest<Api.Operations.OverviewReport>('/admin/operations/overview', params)
}

export function fetchTradeStatistics(params: Api.Operations.ReportQuery) {
  return reportRequest<Api.Operations.TradeStatisticsReport>(
    '/admin/operations/trade-statistics',
    params
  )
}

export function fetchProductStatistics(params: Api.Operations.ReportQuery) {
  return reportRequest<Api.Operations.ProductStatisticsReport>(
    '/admin/operations/product-statistics',
    params
  )
}

export function fetchUserStatistics(params: Api.Operations.ReportQuery) {
  return reportRequest<Api.Operations.UserStatisticsReport>(
    '/admin/operations/user-statistics',
    params
  )
}

export function fetchTrafficStatistics(params: Api.Operations.ReportQuery) {
  return reportRequest<Api.Operations.TrafficStatisticsReport>(
    '/admin/operations/traffic-statistics',
    params
  )
}

export function fetchMarketingStatistics(params: Api.Operations.ReportQuery) {
  return reportRequest<Api.Operations.MarketingStatisticsReport>(
    '/admin/operations/marketing-statistics',
    params
  )
}

export function fetchServiceStatistics(params: Api.Operations.ReportQuery) {
  return reportRequest<Api.Operations.ServiceStatisticsReport>(
    '/admin/operations/service-statistics',
    params
  )
}
