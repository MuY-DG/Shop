<template>
  <OperationReportPage :config="config" :load-report="loadReport" />
</template>

<script setup lang="ts">
  import { fetchServiceStatistics } from '@/api/operations'
  import OperationReportPage from '../components/operation-report-page.vue'
  import { adaptServiceReport } from '../report-adapters'
  import type { OperationPageConfig, OperationPageLoader } from '../operations-state'

  defineOptions({ name: 'OperationsServiceStatistics' })

  const config: OperationPageConfig = {
    title: '服务统计',
    description: '统一观察发货履约、微信发货、售后退款和客服队列，及时发现当前运营积压。',
    trendTitle: '履约与服务趋势',
    defaultMetricGroupTitle: '服务核心',
    metricDefinitions: [
      {
        key: 'toShipOrderCount',
        title: '待发货订单',
        group: '履约',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:truck-line',
        definition: '当前已支付且尚未发货的订单数，不受日期范围影响。',
        betterDirection: 'DOWN'
      },
      {
        key: 'overdueToShipOrderCount',
        title: '超时未发货',
        group: '履约',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:alarm-warning-line',
        definition: '当前超过履约时限仍未发货的订单数。',
        betterDirection: 'DOWN'
      },
      {
        key: 'averageShippingSeconds',
        title: '平均发货耗时',
        group: '履约',
        unit: 'SECOND',
        icon: 'ri:timer-2-line',
        definition: '统计周期内订单从支付成功到首次发货的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'averageCompletionSeconds',
        title: '平均完成耗时',
        group: '履约',
        unit: 'SECOND',
        icon: 'ri:timer-flash-line',
        definition: '统计周期内支付订单从首次发货到交易完成的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'afterSaleApplicationCount',
        title: '售后申请',
        group: '售后',
        unit: 'COUNT',
        icon: 'ri:customer-service-line',
        definition: '统计周期内新创建的售后申请数量。',
        betterDirection: 'DOWN'
      },
      {
        key: 'approvedAfterSaleCount',
        title: '售后通过',
        group: '售后',
        unit: 'COUNT',
        icon: 'ri:checkbox-circle-line',
        definition: '统计周期内完成审核且结果为通过的售后申请数。'
      },
      {
        key: 'rejectedAfterSaleCount',
        title: '售后拒绝',
        group: '售后',
        unit: 'COUNT',
        icon: 'ri:close-circle-line',
        definition: '统计周期内完成审核且结果为拒绝的售后申请数。'
      },
      {
        key: 'afterSaleApprovalRate',
        title: '售后通过率',
        group: '售后',
        unit: 'BASIS_POINT',
        icon: 'ri:percent-line',
        definition: '统计周期内审核通过数除以已完成审核数；零分母时不可计算。'
      },
      {
        key: 'averageAfterSaleReviewSeconds',
        title: '平均售后审核耗时',
        group: '售后',
        unit: 'SECOND',
        icon: 'ri:timer-line',
        definition: '统计周期内完成审核的售后申请，从提交到审核完成的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'successfulRefundCount',
        title: '退款成功',
        group: '退款',
        unit: 'COUNT',
        icon: 'ri:refund-2-line',
        definition: '统计周期内成功完成退款的记录数。'
      },
      {
        key: 'failedRefundCount',
        title: '退款失败',
        group: '退款',
        unit: 'COUNT',
        icon: 'ri:error-warning-line',
        definition: '统计周期内更新为失败状态的退款记录数。',
        betterDirection: 'DOWN'
      },
      {
        key: 'refundSuccessRate',
        title: '退款成功率',
        group: '退款',
        unit: 'BASIS_POINT',
        icon: 'ri:checkbox-circle-line',
        definition: '统计周期内退款成功数除以成功与失败结果总数；零分母时不可计算。',
        betterDirection: 'UP'
      },
      {
        key: 'averageRefundProcessingSeconds',
        title: '平均退款处理耗时',
        group: '退款',
        unit: 'SECOND',
        icon: 'ri:hourglass-line',
        definition: '统计周期内成功退款从发起到成功完成的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'successfulRefundAmountCent',
        title: '退款金额',
        group: '退款',
        unit: 'CENT',
        icon: 'ri:money-cny-circle-line',
        definition: '统计周期内成功完成退款的金额总和。'
      },
      {
        key: 'waitingConversationCount',
        title: '待接待会话',
        group: '客服',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:chat-new-line',
        definition: '当前仍在等待客服接待的会话数，不受日期范围影响。',
        betterDirection: 'DOWN'
      },
      {
        key: 'activeConversationCount',
        title: '服务中会话',
        group: '客服',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:chat-check-line',
        definition: '当前正在由客服处理的会话数，不受日期范围影响。'
      },
      {
        key: 'adminUnreadMessageCount',
        title: '客服未读消息',
        group: '客服',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:message-3-line',
        definition: '当前客服侧尚未读取的用户消息总数，不受日期范围影响。',
        betterDirection: 'DOWN'
      },
      {
        key: 'conversationCount',
        title: '咨询会话',
        group: '客服',
        unit: 'COUNT',
        icon: 'ri:question-answer-line',
        definition: '统计周期内开始的新一轮客服咨询数量。'
      },
      {
        key: 'averageFirstResponseSeconds',
        title: '平均首响耗时',
        group: '客服',
        unit: 'SECOND',
        icon: 'ri:speed-line',
        definition: '统计周期内已响应咨询从用户发起到首条客服回复的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'averageResolutionSeconds',
        title: '平均解决耗时',
        group: '客服',
        unit: 'SECOND',
        icon: 'ri:hourglass-line',
        definition: '统计周期内已关闭咨询从发起到关闭的平均耗时。',
        betterDirection: 'DOWN'
      },
      {
        key: 'conversationCloseRate',
        title: '会话关闭率',
        group: '客服',
        unit: 'BASIS_POINT',
        icon: 'ri:chat-off-line',
        definition: '统计周期内已关闭咨询数除以咨询总数；零分母时不可计算。',
        betterDirection: 'UP'
      },
      {
        key: 'conversationTransferRate',
        title: '会话转接率',
        group: '客服',
        unit: 'BASIS_POINT',
        icon: 'ri:share-forward-line',
        definition: '统计周期内发生过转接的咨询数除以咨询总数；零分母时不可计算。',
        betterDirection: 'DOWN'
      }
    ]
  }

  const loadReport: OperationPageLoader = async (query) =>
    adaptServiceReport(await fetchServiceStatistics(query))
</script>
