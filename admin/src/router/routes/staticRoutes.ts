import { AppRouteRecordRaw } from '@/utils/router'

/**
 * 静态路由配置（不需要权限就能访问的路由）
 *
 * 属性说明：
 * isHideTab: true 表示不在标签页中显示
 *
 * 注意事项：
 * 1、path、name 不要和动态路由冲突，否则会导致路由冲突无法访问
 * 2、静态路由不管是否登录都可以访问
 */
export const staticRoutes: AppRouteRecordRaw[] = [
  // 不需要登录就能访问的路由示例
  // {
  //   path: '/welcome',
  //   name: 'WelcomeStatic',
  //   component: () => import('@views/dashboard/console/index.vue'),
  //   meta: { title: 'menus.dashboard.title' }
  // },
  {
    path: '/auth/login',
    name: 'Login',
    component: () => import('@views/auth/login/index.vue'),
    meta: { title: 'menus.login.title', isHideTab: true }
  },
  {
    path: '/auth/register',
    name: 'Register',
    component: () => import('@views/auth/register/index.vue'),
    meta: { title: 'menus.register.title', isHideTab: true }
  },
  {
    path: '/auth/forget-password',
    name: 'ForgetPassword',
    component: () => import('@views/auth/forget-password/index.vue'),
    meta: { title: 'menus.forgetPassword.title', isHideTab: true }
  },
  {
    path: '/order/list',
    name: 'LegacyOrderListRedirect',
    redirect: (to) => ({ path: '/trade/orders', query: to.query }),
    meta: { title: '订单列表', isHideTab: true }
  },
  {
    path: '/aftersale/list',
    name: 'LegacyAfterSaleListRedirect',
    redirect: (to) => ({ path: '/trade/after-sales', query: to.query }),
    meta: { title: '售后列表', isHideTab: true }
  },
  {
    path: '/dashboard',
    name: 'LegacyDashboardRedirect',
    redirect: '/operations/overview',
    meta: { title: '运营概览', isHideTab: true }
  },
  {
    path: '/dashboard/console',
    name: 'LegacyDashboardConsoleRedirect',
    redirect: '/operations/overview',
    meta: { title: '运营概览', isHideTab: true }
  },
  {
    path: '/decoration/banner',
    name: 'LegacyHomeBannerRedirect',
    redirect: '/decoration/home?section=banner',
    meta: { title: '首页装修', isHideTab: true }
  },
  {
    path: '/decoration/category',
    name: 'LegacyHomeCategoryRedirect',
    redirect: '/decoration/home?section=category',
    meta: { title: '首页装修', isHideTab: true }
  },
  {
    path: '/decoration/hot-products',
    name: 'LegacyHomeHotRedirect',
    redirect: '/decoration/home?section=hot',
    meta: { title: '首页装修', isHideTab: true }
  },
  {
    path: '/decoration/recommended-products',
    name: 'LegacyHomeRecommendedRedirect',
    redirect: '/decoration/home?section=recommended',
    meta: { title: '首页装修', isHideTab: true }
  },
  {
    path: '/403',
    name: 'Exception403',
    component: () => import('@views/exception/403/index.vue'),
    meta: { title: '403', isHideTab: true }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'Exception404',
    component: () => import('@views/exception/404/index.vue'),
    meta: { title: '404', isHideTab: true }
  },
  {
    path: '/500',
    name: 'Exception500',
    component: () => import('@views/exception/500/index.vue'),
    meta: { title: '500', isHideTab: true }
  },
  {
    path: '/outside',
    component: () => import('@views/index/index.vue'),
    name: 'Outside',
    meta: { title: 'menus.outside.title' },
    children: [
      // iframe 内嵌页面
      {
        path: '/outside/iframe/:path',
        name: 'Iframe',
        component: () => import('@/views/outside/Iframe.vue'),
        meta: { title: 'iframe' }
      }
    ]
  }
]
