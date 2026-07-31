import type { AppRouteRecord } from '@/types/router'

export const USER_CENTER_ROUTE_PATH = '/system/user-center'
export const GUEST_ROUTE_PATH = '/guest'

const isGuestRoute = (route: AppRouteRecord): boolean =>
  route.path === GUEST_ROUTE_PATH || route.name === 'GuestIntroduction'

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
 * Adds utility pages available to signed-in administrators. Guest accounts are
 * deliberately restricted to the guest introduction route returned by RBAC.
 */
export const appendAuthenticatedUtilityRoutes = (
  routes: AppRouteRecord[],
  roles: string[] = []
): AppRouteRecord[] => {
  if (roles.includes('R_GUEST')) {
    return routes.filter(isGuestRoute)
  }
  return hasUserCenterRoute(routes) ? routes : [...routes, createUserCenterRoute()]
}
