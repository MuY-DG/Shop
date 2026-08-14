import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import test from 'node:test'
import { routeModules } from '../modules'
import { staticRoutes } from '../routes/staticRoutes'
import { resolveViewComponentCandidates } from './ComponentLoader'

const demoPrefixes = [
  '/article',
  '/change',
  '/examples',
  '/result',
  '/safeguard',
  '/template',
  '/widgets'
]

const flattenPaths = (routes: Array<{ path: string; children?: unknown[] }>): string[] =>
  routes.flatMap((route) => [
    route.path,
    ...flattenPaths((route.children ?? []) as Array<{ path: string; children?: unknown[] }>)
  ])

test('static and fallback business routes expose no template demo entries', () => {
  const paths = [
    ...flattenPaths(staticRoutes as Array<{ path: string; children?: unknown[] }>),
    ...flattenPaths(routeModules as Array<{ path: string; children?: unknown[] }>)
  ]

  for (const path of paths) {
    assert.equal(
      demoPrefixes.some((prefix) => path === prefix || path.startsWith(`${prefix}/`)),
      false,
      `unexpected demo route: ${path}`
    )
  }
})

test('component path resolution still points to real commerce menu views', () => {
  const businessComponents = [
    '/account-rights/index',
    '/aftersale/list',
    '/compliance/documents',
    '/compliance/merchant',
    '/configuration/data-cleanup',
    '/configuration/wechat-platform',
    '/configuration/wechat-service-card',
    '/content/contact',
    '/content/home-decoration',
    '/customer-service/index',
    '/customer-service/overview',
    '/customer-service/settings',
    '/customer-service-management/members',
    '/customer/user',
    '/development/storage',
    '/finance/reconciliation/index',
    '/guest/index',
    '/index/index',
    '/marketing/coupon',
    '/marketing/coupon-claim',
    '/order/list',
    '/order/logistics-config',
    '/operations/marketing-statistics',
    '/operations/overview',
    '/operations/product-statistics',
    '/operations/service-statistics',
    '/operations/trade-statistics',
    '/operations/traffic-statistics',
    '/operations/user-statistics',
    '/payment/config',
    '/product/category',
    '/product/guarantee-service',
    '/product/parameter',
    '/product/review',
    '/product/spec-template',
    '/product/spu',
    '/storage/files',
    '/system/log',
    '/system/menu',
    '/system/role',
    '/system/user',
    '/system/user-center'
  ]

  for (const componentPath of businessComponents) {
    const candidates = resolveViewComponentCandidates(componentPath)
    assert.equal(
      candidates.some((candidate) => existsSync(new URL(candidate, import.meta.url))),
      true,
      `missing business component: ${componentPath}`
    )
  }
})
