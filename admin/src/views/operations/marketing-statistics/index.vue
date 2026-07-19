<template>
  <OperationReportPage :config="config" :load-report="loadReport" />
</template>

<script setup lang="ts">
  import { fetchMarketingStatistics } from '@/api/operations'
  import OperationReportPage from '../components/operation-report-page.vue'
  import { adaptMarketingReport } from '../report-adapters'
  import type { OperationPageConfig, OperationPageLoader } from '../operations-state'

  defineOptions({ name: 'OperationsMarketingStatistics' })

  const config: OperationPageConfig = {
    title: '营销统计',
    description: '围绕优惠券发放、使用、过期和成交贡献评估营销效果；缺少投放成本时不伪造 ROI。',
    trendTitle: '优惠券发放与使用趋势',
    defaultMetricGroupTitle: '优惠券效果',
    metricDefinitions: [
      {
        key: 'issuedCouponCount',
        title: '发放优惠券',
        unit: 'COUNT',
        icon: 'ri:coupon-3-line',
        definition: '统计周期内用户领取或后台发放的优惠券数量。'
      },
      {
        key: 'usedCouponCount',
        title: '使用优惠券',
        unit: 'COUNT',
        icon: 'ri:coupon-2-line',
        definition: '统计周期内成功核销的优惠券数量。',
        betterDirection: 'UP'
      },
      {
        key: 'expiredCouponCount',
        title: '过期优惠券',
        unit: 'COUNT',
        icon: 'ri:timer-line',
        definition: '统计周期内到期且未使用的优惠券数量。',
        betterDirection: 'DOWN'
      },
      {
        key: 'couponUsageRate',
        title: '优惠券使用率',
        unit: 'BASIS_POINT',
        icon: 'ri:percent-line',
        definition: '使用优惠券数除以发放优惠券数；零分母时不可计算。',
        betterDirection: 'UP'
      },
      {
        key: 'couponDiscountCent',
        title: '优惠金额',
        unit: 'CENT',
        icon: 'ri:discount-percent-line',
        definition: '使用优惠券订单中由优惠券抵扣的金额。'
      },
      {
        key: 'couponPaidAmountCent',
        title: '用券支付金额',
        unit: 'CENT',
        icon: 'ri:money-cny-circle-line',
        definition: '使用优惠券并完成支付的订单实付金额。',
        betterDirection: 'UP'
      }
    ]
  }

  const loadReport: OperationPageLoader = async (query) =>
    adaptMarketingReport(await fetchMarketingStatistics(query))
</script>
