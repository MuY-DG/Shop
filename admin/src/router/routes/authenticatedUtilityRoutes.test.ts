import assert from 'node:assert/strict'
import test from 'node:test'
import {
  appendAuthenticatedUtilityRoutes,
  USER_CENTER_ROUTE_PATH
} from './authenticatedUtilityRoutes'
import { RoutePermissionValidator } from '../core/RoutePermissionValidator'

test('backend menus always include a hidden personal center route', () => {
  const menu = [
    {
      path: '/dashboard',
      name: 'Dashboard',
      component: '/index/index',
      meta: { title: 'Dashboard' },
      children: []
    }
  ]

  const result = appendAuthenticatedUtilityRoutes(menu)
  const userCenter = result.at(-1)

  assert.equal(result.length, 2)
  assert.equal(menu.length, 1)
  assert.equal(userCenter?.path, USER_CENTER_ROUTE_PATH)
  assert.equal(userCenter?.name, 'UserCenter')
  assert.equal(userCenter?.component, '/system/user-center')
  assert.equal(userCenter?.meta.isHide, true)
  assert.equal(userCenter?.meta.isHideTab, true)
  assert.equal(RoutePermissionValidator.hasPermission(USER_CENTER_ROUTE_PATH, result), true)
})

test('an existing personal center route is not duplicated', () => {
  const menu = [
    {
      path: '/system',
      name: 'System',
      component: '/index/index',
      meta: { title: 'System' },
      children: [
        {
          path: 'user-center',
          name: 'UserCenter',
          component: '/system/user-center',
          meta: { title: 'Personal center' }
        }
      ]
    }
  ]

  assert.equal(appendAuthenticatedUtilityRoutes(menu), menu)
})
