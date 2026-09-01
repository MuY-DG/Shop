import { AppRouteRecord } from '@/types/router'
import { auditLogRoutes } from './audit-log'
import { operationsRoutes } from './operations'
import { systemRoutes } from './system'

/**
 * 导出所有模块化路由
 */
export const routeModules: AppRouteRecord[] = [operationsRoutes, auditLogRoutes, systemRoutes]
