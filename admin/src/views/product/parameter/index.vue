<template>
  <div class="product-parameter-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <template #header>
        <div class="page-header">
          <div>
            <h3>商品参数</h3>
            <p>定义分类级展示参数；商品编辑时只出现所属分类绑定的参数。</p>
          </div>
          <div class="page-header__actions">
            <ElButton :loading="loading" @click="loadData">刷新</ElButton>
            <ElButton v-if="canWrite" type="primary" @click="openEditor()"> 新增参数 </ElButton>
          </div>
        </div>
      </template>

      <div ref="tableContainerRef">
        <ElTable ref="tableRef" v-loading="loading" :data="items" row-key="id">
          <ElTableColumn label="参数" min-width="180">
            <template #default="{ row }">
              <strong>{{ row.parameterName }}</strong>
              <div class="muted">{{ row.parameterCode }}</div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="类型" width="130">
            <template #default="{ row }">{{ valueTypeLabel(row.valueType) }}</template>
          </ElTableColumn>
          <ElTableColumn label="适用分类" min-width="240">
            <template #default="{ row }">
              <div class="category-tags">
                <ElTag v-for="categoryId in row.categoryIds" :key="categoryId" size="small">
                  {{ categoryName(categoryId) }}
                </ElTag>
                <span v-if="!row.categoryIds.length" class="muted">未绑定分类</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="展示" width="150">
            <template #default="{ row }">
              <ElTag v-if="row.cardVisible" size="small" type="success">商品卡片</ElTag>
              <ElTag v-if="row.detailVisible" size="small" type="info">商品详情</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100">
            <template #default="{ row }">
              <ElTag :type="row.status === 'ENABLED' ? 'success' : 'info'">
                {{ row.status === 'ENABLED' ? '启用' : '停用' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="sortOrder" label="排序" width="80" />
          <ElTableColumn v-if="canWrite" label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <ElButton link type="primary" @click="openEditor(row)"> 编辑 </ElButton>
              <ElButton link type="danger" @click="handleDelete(row)"> 删除 </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </div>
    </ElCard>

    <ElDrawer
      v-model="editorVisible"
      :title="editingId ? '编辑商品参数' : '新增商品参数'"
      size="720px"
      destroy-on-close
    >
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="104px">
        <div class="form-grid">
          <ElFormItem label="参数名称" prop="parameterName">
            <ElInput v-model="formData.parameterName" maxlength="64" placeholder="例如：辣度" />
          </ElFormItem>
          <ElFormItem label="参数编码" prop="parameterCode">
            <div class="parameter-code-field">
              <ElInput
                :model-value="formData.parameterCode"
                maxlength="64"
                placeholder="输入参数名称后自动生成"
                @update:model-value="handleParameterCodeInput"
              >
                <template #append>
                  <ElButton @click="regenerateParameterCode">重新生成</ElButton>
                </template>
              </ElInput>
              <small>仅用于系统内部识别，通常无需修改。</small>
            </div>
          </ElFormItem>
          <ElFormItem label="值类型" prop="valueType">
            <ElSelect v-model="formData.valueType" @change="handleValueTypeChange">
              <ElOption label="文本" value="TEXT" />
              <ElOption label="数字" value="NUMBER" />
              <ElOption label="单选" value="SINGLE_SELECT" />
              <ElOption label="多选" value="MULTI_SELECT" />
              <ElOption label="是/否" value="BOOLEAN" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="单位">
            <ElInput v-model="formData.unit" maxlength="24" placeholder="例如：g、cm" />
          </ElFormItem>
          <ElFormItem label="排序">
            <ElInputNumber v-model="formData.sortOrder" :min="0" :precision="0" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSwitch
              v-model="formData.status"
              active-value="ENABLED"
              inactive-value="DISABLED"
              active-text="启用"
              inactive-text="停用"
            />
          </ElFormItem>
        </div>

        <ElFormItem label="适用分类" prop="categoryIds">
          <ElTreeSelect
            v-model="formData.categoryIds"
            :data="categoryTreeOptions"
            node-key="value"
            multiple
            check-strictly
            show-checkbox
            default-expand-all
            :render-after-expand="false"
            filterable
            clearable
            style="width: 100%"
            placeholder="选择需要填写该参数的商品分类"
          />
        </ElFormItem>
        <ElFormItem label="参数说明">
          <ElInput
            v-model="formData.description"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
          />
        </ElFormItem>
        <ElFormItem label="参数用途">
          <ElCheckbox v-model="formData.required">必填</ElCheckbox>
          <ElCheckbox v-model="formData.filterable">可筛选</ElCheckbox>
          <ElCheckbox v-model="formData.cardVisible">商品卡片展示</ElCheckbox>
          <ElCheckbox v-model="formData.detailVisible">商品详情展示</ElCheckbox>
        </ElFormItem>

        <ElFormItem v-if="selectable" label="参数选项" required>
          <div class="option-editor">
            <div v-for="(option, index) in formData.options" :key="index" class="option-row">
              <ElInput
                v-model="option.optionLabel"
                maxlength="64"
                placeholder="显示名称，如：中辣"
              />
              <ElInput v-model="option.optionCode" maxlength="64" placeholder="编码，如：MEDIUM" />
              <ElInputNumber
                v-model="option.displayLevel"
                :min="0"
                :precision="0"
                placeholder="等级"
              />
              <ElButton type="danger" text @click="removeOption(index)">删除</ElButton>
            </div>
            <ElButton type="primary" plain @click="addOption">+ 添加选项</ElButton>
            <small>展示等级可用于辣椒数量、强度刻度等视觉表达；不需要时留空。</small>
          </div>
        </ElFormItem>
      </ElForm>

      <template #footer>
        <ElButton @click="editorVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
      </template>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, nextTick, onActivated, onMounted, reactive, ref, watch } from 'vue'
  import { useResizeObserver } from '@vueuse/core'
  import {
    ElMessage,
    ElMessageBox,
    type FormInstance,
    type FormRules,
    type TableInstance
  } from 'element-plus'
  import { useAuth } from '@/hooks/core/useAuth'
  import {
    createProductParameterDefinition,
    deleteProductParameterDefinition,
    fetchProductCategories,
    fetchProductParameterDefinitions,
    updateProductParameterDefinition
  } from '@/api/product'

  defineOptions({ name: 'ProductParameter' })

  type ParameterForm = Api.Product.ProductParameterDefinitionForm
  const loading = ref(false)
  const submitting = ref(false)
  const editorVisible = ref(false)
  const editingId = ref<number | null>(null)
  const items = ref<Api.Product.ProductParameterDefinition[]>([])
  const categories = ref<Api.Product.Category[]>([])
  const formRef = ref<FormInstance>()
  const tableRef = ref<TableInstance>()
  const tableContainerRef = ref<HTMLElement>()
  const parameterCodeManuallyEdited = ref(false)
  const { hasAuth } = useAuth()
  const canWrite = computed(() => hasAuth('product:parameter:write'))

  const layoutTable = () => {
    nextTick(() => tableRef.value?.doLayout())
  }

  useResizeObserver(tableContainerRef, () => {
    requestAnimationFrame(() => tableRef.value?.doLayout())
  })

  interface CategoryTreeOption {
    value: number
    label: string
    children?: CategoryTreeOption[]
  }

  const defaultForm = (): ParameterForm => ({
    parameterCode: '',
    parameterName: '',
    valueType: 'TEXT',
    unit: '',
    description: '',
    required: false,
    filterable: false,
    cardVisible: false,
    detailVisible: true,
    sortOrder: 0,
    status: 'ENABLED',
    categoryIds: [],
    options: []
  })
  const formData = reactive<ParameterForm>(defaultForm())
  const toCategoryTreeOptions = (nodes: Api.Product.Category[]): CategoryTreeOption[] =>
    nodes.map((node) => ({
      value: node.id,
      label: node.name,
      children: node.children?.length ? toCategoryTreeOptions(node.children) : undefined
    }))
  const categoryTreeOptions = computed(() => toCategoryTreeOptions(categories.value))
  const selectable = computed(
    () => formData.valueType === 'SINGLE_SELECT' || formData.valueType === 'MULTI_SELECT'
  )
  const rules: FormRules<ParameterForm> = {
    parameterName: [{ required: true, message: '请输入参数名称', trigger: 'blur' }],
    parameterCode: [
      { required: true, message: '请输入参数编码', trigger: 'blur' },
      { pattern: /^[A-Za-z0-9_-]+$/, message: '仅支持字母、数字、下划线和短横线', trigger: 'blur' }
    ],
    valueType: [{ required: true, message: '请选择值类型', trigger: 'change' }],
    categoryIds: [{ required: true, type: 'array', min: 1, message: '请至少选择一个分类' }]
  }

  const flattenCategories = (nodes: Api.Product.Category[]): Api.Product.Category[] =>
    nodes.flatMap((node) => [node, ...flattenCategories(node.children || [])])
  const categoryName = (categoryId: number) =>
    flattenCategories(categories.value).find((category) => category.id === categoryId)?.name ||
    `分类 ${categoryId}`

  const valueTypeLabel = (value: Api.Product.ProductParameterValueType) =>
    ({
      TEXT: '文本',
      NUMBER: '数字',
      SINGLE_SELECT: '单选',
      MULTI_SELECT: '多选',
      BOOLEAN: '是/否'
    })[value]

  const generatedParameterCode = (parameterName: string) => {
    const normalizedName = parameterName.trim()
    if (!normalizedName) return ''
    const readableCode = normalizedName
      .normalize('NFKD')
      .replace(/[^A-Za-z0-9_-]+/g, '_')
      .replace(/^_+|_+$/g, '')
      .replace(/_+/g, '_')
      .toUpperCase()
    if (readableCode) return readableCode.slice(0, 64)

    let hash = 2166136261
    for (const character of normalizedName) {
      hash ^= character.codePointAt(0) || 0
      hash = Math.imul(hash, 16777619)
    }
    return `PARAM_${(hash >>> 0).toString(16).toUpperCase().padStart(8, '0')}`
  }

  const handleParameterCodeInput = (value: string) => {
    parameterCodeManuallyEdited.value = true
    formData.parameterCode = value
  }

  const regenerateParameterCode = () => {
    parameterCodeManuallyEdited.value = false
    formData.parameterCode = generatedParameterCode(formData.parameterName)
  }

  const loadData = async () => {
    loading.value = true
    try {
      const [definitionItems, categoryItems] = await Promise.all([
        fetchProductParameterDefinitions(),
        fetchProductCategories()
      ])
      items.value = definitionItems
      categories.value = categoryItems
    } finally {
      loading.value = false
    }
  }

  const openEditor = (item?: Api.Product.ProductParameterDefinition) => {
    editingId.value = item?.id ?? null
    parameterCodeManuallyEdited.value = Boolean(item)
    Object.assign(formData, defaultForm())
    if (item) {
      Object.assign(formData, {
        parameterCode: item.parameterCode,
        parameterName: item.parameterName,
        valueType: item.valueType,
        unit: item.unit || '',
        description: item.description || '',
        required: item.required,
        filterable: item.filterable,
        cardVisible: item.cardVisible,
        detailVisible: item.detailVisible,
        sortOrder: item.sortOrder,
        status: item.status,
        categoryIds: [...item.categoryIds],
        options: item.options.map((option) => ({ ...option }))
      })
    }
    editorVisible.value = true
    requestAnimationFrame(() => formRef.value?.clearValidate())
  }

  watch(
    () => formData.parameterName,
    (parameterName) => {
      if (!editingId.value && !parameterCodeManuallyEdited.value) {
        formData.parameterCode = generatedParameterCode(parameterName)
      }
    }
  )

  const handleValueTypeChange = () => {
    if (!selectable.value) formData.options = []
    if (selectable.value && !formData.options.length) addOption()
  }

  const addOption = () => {
    formData.options.push({
      optionCode: '',
      optionLabel: '',
      displayLevel: null,
      sortOrder: formData.options.length
    })
  }
  const removeOption = (index: number) => formData.options.splice(index, 1)

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)
    if (!valid) return
    if (
      selectable.value &&
      formData.options.some((option) => !option.optionCode.trim() || !option.optionLabel.trim())
    ) {
      ElMessage.error('请完整填写所有参数选项')
      return
    }
    submitting.value = true
    try {
      const payload: ParameterForm = {
        ...formData,
        parameterCode: formData.parameterCode.trim().toUpperCase(),
        parameterName: formData.parameterName.trim(),
        unit: formData.unit.trim(),
        description: formData.description.trim(),
        options: formData.options.map((option, index) => ({
          ...option,
          optionCode: option.optionCode.trim().toUpperCase(),
          optionLabel: option.optionLabel.trim(),
          sortOrder: index
        }))
      }
      if (editingId.value) await updateProductParameterDefinition(editingId.value, payload)
      else await createProductParameterDefinition(payload)
      editorVisible.value = false
      await loadData()
    } finally {
      submitting.value = false
    }
  }

  const handleDelete = async (item: Api.Product.ProductParameterDefinition) => {
    await ElMessageBox.confirm(
      `确定删除参数“${item.parameterName}”吗？已有商品值时不能删除。`,
      '删除确认',
      {
        type: 'warning'
      }
    )
    await deleteProductParameterDefinition(item.id)
    await loadData()
  }

  onActivated(layoutTable)
  watch(canWrite, layoutTable)
  onMounted(loadData)
</script>

<style scoped lang="scss">
  .page-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;

    h3 {
      margin: 0;
    }
    p {
      margin: 6px 0 0;
      color: var(--el-text-color-secondary);
    }
  }

  .page-header__actions,
  .category-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .muted {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
  }
  .form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 18px;
  }
  .option-editor {
    display: grid;
    width: 100%;
    gap: 10px;
  }
  .option-row {
    display: grid;
    grid-template-columns: 1fr 1fr 120px auto;
    gap: 8px;
  }
  .option-editor small {
    color: var(--el-text-color-secondary);
  }
  .parameter-code-field {
    display: grid;
    width: 100%;
    gap: 6px;

    small {
      color: var(--el-text-color-secondary);
    }
  }
</style>
