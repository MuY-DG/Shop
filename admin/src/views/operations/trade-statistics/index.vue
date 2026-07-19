<template>
  <OperationReportPage :config="config" :load-report="loadReport" />
</template>

<script setup lang="ts">
  import { fetchTradeStatistics } from '@/api/operations'
  import OperationReportPage from '../components/operation-report-page.vue'
  import { adaptTradeReport } from '../report-adapters'
  import type { OperationPageConfig, OperationPageLoader } from '../operations-state'

  defineOptions({ name: 'OperationsTradeStatistics' })

  const config: OperationPageConfig = {
    title: '交易统计',
    description: '观察订单创建、支付、退款和净收款，并拆解订单、支付、退款状态及小时分布。',
    trendTitle: '交易与退款趋势',
    defaultMetricGroupTitle: '交易核心',
    metricDefinitions: [
      {
        key: 'createdOrderCount',
        title: '创建订单',
        group: '成交规模',
        unit: 'COUNT',
        icon: 'ri:file-add-line',
        definition: '统计周期内新创建的订单数，包含未完成支付的订单。',
        betterDirection: 'UP'
      },
      {
        key: 'paidOrderCount',
        title: '支付订单',
        group: '成交规模',
        unit: 'COUNT',
        icon: 'ri:checkbox-circle-line',
        definition: '统计周期内完成支付的去重订单数。',
        betterDirection: 'UP'
      },
      {
        key: 'paidBuyerCount',
        title: '支付用户',
        group: '成交规模',
        unit: 'COUNT',
        icon: 'ri:user-star-line',
        definition: '统计周期内至少完成一笔支付的去重用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'paidAmountCent',
        title: '支付 GMV',
        group: '成交规模',
        unit: 'CENT',
        icon: 'ri:money-cny-circle-line',
        definition: '按订单支付时间统计的实付金额总和。',
        betterDirection: 'UP'
      },
      {
        key: 'successfulRefundCount',
        title: '成功退款笔数',
        group: '退款与订单金额',
        unit: 'COUNT',
        icon: 'ri:refund-line',
        definition: '按退款成功时间统计的成功退款记录数。',
        betterDirection: 'DOWN'
      },
      {
        key: 'successfulRefundAmountCent',
        title: '成功退款',
        group: '退款与订单金额',
        unit: 'CENT',
        icon: 'ri:refund-2-line',
        definition: '按退款成功时间统计的退款金额总和。',
        betterDirection: 'DOWN'
      },
      {
        key: 'netReceiptAmountCent',
        title: '净收款',
        group: '成交规模',
        unit: 'CENT',
        icon: 'ri:wallet-3-line',
        definition: '本周期支付 GMV 减去本周期成功退款。',
        betterDirection: 'UP'
      },
      {
        key: 'couponDiscountCent',
        title: '优惠金额',
        group: '退款与订单金额',
        unit: 'CENT',
        icon: 'ri:coupon-3-line',
        definition: '统计周期内支付成功订单的优惠券抵扣金额总和。'
      },
      {
        key: 'freightCent',
        title: '支付运费',
        group: '退款与订单金额',
        unit: 'CENT',
        icon: 'ri:truck-line',
        definition: '统计周期内支付成功订单包含的运费总和。'
      },
      {
        key: 'averageOrderAmountCent',
        title: '订单均价',
        group: '退款与订单金额',
        unit: 'CENT',
        icon: 'ri:shopping-basket-line',
        definition: '支付 GMV 除以支付订单数；零分母时显示不可计算。',
        betterDirection: 'UP'
      },
      {
        key: 'customerUnitPriceCent',
        title: '客单价',
        group: '退款与订单金额',
        unit: 'CENT',
        icon: 'ri:user-received-2-line',
        definition: '支付 GMV 除以支付用户数；零分母时显示不可计算。',
        betterDirection: 'UP'
      },
      {
        key: 'orderPaymentConversionRate',
        title: '下单支付转化率',
        group: '转化与履约效率',
        unit: 'BASIS_POINT',
        icon: 'ri:percent-line',
        definition: '统计周期内创建且最终已支付的订单数除以创建订单数；零分母时显示不可计算。',
        betterDirection: 'UP'
      },
      {
        key: 'createToPaySeconds',
        title: '平均下单支付耗时',
        group: '转化与履约效率',
        unit: 'SECOND',
        icon: 'ri:timer-line',
        definition: '统计区间内创建订单 cohort，从订单创建到支付成功的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'payToShipSeconds',
        title: '平均支付发货耗时',
        group: '转化与履约效率',
        unit: 'SECOND',
        icon: 'ri:truck-line',
        definition: '统计区间内创建订单 cohort，从支付成功到首次发货的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'shipToCompleteSeconds',
        title: '平均发货完成耗时',
        group: '转化与履约效率',
        unit: 'SECOND',
        icon: 'ri:flag-line',
        definition: '统计区间内创建订单 cohort，从首次发货到交易完成的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'paymentAttemptCount',
        title: '支付尝试',
        group: '转化与履约效率',
        unit: 'COUNT',
        icon: 'ri:bank-card-line',
        definition: '统计周期内发起的支付尝试次数；采集启用前的区间显示未采集。'
      },
      {
        key: 'paymentAttemptSuccessRate',
        title: '支付尝试成功率',
        group: '转化与履约效率',
        unit: 'BASIS_POINT',
        icon: 'ri:secure-payment-line',
        definition: '支付成功尝试数除以支付尝试总数；采集不完整或零分母时显示不可计算。',
        betterDirection: 'UP'
      }
    ]
  }

  const loadReport: OperationPageLoader = async (query) =>
    adaptTradeReport(await fetchTradeStatistics(query))
</script>
