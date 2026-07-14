<template>
  <div class="sku-matrix">
    <div class="sku-matrix__header">
      <div>
        <div class="sku-matrix__title">商品属性</div>
        <div class="sku-matrix__hint">
          已生成 {{ modelValue.length }} 个组合；图片不填时依次使用规格值图片、商品封面图。
        </div>
      </div>
      <ElTag :type="modelValue.length > MAX_SKU_COMBINATIONS ? 'danger' : 'info'">
        {{ modelValue.length }} / {{ MAX_SKU_COMBINATIONS }}
      </ElTag>
    </div>

    <ElTable :data="modelValue" border row-key="combinationKey" max-height="560">
      <ElTableColumn
        v-for="(group, groupIndex) in groups"
        :key="group.groupKey"
        :label="group.name || `规格 ${groupIndex + 1}`"
        min-width="110"
        fixed="left"
      >
        <template #default="{ row }">
          {{ resolveValueName(row, group.groupKey) }}
        </template>
      </ElTableColumn>

      <ElTableColumn label="图片" width="150">
        <template #default="{ row, $index }">
          <div class="sku-image-cell">
            <ElImage
              v-if="resolvePreview(row)"
              :src="resolvePreview(row)"
              fit="cover"
              :preview-src-list="[resolvePreview(row)]"
              preview-teleported
              class="sku-image-cell__preview"
            />
            <div v-else class="sku-image-cell__placeholder">暂无图片</div>
            <ElPopover placement="right" :width="150" trigger="click">
              <template #reference>
                <ElButton size="small" text type="primary" :disabled="disabled">
                  {{ row.image ? '更换' : '选择' }}
                </ElButton>
              </template>
              <AssetPicker
                :model-value="{ fileId: row.imageFileId, url: row.image }"
                media-kind="IMAGE"
                :disabled="disabled"
                compact
                @change="updateImage($index, $event)"
              />
            </ElPopover>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn width="150">
        <template #header><span class="sku-matrix__required">*</span> 售价（元）</template>
        <template #default="{ row, $index }">
          <ElInputNumber
            :model-value="centToYuan(row.priceCent)"
            :min="0.01"
            :precision="2"
            :step="1"
            :disabled="disabled"
            controls-position="right"
            class="sku-matrix__number"
            @update:model-value="updateMoney($index, 'priceCent', $event)"
          />
        </template>
      </ElTableColumn>

      <ElTableColumn label="成本价（元）" width="150">
        <template #default="{ row, $index }">
          <ElInputNumber
            :model-value="centToYuan(row.costPriceCent)"
            :min="0"
            :precision="2"
            :step="1"
            :disabled="disabled"
            controls-position="right"
            class="sku-matrix__number"
            @update:model-value="updateMoney($index, 'costPriceCent', $event)"
          />
        </template>
      </ElTableColumn>

      <ElTableColumn label="划线价（元）" width="150">
        <template #default="{ row, $index }">
          <ElInputNumber
            :model-value="centToYuan(row.originalPriceCent)"
            :min="0"
            :precision="2"
            :step="1"
            :disabled="disabled"
            controls-position="right"
            class="sku-matrix__number"
            @update:model-value="updateMoney($index, 'originalPriceCent', $event)"
          />
        </template>
      </ElTableColumn>

      <ElTableColumn label="库存" width="130">
        <template #default="{ row, $index }">
          <ElInputNumber
            :model-value="row.stockAvailable"
            :min="0"
            :precision="0"
            :disabled="disabled || stockDisabled"
            controls-position="right"
            class="sku-matrix__number"
            @update:model-value="updateNumber($index, 'stockAvailable', $event ?? 0)"
          />
        </template>
      </ElTableColumn>

      <ElTableColumn label="商品编码" width="170">
        <template #default="{ row, $index }">
          <ElInput
            :model-value="row.skuCode"
            maxlength="64"
            placeholder="留空自动生成"
            :disabled="disabled"
            @update:model-value="updateRow($index, { skuCode: $event })"
          />
        </template>
      </ElTableColumn>

      <ElTableColumn label="重量（g）" width="140">
        <template #default="{ row, $index }">
          <ElInputNumber
            :model-value="row.weightGram"
            :min="0"
            :precision="0"
            :disabled="disabled"
            controls-position="right"
            class="sku-matrix__number"
            @update:model-value="updateNullableNumber($index, 'weightGram', $event)"
          />
        </template>
      </ElTableColumn>

      <ElTableColumn label="体积（m³）" width="150">
        <template #default="{ row, $index }">
          <ElInputNumber
            :model-value="row.volumeCubicMeter"
            :min="0"
            :precision="6"
            :step="0.001"
            :disabled="disabled"
            controls-position="right"
            class="sku-matrix__number"
            @update:model-value="updateNullableNumber($index, 'volumeCubicMeter', $event)"
          />
        </template>
      </ElTableColumn>

      <ElTableColumn label="默认选中规格" width="120" align="center" fixed="right">
        <template #default="{ row, $index }">
          <ElRadio
            :model-value="defaultCombinationKey"
            :value="row.combinationKey"
            :disabled="disabled || row.status !== 'ENABLED'"
            @change="selectDefault($index)"
          >
            默认
          </ElRadio>
        </template>
      </ElTableColumn>

      <ElTableColumn label="操作" width="90" align="center" fixed="right">
        <template #default="{ row, $index }">
          <ElSwitch
            :model-value="row.status === 'ENABLED'"
            :disabled="disabled"
            inline-prompt
            active-text="启"
            inactive-text="停"
            @update:model-value="updateStatus($index, Boolean($event))"
          />
        </template>
      </ElTableColumn>
    </ElTable>
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import type { ProductEditorSku, ProductEditorSpecGroup, ProductSkuStatus } from './editor-model'
  import { centToYuan, yuanToCent } from './editor-model'
  import { imageSpecFallback, MAX_SKU_COMBINATIONS, normalizeDefaultSku } from './sku-matrix'

  interface Props {
    modelValue: ProductEditorSku[]
    groups: ProductEditorSpecGroup[]
    coverImage?: string
    disabled?: boolean
    stockDisabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorSku[]): void
  }

  const props = withDefaults(defineProps<Props>(), {
    coverImage: '',
    disabled: false,
    stockDisabled: false
  })
  const emit = defineEmits<Emits>()

  type MoneyField = 'priceCent' | 'costPriceCent' | 'originalPriceCent'
  type NullableNumberField = 'weightGram' | 'volumeCubicMeter'

  const defaultCombinationKey = computed(
    () => props.modelValue.find((sku) => sku.defaultSelected)?.combinationKey || ''
  )

  const copyRows = () => props.modelValue.map((sku) => ({ ...sku }))

  const commit = (rows: ProductEditorSku[]) => emit('update:modelValue', rows)

  const updateRow = (index: number, patch: Partial<ProductEditorSku>) => {
    const rows = copyRows()
    rows[index] = { ...rows[index], ...patch }
    commit(rows)
  }

  const updateMoney = (index: number, field: MoneyField, value: number | undefined) => {
    updateRow(index, { [field]: yuanToCent(value) } as Partial<ProductEditorSku>)
  }

  const updateNumber = (index: number, field: 'stockAvailable', value: number | undefined) => {
    updateRow(index, { [field]: value ?? 0 })
  }

  const updateNullableNumber = (
    index: number,
    field: NullableNumberField,
    value: number | undefined
  ) => {
    updateRow(index, { [field]: value ?? null } as Partial<ProductEditorSku>)
  }

  const updateImage = (index: number, asset: Api.Common.AssetValue) => {
    updateRow(index, {
      image: asset.url,
      imageFileId: asset.fileId
    })
  }

  const updateStatus = (index: number, enabled: boolean) => {
    const rows = copyRows()
    rows[index].status = (enabled ? 'ENABLED' : 'DISABLED') as ProductSkuStatus
    normalizeDefaultSku(rows)
    commit(rows)
  }

  const selectDefault = (index: number) => {
    const rows = copyRows()
    rows.forEach((sku, skuIndex) => {
      sku.defaultSelected = skuIndex === index && sku.status === 'ENABLED'
    })
    normalizeDefaultSku(rows)
    commit(rows)
  }

  const resolveValueName = (sku: ProductEditorSku, groupKey: string) => {
    const group = props.groups.find((item) => item.groupKey === groupKey)
    const value = group?.values.find((item) => sku.specValueKeys.includes(item.valueKey))
    return value?.valueName || '-'
  }

  const resolvePreview = (sku: ProductEditorSku) =>
    imageSpecFallback(sku, props.groups, props.coverImage)
</script>

<style scoped lang="scss">
  .sku-matrix {
    display: grid;
    gap: 12px;
  }

  .sku-matrix__header {
    display: flex;
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .sku-matrix__title {
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .sku-matrix__hint {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .sku-matrix__number {
    width: 100%;
  }

  .sku-matrix__required {
    color: var(--el-color-danger);
  }

  .sku-image-cell {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .sku-image-cell__preview,
  .sku-image-cell__placeholder {
    flex: none;
    width: 46px;
    height: 46px;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  .sku-image-cell__placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    color: var(--el-text-color-placeholder);
    text-align: center;
  }
</style>
