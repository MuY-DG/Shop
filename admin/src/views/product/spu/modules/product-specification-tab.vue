<template>
  <div class="specification-tab">
    <section class="editor-section">
      <div class="editor-section__heading">
        <div>
          <h3>规格类型</h3>
          <p>单规格适合只有一个销售属性的商品，多规格会根据规格值自动生成组合。</p>
        </div>
        <div v-if="modelValue.specType === 'MULTI'" class="template-actions">
          <ElSelect
            v-model="selectedTemplateId"
            filterable
            clearable
            placeholder="选择规格模板（可选）"
            :loading="templateLoading"
            :disabled="disabled"
            style="width: 240px"
            @change="handleTemplateChange"
          >
            <ElOption
              v-for="template in templates"
              :key="template.id"
              :label="template.name"
              :value="template.id"
            />
          </ElSelect>
          <ElButton :loading="templateLoading" :disabled="disabled" @click="loadTemplates">
            刷新
          </ElButton>
        </div>
      </div>

      <ElRadioGroup
        :model-value="modelValue.specType"
        :disabled="disabled"
        @update:model-value="changeSpecType"
      >
        <ElRadioButton value="SINGLE">单规格</ElRadioButton>
        <ElRadioButton value="MULTI">多规格</ElRadioButton>
      </ElRadioGroup>
    </section>

    <section v-if="modelValue.specType === 'SINGLE'" class="editor-section">
      <div class="editor-section__heading">
        <div>
          <h3>商品规格</h3>
          <p>商品编码可留空，由后端保存时自动生成；库存不填按 0 保存。</p>
        </div>
      </div>

      <ElForm label-position="top" class="single-sku-form">
        <ElFormItem label="图片">
          <div class="single-sku-form__image">
            <AssetPicker
              :model-value="{ fileId: singleSku.imageFileId, url: singleSku.image }"
              purpose="PRODUCT_SKU_IMAGE"
              :disabled="disabled"
              @change="updateSingleSkuImage"
            />
            <span>可选；不上传时使用商品封面图。</span>
          </div>
        </ElFormItem>

        <div class="single-sku-form__grid">
          <ElFormItem label="售价（元）" required>
            <ElInputNumber
              :model-value="centToYuan(singleSku.priceCent)"
              :min="0.01"
              :precision="2"
              :step="1"
              controls-position="right"
              :disabled="disabled"
              @update:model-value="updateSingleMoney('priceCent', $event)"
            />
          </ElFormItem>
          <ElFormItem label="成本价（元）">
            <ElInputNumber
              :model-value="centToYuan(singleSku.costPriceCent)"
              :min="0"
              :precision="2"
              :step="1"
              controls-position="right"
              :disabled="disabled"
              @update:model-value="updateSingleMoney('costPriceCent', $event)"
            />
          </ElFormItem>
          <ElFormItem label="划线价（元）">
            <ElInputNumber
              :model-value="centToYuan(singleSku.originalPriceCent)"
              :min="0"
              :precision="2"
              :step="1"
              controls-position="right"
              :disabled="disabled"
              @update:model-value="updateSingleMoney('originalPriceCent', $event)"
            />
          </ElFormItem>
          <ElFormItem label="库存">
            <ElInputNumber
              :model-value="singleSku.stockAvailable"
              :min="0"
              :precision="0"
              controls-position="right"
              :disabled="disabled || !canAdjustStock"
              @update:model-value="updateSingleSku({ stockAvailable: $event ?? 0 })"
            />
          </ElFormItem>
          <ElFormItem label="商品编码">
            <ElInput
              :model-value="singleSku.skuCode"
              maxlength="64"
              placeholder="留空自动生成"
              :disabled="disabled"
              @update:model-value="updateSingleSku({ skuCode: $event })"
            />
          </ElFormItem>
          <ElFormItem label="重量（g）">
            <ElInputNumber
              :model-value="singleSku.weightGram"
              :min="0"
              :precision="0"
              controls-position="right"
              :disabled="disabled"
              placeholder="可选"
              @update:model-value="updateSingleSku({ weightGram: $event ?? null })"
            />
          </ElFormItem>
          <ElFormItem label="体积（m³）">
            <ElInputNumber
              :model-value="singleSku.volumeCubicMeter"
              :min="0"
              :precision="6"
              :step="0.001"
              controls-position="right"
              :disabled="disabled"
              placeholder="可选"
              @update:model-value="updateSingleSku({ volumeCubicMeter: $event ?? null })"
            />
          </ElFormItem>
        </div>
      </ElForm>
    </section>

    <template v-else>
      <section class="editor-section">
        <div class="editor-section__heading">
          <div>
            <h3>商品规格</h3>
            <p>规格名称最多 30 字；必须且只能选择一个规格名称添加规格图。</p>
          </div>
          <ElButton
            v-auth="'product:spec-template:create'"
            plain
            :disabled="disabled || !modelValue.specGroups.length"
            @click="openSaveDialog"
          >
            另存为模板
          </ElButton>
        </div>

        <SpecTreeEditor
          :model-value="modelValue.specGroups"
          :disabled="disabled"
          @update:model-value="handleGroupsChange"
        />

        <ElAlert
          v-if="combinationDescription"
          class="combination-alert"
          :type="combinationCountValue > MAX_SKU_COMBINATIONS ? 'error' : 'info'"
          :closable="false"
          show-icon
          :title="combinationDescription"
        />
      </section>

      <section v-if="combinationCountValue <= MAX_SKU_COMBINATIONS" class="editor-section">
        <SkuMatrix
          :model-value="modelValue.skus"
          :groups="modelValue.specGroups"
          :cover-image="modelValue.mainImage"
          :stock-disabled="!canAdjustStock"
          :disabled="disabled"
          @update:model-value="patchForm({ skus: $event })"
        />
      </section>
    </template>

    <ElDialog v-model="saveDialogVisible" title="另存为规格模板" width="480px" align-center>
      <ElForm label-position="top">
        <ElFormItem label="规格模板名称" required>
          <ElInput
            v-model="templateName"
            maxlength="64"
            show-word-limit
            placeholder="请输入模板名称"
            @keyup.enter="saveAsTemplate"
          />
        </ElFormItem>
        <ElAlert
          type="info"
          :closable="false"
          title="模板保存规格名称、规格值与规格图开关，不保存当前 SKU 的价格和库存。"
        />
      </ElForm>
      <template #footer>
        <ElButton @click="saveDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="templateSaving" @click="saveAsTemplate">
          保存模板
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import {
    createProductSpecTemplate,
    fetchProductSpecTemplateDetail,
    fetchProductSpecTemplates
  } from '@/api/product'
  import type {
    ProductEditorForm,
    ProductEditorSku,
    ProductEditorSpecGroup,
    ProductEditorSpecTemplate,
    ProductSpecType
  } from './editor-model'
  import { centToYuan, yuanToCent } from './editor-model'
  import SpecTreeEditor from './spec-tree-editor.vue'
  import SkuMatrix from './sku-matrix.vue'
  import {
    MAX_SKU_COMBINATIONS,
    cloneTemplateGroups,
    combinationCount,
    createEmptySku,
    createEmptySpecGroup,
    describeCombinationCount,
    normalizeDefaultSku,
    reconcileSkuMatrix,
    validateSpecGroups
  } from './sku-matrix'

  interface Props {
    modelValue: ProductEditorForm
    disabled?: boolean
    canAdjustStock?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorForm): void
  }

  const props = withDefaults(defineProps<Props>(), {
    disabled: false,
    canAdjustStock: true
  })
  const emit = defineEmits<Emits>()

  const selectedTemplateId = ref<number | null>(null)
  const templates = ref<ProductEditorSpecTemplate[]>([])
  const templateLoading = ref(false)
  const templateSaving = ref(false)
  const saveDialogVisible = ref(false)
  const templateName = ref('')
  const singleSnapshot = ref<ProductEditorSku | null>(null)
  const multiGroupSnapshot = ref<ProductEditorSpecGroup[] | null>(null)
  const multiSkuSnapshot = ref<ProductEditorSku[] | null>(null)

  const singleSku = computed(() => props.modelValue.skus[0] || createEmptySku())
  const combinationCountValue = computed(() => combinationCount(props.modelValue.specGroups))
  const combinationDescription = computed(() =>
    props.modelValue.specGroups.length
      ? `组合数量：${describeCombinationCount(props.modelValue.specGroups)}`
      : ''
  )

  const patchForm = (patch: Partial<ProductEditorForm>) => {
    emit('update:modelValue', { ...props.modelValue, ...patch })
  }

  const updateSingleSku = (patch: Partial<ProductEditorSku>) => {
    const next = {
      ...singleSku.value,
      ...patch,
      status: 'ENABLED' as const,
      defaultSelected: true,
      combinationKey: 'SINGLE',
      specJson: '{}',
      specText: '默认规格',
      specValueKeys: [],
      sortOrder: 0
    }
    singleSnapshot.value = next
    patchForm({ skus: [next], specGroups: [] })
  }

  const updateSingleMoney = (
    field: 'priceCent' | 'costPriceCent' | 'originalPriceCent',
    value: number | undefined
  ) => {
    updateSingleSku({ [field]: yuanToCent(value) } as Partial<ProductEditorSku>)
  }

  const updateSingleSkuImage = (asset: Api.Common.AssetValue) => {
    updateSingleSku({ image: asset.url, imageFileId: asset.fileId })
  }

  const changeSpecType = (value: string | number | boolean | undefined) => {
    const target = value as ProductSpecType
    if (target === props.modelValue.specType) return

    if (props.modelValue.specType === 'SINGLE') {
      singleSnapshot.value = { ...singleSku.value }
      const groups = multiGroupSnapshot.value || [createEmptySpecGroup()]
      const skus = reconcileSkuMatrix(groups, multiSkuSnapshot.value || [])
      patchForm({ specType: 'MULTI', specGroups: groups, skus })
      return
    }

    multiGroupSnapshot.value = props.modelValue.specGroups.map((group) => ({
      ...group,
      values: group.values.map((item) => ({ ...item }))
    }))
    multiSkuSnapshot.value = props.modelValue.skus.map((sku) => ({ ...sku }))
    patchForm({
      specType: 'SINGLE',
      specGroups: [],
      skus: [singleSnapshot.value || createEmptySku()]
    })
  }

  const handleGroupsChange = (groups: ProductEditorSpecGroup[]) => {
    const count = combinationCount(groups)
    const skus =
      count <= MAX_SKU_COMBINATIONS
        ? reconcileSkuMatrix(groups, props.modelValue.skus)
        : props.modelValue.skus
    multiGroupSnapshot.value = groups
    multiSkuSnapshot.value = skus
    patchForm({ specGroups: groups, skus })
  }

  const loadTemplates = async () => {
    templateLoading.value = true
    try {
      templates.value = (await fetchProductSpecTemplates()) as ProductEditorSpecTemplate[]
    } finally {
      templateLoading.value = false
    }
  }

  const handleTemplateChange = async (templateId: number | null) => {
    if (!templateId) return
    try {
      await ElMessageBox.confirm(
        '加载模板会替换当前规格结构和组合矩阵，已填写的同组合数据无法保证保留。是否继续？',
        '加载规格模板',
        { type: 'warning', confirmButtonText: '继续加载', cancelButtonText: '取消' }
      )
    } catch {
      selectedTemplateId.value = null
      return
    }

    templateLoading.value = true
    try {
      const detail = (await fetchProductSpecTemplateDetail(templateId)) as ProductEditorSpecTemplate
      const groups = cloneTemplateGroups(detail.groups || [])
      handleGroupsChange(groups)
      ElMessage.success(`已加载模板“${detail.name}”`)
    } finally {
      templateLoading.value = false
    }
  }

  const openSaveDialog = () => {
    const error = validateSpecGroups(props.modelValue.specGroups)
    if (error) {
      ElMessage.error(error)
      return
    }
    templateName.value = ''
    saveDialogVisible.value = true
  }

  const buildTemplatePayload = () => ({
    name: templateName.value.trim(),
    groups: props.modelValue.specGroups.map((group, groupIndex) => ({
      name: group.name.trim(),
      groupKey: group.groupKey,
      imageEnabled: group.imageEnabled,
      sortOrder: groupIndex,
      values: group.values.map((value, valueIndex) => ({
        valueName: value.valueName.trim(),
        valueKey: value.valueKey,
        sortOrder: valueIndex
      }))
    }))
  })

  const saveAsTemplate = async () => {
    if (!templateName.value.trim()) {
      ElMessage.error('请输入规格模板名称')
      return
    }
    templateSaving.value = true
    try {
      await createProductSpecTemplate(buildTemplatePayload())
      saveDialogVisible.value = false
      await loadTemplates()
    } finally {
      templateSaving.value = false
    }
  }

  const validateSingle = () => {
    const sku = singleSku.value
    if (!sku.priceCent || sku.priceCent < 1) return '请填写单规格商品售价'
    if (sku.costPriceCent !== null && sku.costPriceCent < 0) return '成本价不能小于 0'
    if (sku.originalPriceCent !== null && sku.originalPriceCent < 0) return '划线价不能小于 0'
    if (sku.stockAvailable < 0) return '库存不能小于 0'
    if (sku.weightGram !== null && sku.weightGram < 0) return '重量不能小于 0'
    if (sku.volumeCubicMeter !== null && sku.volumeCubicMeter < 0) return '体积不能小于 0'
    return null
  }

  const validateMulti = () => {
    const groupError = validateSpecGroups(props.modelValue.specGroups)
    if (groupError) return groupError
    if (!props.modelValue.skus.length) return '请生成商品属性组合'
    if (props.modelValue.skus.length !== combinationCountValue.value) {
      return '商品属性组合尚未同步，请修改一个规格值后重试'
    }
    if (props.modelValue.skus.some((sku) => !sku.priceCent || sku.priceCent < 1)) {
      return '请填写所有商品属性的售价'
    }
    if (props.modelValue.skus.some((sku) => sku.stockAvailable < 0)) return '库存不能小于 0'
    const enabled = props.modelValue.skus.filter((sku) => sku.status === 'ENABLED')
    if (!enabled.length) return '至少启用一个商品属性'
    if (enabled.filter((sku) => sku.defaultSelected).length !== 1) {
      return '必须且只能选择一个已启用的默认规格'
    }
    return null
  }

  const validate = async () => {
    const error = props.modelValue.specType === 'SINGLE' ? validateSingle() : validateMulti()
    if (error) {
      ElMessage.error(error)
      return false
    }
    if (props.modelValue.specType === 'SINGLE') {
      updateSingleSku({})
    } else {
      const rows = props.modelValue.skus.map((sku) => ({ ...sku }))
      normalizeDefaultSku(rows)
      patchForm({ skus: rows })
    }
    return true
  }

  defineExpose({ validate })

  onMounted(loadTemplates)
</script>

<style scoped lang="scss">
  .specification-tab {
    display: grid;
    gap: 18px;
  }

  .editor-section {
    padding: 20px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 12px;
  }

  .editor-section__heading {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 18px;

    h3 {
      margin: 0;
      font-size: 16px;
      color: var(--el-text-color-primary);
    }

    p {
      margin: 6px 0 0;
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }
  }

  .template-actions {
    display: flex;
    gap: 8px;
  }

  .single-sku-form__image {
    width: min(620px, 100%);

    > span {
      display: block;
      margin-top: 6px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .single-sku-form__grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 0 18px;

    :deep(.el-input-number) {
      width: 100%;
    }
  }

  .combination-alert {
    margin-top: 16px;
  }

  @media (width <= 960px) {
    .single-sku-form__grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (width <= 640px) {
    .editor-section__heading,
    .template-actions {
      flex-direction: column;
    }

    .single-sku-form__grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
