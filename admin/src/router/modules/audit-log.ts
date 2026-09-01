import { AppRouteRecord } from '@/types/router'

const logReadAuth = [{ title: '查看审计与日志', authMark: 'system:log:read' }]

export const auditLogRoutes: AppRouteRecord = {
  path: '/audit-log',
  name: 'AuditLog',
  component: '/index/index',
  redirect: '/audit-log/operation',
  meta: {
    title: 'menus.auditLog.title',
    icon: 'ri:file-shield-2-line',
    roles: ['R_SUPER']
  },
  children: [
    {
      path: 'operation',
      name: 'AuditOperation',
      component: '/system/log',
      meta: {
        title: 'menus.auditLog.operation',
        icon: 'ri:shield-check-line',
        keepAlive: true,
        roles: ['R_SUPER'],
        authList: logReadAuth
      }
    },
    {
      path: 'security',
      name: 'AuditSecurity',
      component: '/system/log',
      meta: {
        title: 'menus.auditLog.security',
        icon: 'ri:login-box-line',
        keepAlive: true,
        roles: ['R_SUPER'],
        authList: logReadAuth
      }
    },
    {
      path: 'exceptions',
      name: 'AuditException',
      component: '/system/log',
      meta: {
        title: 'menus.auditLog.exception',
        icon: 'ri:alarm-warning-line',
        keepAlive: true,
        roles: ['R_SUPER'],
        authList: logReadAuth
      }
    },
    {
      path: 'requests',
      name: 'AuditRequest',
      component: '/system/log',
      meta: {
        title: 'menus.auditLog.request',
        icon: 'ri:route-line',
        keepAlive: true,
        roles: ['R_SUPER'],
        authList: logReadAuth
      }
    },
    {
      path: 'tasks',
      name: 'AuditTask',
      component: '/system/task-log',
      meta: {
        title: 'menus.auditLog.task',
        icon: 'ri:timer-flash-line',
        keepAlive: true,
        roles: ['R_SUPER'],
        authList: [
          ...logReadAuth,
          { title: '查看清理任务配置', authMark: 'data-cleanup:config:read' }
        ]
      }
    }
  ]
}
