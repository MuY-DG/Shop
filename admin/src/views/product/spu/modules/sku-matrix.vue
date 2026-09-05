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

      <ElTableColumn label="批发价" width="120" align="center">
        <template #default="{ row, $index }">
          <ElButton
            size="small"
            :type="row.wholesaleTiers.length ? 'primary' : 'default'"
            plain
            :disabled="disabled"
            @click="openWholesaleDialog($index)"
          >
            {{ row.wholesaleTiers.length ? `${row.wholesaleTiers.length} 档` : '设置' }}
          </ElButton>
        </template>
      </ElTableColumn>

      <ElTableColumn label="库存" width="130">
        <template #default="{ row, $index }">
          <span v-if="row.id != null">{{ row.stockAvailable }}</span>
          <ElInputNumber
            v-else
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

      <ElTableColumn label="低库存预警值" width="150">
        <template #default="{ row, $index }">
          <ElInputNumber
            :model-value="row.lowStockThreshold"
            :min="0"
            :precision="0"
            :disabled="disabled || stockDisabled"
            controls-position="right"
            class="sku-matrix__number"
            @update:model-value="updateNumber($index, 'lowStockThreshold', $event ?? 0)"
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

      <ElTableColumn v-if="complianceType === 'FOOD'" label="净含量（食品必填）" width="180">
        <template #default="{ row, $index }">
          <ElInput
            :model-value="row.netContentText"
            maxlength="120"
            placeholder="按真实标签填写"
            :disabled="disabled"
            @update:model-value="updateRow($index, { netContentText: $event })"
          />
        </template>
      </ElTableColumn>

      <ElTableColumn v-if="complianceType === 'FOOD'" width="150">
        <template #header><span class="sku-matrix__optional">包装单位</span></template>
        <template #default="{ row, $index }">
          <ElSelect
            :model-value="row.packUnitText"
            :disabled="disabled"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="默认「袋」"
            @update:model-value="updatePackUnit($index, $event)"
          >
            <ElOption v-for="unit in PACK_UNIT_OPTIONS" :key="unit" :label="unit" :value="unit" />
          </ElSelect>
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

    <ElDialog v-model="wholesaleDialogVisible" title="设置批发阶梯价" width="620px" align-center>
      <div v-if="editingSku" class="wholesale-dialog-heading">
        <b>{{ editingSku.specText }}</b>
        <span>常规售价：{{ centToYuan(editingSku.priceCent)?.toFixed(2) || '-' }} 元</span>
      </div>
      <WholesaleTierEditor
        v-if="editingSku"
        v-model="wholesaleDraft"
        :retail-price-cent="editingSku.priceCent"
        :disabled="disabled"
      />
      <template #footer>
        <ElButton @click="wholesaleDialogVisible = false">取消</ElButton>
        <ElButton type="primary" @click="saveWholesaleTiers">确定</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import type {
    ProductEditorSku,
    ProductEditorSpecGroup,
    ProductEditorWholesaleTier,
    ProductSkuStatus
  } from './editor-model'
  import { centToYuan, validateWholesaleTiers, yuanToCent } from './editor-model'
  import { PACK_UNIT_OPTIONS } from './sku-derivation'
  import WholesaleTierEditor from './wholesale-tier-editor.vue'
  import {
    imageSpecFallback,
    MAX_SKU_COMBINATIONS,
    composeSkuSpecText,
    normalizeDefaultSku
  } from './sku-matrix'

  interface Props {
    modelValue: ProductEditorSku[]
    groups: ProductEditorSpecGroup[]
    coverImage?: string
    complianceType?: Api.Product.ProductComplianceType
    disabled?: boolean
    stockDisabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorSku[]): void
  }

  const props = withDefaults(defineProps<Props>(), {
    coverImage: '',
    complianceType: 'NON_FOOD',
    disabled: false,
    stockDisabled: false
  })
  const emit = defineEmits<Emits>()
  const wholesaleDialogVisible = ref(false)
  const wholesaleEditingIndex = ref(-1)
  const wholesaleDraft = ref<ProductEditorWholesaleTier[]>([])

  type MoneyField = 'priceCent' | 'costPriceCent' | 'originalPriceCent'

  const defaultCombinationKey = computed(
    () => props.modelValue.find((sku) => sku.defaultSelected)?.combinationKey || ''
  )
  const editingSku = computed(() => props.modelValue[wholesaleEditingIndex.value])

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

  const updateNumber = (
    index: number,
    field: 'stockAvailable' | 'lowStockThreshold',
    value: number | undefined
  ) => {
    updateRow(index, { [field]: value ?? 0 })
  }

  const updatePackUnit = (index: number, value: string | number | boolean | undefined) => {
    const rows = copyRows()
    rows[index] = {
      ...rows[index],
      packUnitText: typeof value === 'string' ? value : '',
      specText: composeSkuSpecText(props.groups, rows[index])
    }
    commit(rows)
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

  const openWholesaleDialog = (index: number) => {
    wholesaleEditingIndex.value = index
    wholesaleDraft.value = props.modelValue[index].wholesaleTiers.map((tier) => ({ ...tier }))
    wholesaleDialogVisible.value = true
  }

  const saveWholesaleTiers = () => {
    const sku = editingSku.value
    if (!sku) return
    const error = validateWholesaleTiers(wholesaleDraft.value, sku.priceCent)
    if (error) {
      ElMessage.error(error)
      return
    }
    updateRow(wholesaleEditingIndex.value, {
      wholesaleTiers: wholesaleDraft.value.map((tier) => ({ ...tier }))
    })
    wholesaleDialogVisible.value = false
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

  .sku-matrix__optional {
    color: var(--el-text-color-secondary);
    font-weight: 400;
  }

  .wholesale-dialog-heading {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 14px;
    color: var(--el-text-color-regular);
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
