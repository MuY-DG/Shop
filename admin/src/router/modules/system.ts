import { AppRouteRecord } from '@/types/router'

export const systemRoutes: AppRouteRecord = {
  path: '/system',
  name: 'System',
  component: '/index/index',
  meta: {
    title: 'menus.system.title',
    icon: 'ri:user-3-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'user',
      name: 'User',
      component: '/system/user',
      meta: {
        title: 'menus.system.user',
        icon: 'ri:user-line',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN'],
        authList: [
          { title: '查看管理员', authMark: 'system:user:read' },
          { title: '创建管理员', authMark: 'system:user:create' },
          { title: '编辑管理员', authMark: 'system:user:update' },
          { title: '停用管理员', authMark: 'system:user:disable' },
          { title: '查看登录设备', authMark: 'system:user:session:read' },
          { title: '下线登录设备', authMark: 'system:user:session:revoke' }
        ]
      }
    },
    {
      path: 'role',
      name: 'Role',
      component: '/system/role',
      meta: {
        title: 'menus.system.role',
        icon: 'ri:user-settings-line',
        keepAlive: true,
        roles: ['R_SUPER']
      }
    },
    {
      path: 'user-center',
      name: 'UserCenter',
      component: '/system/user-center',
      meta: {
        title: 'menus.system.userCenter',
        icon: 'ri:user-line',
        isHide: true,
        keepAlive: true,
        isHideTab: true
      }
    },
    {
      path: 'menu',
      name: 'Menus',
      component: '/system/menu',
      meta: {
        title: 'menus.system.menu',
        icon: 'ri:menu-line',
        keepAlive: true,
        roles: ['R_SUPER'],
        authList: [
          { title: '新增', authMark: 'add' },
          { title: '编辑', authMark: 'edit' },
          { title: '删除', authMark: 'delete' }
        ]
      }
    },
    {
      path: 'log',
      name: 'SystemLog',
      component: '/system/log',
      meta: {
        title: 'menus.system.log',
        icon: 'ri:file-list-3-line',
        keepAlive: true,
        roles: ['R_SUPER'],
        authList: [{ title: '查看日志', authMark: 'system:log:read' }]
      }
    }
  ]
}
