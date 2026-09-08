<template>
  <div v-if="view.refundText || view.activeText" class="item-after-sale">
    <span v-if="view.refundText" class="item-after-sale__refund">{{ view.refundText }}</span>
    <span v-if="view.activeText" class="item-after-sale__active">{{ view.activeText }}</span>
    <ElPopover v-if="view.records.length" placement="left" :width="320" trigger="click">
      <template #reference><ElButton link type="primary">查看售后记录</ElButton></template>
      <div class="item-after-sale__records">
        <ElButton
          v-for="record in view.records"
          :key="record.afterSaleId"
          link
          type="primary"
          @click="emit('open', record.afterSaleId)"
        >
          {{ record.afterSaleNo }} · {{ orderItemAfterSaleStatusText(record.status) }}
        </ElButton>
      </div>
    </ElPopover>
  </div>
  <span v-else class="item-after-sale__empty">—</span>
</template>

<script setup lang="ts">
  import { computed } from 'vue'
  import {
    buildOrderItemAfterSaleView,
    orderItemAfterSaleStatusText
  } from '../order-item-after-sale'

  const props = defineProps<{ item: Api.Order.OrderItem }>()
  const emit = defineEmits<{ open: [afterSaleId: number] }>()
  const view = computed(() => buildOrderItemAfterSaleView(props.item))
</script>

<style scoped lang="scss">
  .item-after-sale,
  .item-after-sale__records {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
    line-height: 1.6;
  }

  .item-after-sale__refund {
    color: var(--el-color-success-dark-2);
  }

  .item-after-sale__active {
    color: var(--el-color-warning-dark-2);
  }

  .item-after-sale__empty {
    color: var(--el-text-color-placeholder);
  }

  .item-after-sale__records :deep(.el-button) {
    height: auto;
    margin-left: 0;
    text-align: left;
    white-space: normal;
  }
</style>
