<template>
  <div class="wholesale-tier-editor">
    <ElAlert
      type="info"
      :closable="false"
      title="同一 SKU 达到起订数量后，该行全部件数按对应阶梯单价计算；最多 5 档。"
    />

    <div v-if="modelValue.length" class="tier-list">
      <div v-for="(tier, index) in modelValue" :key="index" class="tier-row">
        <span class="tier-index">第 {{ index + 1 }} 档</span>
        <ElInputNumber
          :model-value="tier.minQuantity"
          :min="2"
          :max="999"
          :precision="0"
          controls-position="right"
          :disabled="disabled"
          @update:model-value="updateQuantity(index, $event)"
        />
        <span>件起，每件</span>
        <ElInputNumber
          :model-value="centToYuan(tier.unitPriceCent)"
          :min="0.01"
          :precision="2"
          :step="0.1"
          controls-position="right"
          :disabled="disabled"
          @update:model-value="updatePrice(index, $event)"
        />
        <span>元</span>
        <ElButton text type="danger" :disabled="disabled" @click="removeTier(index)">
          删除
        </ElButton>
      </div>
    </div>

    <div v-else class="empty-hint">未启用批发价，按常规售价结算。</div>
    <ElButton type="primary" plain :disabled="disabled || modelValue.length >= 5" @click="addTier">
      + 添加阶梯
    </ElButton>
  </div>
</template>

<script setup lang="ts">
  import type { ProductEditorWholesaleTier } from './editor-model'
  import { centToYuan, yuanToCent } from './editor-model'

  interface Props {
    modelValue: ProductEditorWholesaleTier[]
    retailPriceCent: number | null
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorWholesaleTier[]): void
  }

  const props = withDefaults(defineProps<Props>(), { disabled: false })
  const emit = defineEmits<Emits>()

  const commit = (rows: ProductEditorWholesaleTier[]) => emit('update:modelValue', rows)

  const updateQuantity = (index: number, value: number | undefined) => {
    const rows = props.modelValue.map((tier) => ({ ...tier }))
    rows[index].minQuantity = value ?? 2
    commit(rows)
  }

  const updatePrice = (index: number, value: number | undefined) => {
    const rows = props.modelValue.map((tier) => ({ ...tier }))
    rows[index].unitPriceCent = yuanToCent(value)
    commit(rows)
  }

  const addTier = () => {
    const previous = props.modelValue.at(-1)
    const minQuantity = previous ? Math.min(999, previous.minQuantity + 10) : 10
    const previousPrice = previous?.unitPriceCent ?? props.retailPriceCent
    commit([
      ...props.modelValue.map((tier) => ({ ...tier })),
      {
        minQuantity,
        unitPriceCent: previousPrice ? Math.max(1, previousPrice - 1) : null
      }
    ])
  }

  const removeTier = (index: number) => {
    commit(props.modelValue.filter((_, tierIndex) => tierIndex !== index))
  }
</script>

<style scoped lang="scss">
  .wholesale-tier-editor {
    display: grid;
    gap: 12px;
  }

  .tier-list {
    display: grid;
    gap: 10px;
  }

  .tier-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;

    :deep(.el-input-number) {
      width: 140px;
    }
  }

  .tier-index {
    width: 54px;
    font-weight: 600;
  }

  .empty-hint {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  .el-button {
    justify-self: start;
  }
</style>
