<template>
  <OperationReportPage :config="config" :load-report="loadReport" />
</template>

<script setup lang="ts">
  import { fetchTrafficStatistics } from '@/api/operations'
  import OperationReportPage from '../components/operation-report-page.vue'
  import { adaptTrafficReport } from '../report-adapters'
  import type { OperationPageConfig, OperationPageLoader } from '../operations-state'

  defineOptions({ name: 'OperationsTrafficStatistics' })

  const config: OperationPageConfig = {
    title: '流量转化',
    description: '查看 PV、访客、会话、入口和页面行为，并以服务端可信订单事实衔接转化漏斗。',
    trendTitle: '流量趋势',
    defaultMetricGroupTitle: '流量核心',
    metricDefinitions: [
      {
        key: 'pageViewCount',
        title: '页面浏览量',
        unit: 'COUNT',
        icon: 'ri:eye-line',
        definition: '统计周期内受控 PAGE_VIEW 事件数。',
        betterDirection: 'UP'
      },
      {
        key: 'visitorCount',
        title: '访客数',
        unit: 'COUNT',
        icon: 'ri:footprint-line',
        definition: '统计周期内匿名访客标识去重数。',
        betterDirection: 'UP'
      },
      {
        key: 'sessionCount',
        title: '会话数',
        unit: 'COUNT',
        icon: 'ri:window-line',
        definition: '统计周期内小程序启动会话标识去重数。',
        betterDirection: 'UP'
      },
      {
        key: 'loginActiveUserCount',
        title: '登录活跃用户',
        unit: 'COUNT',
        icon: 'ri:user-follow-line',
        definition: '行为事件中已由服务端关联用户的去重用户数。',
        betterDirection: 'UP'
      }
    ]
  }

  const loadReport: OperationPageLoader = async (query) =>
    adaptTrafficReport(await fetchTrafficStatistics(query))
</script>
