<template>
  <div v-loading="loading" class="product-parameter-tab">
    <div class="product-parameter-tab__heading">
      <div>
        <h3>商品参数</h3>
        <p>参数由商品分类决定，只用于商品卡片或详情展示，不生成 SKU。</p>
      </div>
      <ElTag type="info">{{ definitions.length }} 个可用参数</ElTag>
    </div>

    <ElAlert
      v-if="!modelValue.categoryId"
      type="info"
      :closable="false"
      title="请先在商品信息中选择商品分类"
    />
    <ElEmpty v-else-if="!loading && !definitions.length" description="当前分类尚未配置商品参数" />
    <ElForm v-else label-position="top" class="parameter-form">
      <ElFormItem
        v-for="definition in definitions"
        :key="definition.id"
        :required="definition.required"
      >
        <template #label>
          <span class="parameter-label">
            <span>{{ definition.parameterName }}</span>
            <ElTag v-if="definition.cardVisible" size="small" type="success">卡片展示</ElTag>
            <ElTag v-if="definition.detailVisible" size="small" type="info">详情展示</ElTag>
            <small v-if="definition.description">{{ definition.description }}</small>
          </span>
        </template>

        <ElInput
          v-if="definition.valueType === 'TEXT'"
          :model-value="valueFor(definition.id).textValue || ''"
          :disabled="disabled"
          maxlength="500"
          show-word-limit
          @update:model-value="updateText(definition.id, $event)"
        />
        <ElInputNumber
          v-else-if="definition.valueType === 'NUMBER'"
          :model-value="valueFor(definition.id).numberValue ?? undefined"
          :disabled="disabled"
          :precision="6"
          controls-position="right"
          @update:model-value="updateNumber(definition.id, $event)"
        />
        <ElSwitch
          v-else-if="definition.valueType === 'BOOLEAN'"
          :model-value="valueFor(definition.id).booleanValue ?? false"
          :disabled="disabled"
          inline-prompt
          active-text="是"
          inactive-text="否"
          @update:model-value="updateBoolean(definition.id, $event)"
        />
        <ElSelect
          v-else
          :model-value="selectValue(definition)"
          :multiple="definition.valueType === 'MULTI_SELECT'"
          :disabled="disabled"
          clearable
          filterable
          style="width: 100%"
          @update:model-value="updateOptions(definition, $event)"
        >
          <ElOption
            v-for="option in definition.options"
            :key="option.optionCode"
            :value="option.optionCode"
            :label="optionLabel(option)"
          />
        </ElSelect>
        <span v-if="definition.unit" class="parameter-unit">单位：{{ definition.unit }}</span>
      </ElFormItem>
    </ElForm>
  </div>
</template>

<script setup lang="ts">
  import { ref, watch } from 'vue'
  import { ElMessage } from 'element-plus'
  import { fetchProductParameterDefinitions } from '@/api/product'
  import type { ProductEditorForm } from './editor-model'

  interface Props {
    modelValue: ProductEditorForm
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorForm): void
  }

  const props = withDefaults(defineProps<Props>(), { disabled: false })
  const emit = defineEmits<Emits>()
  const loading = ref(false)
  const definitions = ref<Api.Product.ProductParameterDefinition[]>([])
  let loadSequence = 0

  const emptyValue = (parameterId: number): Api.Product.SpuParameterValue => ({
    parameterId,
    textValue: null,
    numberValue: null,
    booleanValue: null,
    optionCodes: []
  })

  const valueFor = (parameterId: number) =>
    props.modelValue.parameterValues.find((value) => value.parameterId === parameterId) ||
    emptyValue(parameterId)

  const commitValue = (nextValue: Api.Product.SpuParameterValue) => {
    const values = props.modelValue.parameterValues.filter(
      (value) => value.parameterId !== nextValue.parameterId
    )
    values.push(nextValue)
    emit('update:modelValue', { ...props.modelValue, parameterValues: values })
  }

  const updateText = (parameterId: number, textValue: string) =>
    commitValue({ ...valueFor(parameterId), textValue })
  const updateNumber = (parameterId: number, numberValue: number | undefined) =>
    commitValue({ ...valueFor(parameterId), numberValue: numberValue ?? null })
  const updateBoolean = (parameterId: number, booleanValue: string | number | boolean) =>
    commitValue({ ...valueFor(parameterId), booleanValue: booleanValue === true })

  const selectValue = (definition: Api.Product.ProductParameterDefinition) => {
    const codes = valueFor(definition.id).optionCodes || []
    return definition.valueType === 'MULTI_SELECT' ? codes : codes[0] || ''
  }

  const updateOptions = (
    definition: Api.Product.ProductParameterDefinition,
    value: string | string[]
  ) => {
    const optionCodes = Array.isArray(value) ? value : value ? [value] : []
    commitValue({ ...valueFor(definition.id), optionCodes })
  }

  const optionLabel = (option: Api.Product.ProductParameterOption) =>
    option.displayLevel == null
      ? option.optionLabel
      : `${option.optionLabel}（展示等级 ${option.displayLevel}）`

  const loadDefinitions = async (categoryId: number | null) => {
    const sequence = ++loadSequence
    if (!categoryId) {
      definitions.value = []
      return
    }
    loading.value = true
    try {
      const nextDefinitions = await fetchProductParameterDefinitions({
        categoryId,
        enabledOnly: true
      })
      if (sequence !== loadSequence) return
      definitions.value = nextDefinitions
      const allowedIds = new Set(nextDefinitions.map((definition) => definition.id))
      const retainedValues = props.modelValue.parameterValues.filter((value) =>
        allowedIds.has(value.parameterId)
      )
      if (retainedValues.length !== props.modelValue.parameterValues.length) {
        emit('update:modelValue', { ...props.modelValue, parameterValues: retainedValues })
      }
    } finally {
      if (sequence === loadSequence) loading.value = false
    }
  }

  const hasValue = (
    definition: Api.Product.ProductParameterDefinition,
    value: Api.Product.SpuParameterValue
  ) => {
    if (definition.valueType === 'TEXT') return Boolean(value.textValue?.trim())
    if (definition.valueType === 'NUMBER') return value.numberValue != null
    if (definition.valueType === 'BOOLEAN') return value.booleanValue != null
    return Boolean(value.optionCodes?.length)
  }

  const validate = async () => {
    const missing = definitions.value.find(
      (definition) => definition.required && !hasValue(definition, valueFor(definition.id))
    )
    if (missing) {
      ElMessage.error(`请填写商品参数“${missing.parameterName}”`)
      return false
    }
    return true
  }

  watch(() => props.modelValue.categoryId, loadDefinitions, { immediate: true })
  defineExpose({ validate })
</script>

<style scoped lang="scss">
  .product-parameter-tab {
    min-height: 420px;
    padding: 20px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 12px;
  }

  .product-parameter-tab__heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 20px;

    h3 {
      margin: 0;
      font-size: 16px;
    }

    p {
      margin: 6px 0 0;
      color: var(--el-text-color-secondary);
    }
  }

  .parameter-form {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 4px 20px;
  }

  .parameter-form :deep(.el-form-item__label) {
    display: flex;
    align-items: center;
    height: auto !important;
    min-height: var(--el-component-custom-height);
    line-height: 1.4 !important;
  }

  .parameter-label {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
    max-width: 100%;
    vertical-align: top;

    small {
      color: var(--el-text-color-secondary);
    }
  }

  .parameter-unit {
    margin-left: 10px;
    color: var(--el-text-color-secondary);
  }

  @media (width <= 900px) {
    .parameter-form {
      grid-template-columns: 1fr;
    }
  }
</style>
