<template>
  <div class="specification-tab">
    <ElAlert
      v-if="modelValue.skus.some((sku) => sku.id != null)"
      type="info"
      :closable="false"
      show-icon
      :title="
        canAdjustStock
          ? '已保存规格的库存仅供查看；如需调整，请在商品列表点击「调库存」。新增规格可设置初始库存。'
          : '库存仅供查看；如需调整，请联系有库存管理权限的管理员。'
      "
    />
    <section class="editor-section editor-section--type">
      <div class="spec-type-row">
        <span class="spec-type-row__label">规格类型</span>
        <ElRadioGroup
          :model-value="modelValue.specType"
          :disabled="disabled"
          @update:model-value="changeSpecType"
        >
          <ElRadioButton value="SINGLE">单规格</ElRadioButton>
          <ElRadioButton value="MULTI">多规格</ElRadioButton>
        </ElRadioGroup>

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
          <ElButton :loading="templateLoading" :disabled="disabled" @click="loadTemplates"
            >刷新</ElButton
          >
        </div>
      </div>
    </section>

    <section v-if="modelValue.specType === 'SINGLE'" class="editor-section">
      <div class="editor-section__heading">
        <h3>销售信息</h3>
        <span class="editor-section__hint">商品编码留空自动生成，图片留空使用商品封面图</span>
      </div>

      <ElForm label-position="top" class="single-sku-form">
        <div class="single-sku-form__row">
          <div class="single-sku-form__media">
            <span>规格图</span>
            <CompactAssetField
              :model-value="{ fileId: singleSku.imageFileId, url: singleSku.image }"
              media-kind="IMAGE"
              :disabled="disabled"
              :allow-url="false"
              small
              @change="updateSingleSkuImage"
            />
          </div>
          <ElFormItem class="single-sku-form__display-text" label="对外规格说明">
            <div class="single-sku-form__spec-text">
              <ElInput
                :model-value="singleSpecTextDisplay"
                maxlength="255"
                :placeholder="specTextPlaceholder"
                :disabled="disabled"
                @update:model-value="updateSingleSpecText"
              />
              <ElButton
                v-if="singleSku.specTextCustomized"
                link
                type="primary"
                :disabled="disabled"
                @click="restoreAutoSpecText"
              >
                恢复自动
              </ElButton>
            </div>
            <small class="single-sku-form__field-hint">
              留空自动使用「净含量/包装单位」（如 500g/袋）；需要特殊文案时可手动填写。
            </small>
          </ElFormItem>
          <ElFormItem class="single-sku-form__money" label="售价（元）" required>
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
          <ElFormItem class="single-sku-form__money" label="成本价（元）">
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
          <ElFormItem class="single-sku-form__money" label="划线价（元）">
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
          <ElFormItem
            class="single-sku-form__number"
            :label="singleSku.id == null ? '初始库存' : '库存（只读）'"
          >
            <span v-if="singleSku.id != null">{{ singleSku.stockAvailable }}</span>
            <ElInputNumber
              v-else
              :model-value="singleSku.stockAvailable"
              :min="0"
              :precision="0"
              controls-position="right"
              :disabled="disabled || !canAdjustStock"
              @update:model-value="updateSingleSku({ stockAvailable: $event ?? 0 })"
            />
          </ElFormItem>
          <ElFormItem class="single-sku-form__number" label="低库存预警值">
            <ElInputNumber
              :model-value="singleSku.lowStockThreshold"
              :min="0"
              :precision="0"
              controls-position="right"
              :disabled="disabled || !canAdjustStock"
              @update:model-value="updateSingleSku({ lowStockThreshold: $event ?? 0 })"
            />
          </ElFormItem>
          <ElFormItem class="single-sku-form__code" label="商品编码">
            <ElInput
              :model-value="singleSku.skuCode"
              maxlength="64"
              placeholder="留空自动生成"
              :disabled="disabled"
              @update:model-value="updateSingleSku({ skuCode: $event })"
            />
          </ElFormItem>
        </div>
      </ElForm>
    </section>

    <section
      v-if="modelValue.specType === 'SINGLE' && complianceType === 'FOOD'"
      class="editor-section"
    >
      <div class="editor-section__heading">
        <h3>食品净含量</h3>
        <span class="editor-section__hint">按真实标签填写；非食品商品不展示此项</span>
      </div>

      <ElForm label-position="top" class="single-sku-form">
        <div class="single-sku-form__row">
          <ElFormItem class="single-sku-form__display-text" label="净含量（食品必填）">
            <ElInput
              :model-value="singleSku.netContentText"
              maxlength="120"
              placeholder="按真实标签填写，例如 500g"
              :disabled="disabled"
              @update:model-value="updateSingleNetContent"
            />
          </ElFormItem>
          <ElFormItem class="single-sku-form__unit" label="包装单位">
            <ElSelect
              :model-value="singleSku.packUnitText"
              :disabled="disabled"
              filterable
              allow-create
              default-first-option
              clearable
              placeholder="默认「袋」"
              @update:model-value="updateSinglePackUnit"
            >
              <ElOption v-for="unit in PACK_UNIT_OPTIONS" :key="unit" :label="unit" :value="unit" />
            </ElSelect>
            <small class="single-sku-form__field-hint">
              规格说明自动拼成「净含量/单位」，如 500g/袋。
            </small>
          </ElFormItem>
        </div>
      </ElForm>
    </section>

    <section v-if="modelValue.specType === 'SINGLE'" class="editor-section">
      <div class="editor-section__heading">
        <h3>批发阶梯价</h3>
        <span class="editor-section__hint">可选；数量达到档位后自动切换单价</span>
      </div>
      <WholesaleTierEditor
        :model-value="singleSku.wholesaleTiers"
        :retail-price-cent="singleSku.priceCent"
        :disabled="disabled"
        @update:model-value="updateSingleSku({ wholesaleTiers: $event })"
      />
    </section>

    <template v-else>
      <section class="editor-section">
        <div class="editor-section__heading">
          <h3>商品规格</h3>
          <span class="editor-section__hint"
            >规格图默认不选；请选择一个规格后再为规格值上传图片</span
          >
        </div>

        <SpecTreeEditor
          ref="specTreeEditorRef"
          :model-value="modelValue.specGroups"
          :disabled="disabled"
          :show-add-button="false"
          @update:model-value="handleGroupsChange"
        />

        <div class="spec-tree-actions">
          <ElButton
            class="spec-tree-actions__add"
            :disabled="disabled || modelValue.specGroups.length >= 10"
            @click="addSpecGroup"
          >
            + 添加新规格
          </ElButton>
          <ElButton
            type="primary"
            plain
            :disabled="disabled || !modelValue.specGroups.length"
            @click="openSaveDialog"
          >
            另存为规格模板
          </ElButton>
        </div>

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
          :compliance-type="complianceType"
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
            placeholder="请输入规格模板名称"
            @keyup.enter="saveAsTemplate"
          />
        </ElFormItem>
        <ElAlert
          type="info"
          :closable="false"
          title="规格模板保存规格名称、规格值与规格图开关，不保存当前 SKU 的价格和库存。"
        />
      </ElForm>
      <template #footer>
        <ElButton @click="saveDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="templateSaving" @click="saveAsTemplate">
          保存规格模板
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
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
  import { centToYuan, validateWholesaleTiers, yuanToCent } from './editor-model'
  import { autoSingleSpecText, PACK_UNIT_OPTIONS } from './sku-derivation'
  import SpecTreeEditor from './spec-tree-editor.vue'
  import SkuMatrix from './sku-matrix.vue'
  import CompactAssetField from './compact-asset-field.vue'
  import WholesaleTierEditor from './wholesale-tier-editor.vue'
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
  const specTreeEditorRef = ref<InstanceType<typeof SpecTreeEditor>>()
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
  const complianceType = computed(() => props.modelValue.foodDisclosure.complianceType)
  const singleSpecTextDisplay = computed(() =>
    singleSku.value.specTextCustomized
      ? singleSku.value.specText
      : autoSingleSpecText(singleSku.value)
  )
  const specTextPlaceholder = computed(() =>
    singleSku.value.netContentText.trim()
      ? `留空自动使用「${autoSingleSpecText(singleSku.value)}」`
      : '填写净含量后自动生成，例如 500g/袋'
  )

  const patchForm = (patch: Partial<ProductEditorForm>) => {
    emit('update:modelValue', { ...props.modelValue, ...patch })
  }

  const addSpecGroup = () => specTreeEditorRef.value?.addGroup()

  const updateSingleSku = (patch: Partial<ProductEditorSku>) => {
    const next = {
      ...singleSku.value,
      ...patch,
      status: 'ENABLED' as const,
      defaultSelected: true,
      combinationKey: 'SINGLE',
      specJson: '{}',
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

  const updateSingleSpecText = (value: string) => {
    updateSingleSku({ specText: value, specTextCustomized: true })
  }

  const restoreAutoSpecText = () => {
    updateSingleSku({ specText: '', specTextCustomized: false })
  }

  const updateSingleNetContent = (value: string) => {
    updateSingleSku({ netContentText: value })
  }

  const updateSinglePackUnit = (value: string | number | boolean | undefined) => {
    updateSingleSku({ packUnitText: typeof value === 'string' ? value : '' })
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
    if (sku.lowStockThreshold < 0) return '低库存预警值不能小于 0'
    if (sku.weightGram !== null && sku.weightGram < 0) return '重量不能小于 0'
    if (sku.volumeCubicMeter !== null && sku.volumeCubicMeter < 0) return '体积不能小于 0'
    const wholesaleError = validateWholesaleTiers(sku.wholesaleTiers, sku.priceCent)
    if (wholesaleError) return wholesaleError
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
    if (props.modelValue.skus.some((sku) => sku.lowStockThreshold < 0)) {
      return '低库存预警值不能小于 0'
    }
    for (const sku of props.modelValue.skus) {
      const wholesaleError = validateWholesaleTiers(sku.wholesaleTiers, sku.priceCent)
      if (wholesaleError) return `${sku.specText || '未命名规格'}：${wholesaleError}`
    }
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
    gap: 12px;
    max-width: 1400px;
    margin: 0 auto;
  }

  .editor-section {
    padding: 14px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .editor-section--type {
    padding: 10px 14px;
  }

  .spec-type-row {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
  }

  .spec-type-row__label {
    min-width: 70px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .editor-section__heading {
    display: flex;
    gap: 10px;
    align-items: center;
    margin-bottom: 12px;

    h3 {
      margin: 0;
      font-size: 16px;
      color: var(--el-text-color-primary);
    }
  }

  .editor-section__hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .template-actions {
    display: flex;
    flex: 1;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    justify-content: flex-end;
  }

  .spec-tree-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding-left: 12px;
    margin-top: 12px;
  }

  .spec-tree-actions__add {
    background: transparent;

    &:hover,
    &:focus,
    &:active {
      background: transparent;
    }
  }

  .single-sku-form__row {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: flex-start;
  }

  .single-sku-form__media {
    display: grid;
    gap: 8px;
    width: 72px;

    > span {
      min-height: 22px;
      font-size: 14px;
      line-height: 22px;
      color: var(--el-text-color-regular);
    }
  }

  .single-sku-form :deep(.el-form-item) {
    margin-bottom: 0;
  }

  .single-sku-form :deep(.el-input-number) {
    width: 100%;
  }

  .single-sku-form__money {
    width: 142px;
  }

  .single-sku-form__number {
    width: 118px;
  }

  .single-sku-form__code {
    width: 180px;
  }

  .single-sku-form__display-text {
    width: 240px;
  }

  .single-sku-form__unit {
    width: 160px;
  }

  .single-sku-form__spec-text {
    display: flex;
    gap: 4px;
    align-items: center;
    width: 100%;
  }

  .single-sku-form__field-hint {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }

  .combination-alert {
    margin-top: 10px;
  }

  @media (width <= 640px) {
    .template-actions {
      flex-basis: 100%;
      justify-content: flex-start;
    }

    .template-actions :deep(.el-select) {
      width: 100% !important;
    }

    .editor-section__heading {
      flex-direction: column;
      align-items: flex-start;
    }

    .single-sku-form__money,
    .single-sku-form__number,
    .single-sku-form__code,
    .single-sku-form__display-text {
      flex: 1 1 140px;
      width: auto;
    }
  }
</style>
