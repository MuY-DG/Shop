import { AppRouteRecord } from '@/types/router'

export const operationsRoutes: AppRouteRecord = {
  name: 'Operations',
  path: '/operations',
  component: '/index/index',
  meta: {
    title: 'menus.operations.title',
    icon: 'ri:line-chart-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'overview',
      name: 'OperationsOverview',
      component: '/operations/overview',
      meta: { title: 'menus.operations.overview', icon: 'ri:dashboard-line', keepAlive: false }
    },
    {
      path: 'trade-statistics',
      name: 'OperationsTradeStatistics',
      component: '/operations/trade-statistics',
      meta: { title: 'menus.operations.trade', icon: 'ri:exchange-funds-line', keepAlive: false }
    },
    {
      path: 'product-statistics',
      name: 'OperationsProductStatistics',
      component: '/operations/product-statistics',
      meta: { title: 'menus.operations.product', icon: 'ri:shopping-bag-3-line', keepAlive: false }
    },
    {
      path: 'user-statistics',
      name: 'OperationsUserStatistics',
      component: '/operations/user-statistics',
      meta: { title: 'menus.operations.user', icon: 'ri:user-chart-line', keepAlive: false }
    },
    {
      path: 'traffic-statistics',
      name: 'OperationsTrafficStatistics',
      component: '/operations/traffic-statistics',
      meta: { title: 'menus.operations.traffic', icon: 'ri:route-line', keepAlive: false }
    },
    {
      path: 'marketing-statistics',
      name: 'OperationsMarketingStatistics',
      component: '/operations/marketing-statistics',
      meta: { title: 'menus.operations.marketing', icon: 'ri:coupon-3-line', keepAlive: false }
    },
    {
      path: 'service-statistics',
      name: 'OperationsServiceStatistics',
      component: '/operations/service-statistics',
      meta: {
        title: 'menus.operations.service',
        icon: 'ri:customer-service-2-line',
        keepAlive: false
      }
    }
  ]
}
