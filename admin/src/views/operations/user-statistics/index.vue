<template>
  <OperationReportPage :config="config" :load-report="loadReport" />
</template>

<script setup lang="ts">
  import { fetchUserStatistics } from '@/api/operations'
  import OperationReportPage from '../components/operation-report-page.vue'
  import { adaptUserReport } from '../report-adapters'
  import type { OperationPageConfig, OperationPageLoader } from '../operations-state'

  defineOptions({ name: 'OperationsUserStatistics' })

  const config: OperationPageConfig = {
    title: '用户统计',
    description: '统计注册、服务端活跃、首购与复购，活跃数据仅从采集启用日起准确展示。',
    trendTitle: '注册、活跃与支付用户趋势',
    defaultMetricGroupTitle: '用户增长与价值',
    metricDefinitions: [
      {
        key: 'totalUserCount',
        title: '累计用户',
        unit: 'COUNT',
        icon: 'ri:group-line',
        definition: '截至统计结束日已注册的累计用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'newUserCount',
        title: '新增用户',
        unit: 'COUNT',
        icon: 'ri:user-add-line',
        definition: '统计周期内完成注册的新用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'activeUserCount',
        title: '活跃用户',
        unit: 'COUNT',
        icon: 'ri:user-heart-line',
        definition: '统计周期内产生已认证业务请求的去重用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'paidBuyerCount',
        title: '支付用户',
        unit: 'COUNT',
        icon: 'ri:user-star-line',
        definition: '统计周期内至少完成一笔支付的去重用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'firstPurchaseUserCount',
        title: '首购用户',
        unit: 'COUNT',
        icon: 'ri:medal-line',
        definition: '首次完成支付且首购时间落在统计周期内的用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'repeatBuyerCount',
        title: '复购用户',
        unit: 'COUNT',
        icon: 'ri:user-follow-line',
        definition: '截至统计结束日累计支付至少两单，且本周期内有支付的用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'repeatBuyerRate',
        title: '复购率',
        unit: 'BASIS_POINT',
        icon: 'ri:repeat-2-line',
        definition: '统计周期内复购用户数除以支付用户数；零分母时不可计算。',
        betterDirection: 'UP'
      },
      {
        key: 'phoneAuthorizedUserCount',
        title: '手机授权用户',
        unit: 'COUNT',
        icon: 'ri:smartphone-line',
        definition: '截至统计结束日已完成手机号授权的累计用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'phoneAuthorizationRate',
        title: '手机授权率',
        unit: 'BASIS_POINT',
        icon: 'ri:phone-lock-line',
        definition: '手机授权用户数除以累计用户数；零分母时不可计算。',
        betterDirection: 'UP'
      }
    ]
  }

  const loadReport: OperationPageLoader = async (query) =>
    adaptUserReport(await fetchUserStatistics(query))
</script>
