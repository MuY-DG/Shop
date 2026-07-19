<template>
  <OperationReportPage :config="config" :load-report="loadReport" />
</template>

<script setup lang="ts">
  import { fetchOperationsOverview } from '@/api/operations'
  import OperationReportPage from '../components/operation-report-page.vue'
  import { adaptOverviewReport } from '../report-adapters'
  import type { OperationPageConfig, OperationPageLoader } from '../operations-state'

  defineOptions({ name: 'OperationsOverview' })

  const config: OperationPageConfig = {
    title: '运营概览',
    description: '汇总成交、用户、运营待办与最近业务动态，所有金额和用户数均来自真实业务事实。',
    trendTitle: '核心经营趋势',
    defaultMetricGroupTitle: '经营核心',
    metricDefinitions: [
      {
        key: 'paidAmountCent',
        title: '支付 GMV',
        unit: 'CENT',
        icon: 'ri:money-cny-circle-line',
        definition: '统计周期内支付成功订单的实付金额之和，后续退款不会抹掉原支付事实。',
        betterDirection: 'UP'
      },
      {
        key: 'netReceiptAmountCent',
        title: '净收款',
        unit: 'CENT',
        icon: 'ri:wallet-3-line',
        definition: '本周期支付 GMV 减去本周期成功退款金额，不等同于微信结算到账。',
        betterDirection: 'UP'
      },
      {
        key: 'paidOrderCount',
        title: '支付订单',
        unit: 'COUNT',
        icon: 'ri:file-list-3-line',
        definition: '统计周期内完成支付的去重订单数。',
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
        key: 'customerUnitPriceCent',
        title: '客单价',
        unit: 'CENT',
        icon: 'ri:price-tag-3-line',
        definition: '支付 GMV 除以支付用户数；无支付用户时显示不可计算。',
        betterDirection: 'UP'
      },
      {
        key: 'newUserCount',
        title: '新增用户',
        unit: 'COUNT',
        icon: 'ri:user-add-line',
        definition: '统计周期内完成注册的新用户数。',
        betterDirection: 'UP'
      }
    ]
  }

  const loadReport: OperationPageLoader = async (query) =>
    adaptOverviewReport(await fetchOperationsOverview(query))
</script>
