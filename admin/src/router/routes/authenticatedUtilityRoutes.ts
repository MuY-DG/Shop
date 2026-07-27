import type { AppRouteRecord } from '@/types/router'

export const USER_CENTER_ROUTE_PATH = '/system/user-center'

const hasUserCenterRoute = (routes: AppRouteRecord[]): boolean =>
  routes.some(
    (route) =>
      route.name === 'UserCenter' ||
      route.path === USER_CENTER_ROUTE_PATH ||
      (route.children?.length ? hasUserCenterRoute(route.children) : false)
  )

const createUserCenterRoute = (): AppRouteRecord => ({
  path: USER_CENTER_ROUTE_PATH,
  name: 'UserCenter',
  component: '/system/user-center',
  meta: {
    title: 'menus.system.userCenter',
    icon: 'ri:user-line',
    isHide: true,
    keepAlive: true,
    isHideTab: true
  }
})

/**
 * Adds authenticated pages that are available to every signed-in administrator
 * and therefore do not belong to an RBAC menu grant.
 */
export const appendAuthenticatedUtilityRoutes = (routes: AppRouteRecord[]): AppRouteRecord[] =>
  hasUserCenterRoute(routes) ? routes : [...routes, createUserCenterRoute()]
