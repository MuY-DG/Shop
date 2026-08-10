/**
 * 快速入口配置
 * 包含：应用列表、快速链接等配置
 */
import type { FastEnterConfig } from '@/types/config'

const fastEnterConfig: FastEnterConfig = {
  // 显示条件（屏幕宽度）
  minWidth: 1200,
  // 应用列表
  applications: [
    {
      name: '运营概览',
      description: '经营指标、运营待办与最近成交',
      icon: 'ri:pie-chart-line',
      iconColor: '#377dff',
      enabled: true,
      order: 1,
      routeName: 'OperationsOverview'
    },
    {
      name: '订单管理',
      description: '处理订单、发货与物流',
      icon: 'ri:file-list-3-line',
      iconColor: '#F9901F',
      enabled: true,
      order: 2,
      routeName: 'OrderList'
    },
    {
      name: '商品管理',
      description: '维护商品、规格与库存',
      icon: 'ri:shopping-bag-3-line',
      iconColor: '#13DEB9',
      enabled: true,
      order: 3,
      routeName: 'ProductSpu'
    }
  ],
  // 快速链接
  quickLinks: [
    {
      name: '个人中心',
      enabled: true,
      order: 1,
      routeName: 'UserCenter'
    }
  ]
}

export default Object.freeze(fastEnterConfig)
