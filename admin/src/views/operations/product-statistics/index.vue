<template>
  <OperationReportPage :config="config" :load-report="loadReport" />
</template>

<script setup lang="ts">
  import { fetchProductStatistics } from '@/api/operations'
  import OperationReportPage from '../components/operation-report-page.vue'
  import { adaptProductReport } from '../report-adapters'
  import type { OperationPageConfig, OperationPageLoader } from '../operations-state'

  defineOptions({ name: 'OperationsProductStatistics' })

  const config: OperationPageConfig = {
    title: '商品统计',
    description: '以真实支付商品和订单商品快照统计销量、退款、库存预警与经营排行，不计入虚拟销量。',
    trendTitle: '商品成交趋势',
    defaultMetricGroupTitle: '商品经营核心',
    metricDefinitions: [
      {
        key: 'activeSpuCount',
        title: '有效商品',
        group: '商品与库存快照',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:archive-stack-line',
        definition: '当前未删除且未彻底清理的 SPU 数量，不随日期范围变化。'
      },
      {
        key: 'onSaleSpuCount',
        title: '在售商品',
        group: '商品与库存快照',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:shopping-bag-3-line',
        definition: '当前处于在售状态的 SPU 数量，不随日期范围变化。'
      },
      {
        key: 'enabledSkuCount',
        title: '启用 SKU',
        group: '商品与库存快照',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:barcode-box-line',
        definition: '当前有效商品下处于启用状态的 SKU 数量，不随日期范围变化。'
      },
      {
        key: 'totalAvailableStock',
        title: '可用库存',
        group: '商品与库存快照',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:inbox-archive-line',
        definition: '当前有效商品下启用 SKU 的可用库存总和，不随日期范围变化。'
      },
      {
        key: 'outOfStockSkuCount',
        title: '缺货 SKU',
        group: '商品与库存快照',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:close-circle-line',
        definition: '当前在售商品下已启用且可用库存为零的 SKU 数量。',
        betterDirection: 'DOWN'
      },
      {
        key: 'lowStockSkuCount',
        title: '低库存 SKU',
        group: '商品与库存快照',
        comparisonMode: 'SNAPSHOT',
        unit: 'COUNT',
        icon: 'ri:alarm-warning-line',
        definition: '当前在售商品下已启用、库存大于零且不高于预警阈值的 SKU 数量。',
        betterDirection: 'DOWN'
      },
      {
        key: 'soldQuantity',
        title: '支付件数',
        group: '成交与盈利',
        unit: 'COUNT',
        icon: 'ri:shopping-cart-2-line',
        definition: '统计周期内已支付订单商品件数，不包含商品虚拟销量。',
        betterDirection: 'UP'
      },
      {
        key: 'refundedQuantity',
        title: '退款商品件数',
        group: '成交与盈利',
        unit: 'COUNT',
        icon: 'ri:arrow-go-back-line',
        definition: '统计周期内成功退款记录关联订单的商品件数。',
        betterDirection: 'DOWN'
      },
      {
        key: 'netSoldQuantity',
        title: '净支付件数',
        group: '成交与盈利',
        unit: 'COUNT',
        icon: 'ri:scales-3-line',
        definition: '本周期支付商品件数减去本周期成功退款记录关联的商品件数。',
        betterDirection: 'UP'
      },
      {
        key: 'paidItemAmountCent',
        title: '支付商品毛额',
        group: '成交与盈利',
        unit: 'CENT',
        icon: 'ri:money-cny-box-line',
        definition: '统计周期内已支付订单商品行金额之和，为优惠与运费分摊前的商品毛额。',
        betterDirection: 'UP'
      },
      {
        key: 'paidOrderCount',
        title: '支付订单',
        group: '成交与盈利',
        unit: 'COUNT',
        icon: 'ri:file-list-3-line',
        definition: '统计周期内包含已支付商品的去重订单数。',
        betterDirection: 'UP'
      },
      {
        key: 'paidBuyerCount',
        title: '支付用户',
        group: '成交与盈利',
        unit: 'COUNT',
        icon: 'ri:user-star-line',
        definition: '统计周期内购买已支付商品的去重用户数。',
        betterDirection: 'UP'
      },
      {
        key: 'refundRate',
        title: '商品退款率',
        group: '成交与盈利',
        unit: 'BASIS_POINT',
        icon: 'ri:refund-line',
        definition: '退款商品件数除以支付商品件数；零分母时显示不可计算。',
        betterDirection: 'DOWN'
      },
      {
        key: 'costCoverageRate',
        title: '成本覆盖率',
        group: '成交与盈利',
        unit: 'BASIS_POINT',
        icon: 'ri:pie-chart-2-line',
        definition: '支付商品毛额中，具备下单时成本快照的商品金额占比；旧订单也计入分母。',
        betterDirection: 'UP'
      },
      {
        key: 'grossProfitAmountCent',
        title: '商品毛利',
        group: '成交与盈利',
        unit: 'CENT',
        icon: 'ri:funds-line',
        definition: '具备成本快照商品的分摊实付金额减去行成本；无成本快照时显示未采集。',
        betterDirection: 'UP'
      }
    ]
  }

  const loadReport: OperationPageLoader = async (query) =>
    adaptProductReport(await fetchProductStatistics(query))
</script>
