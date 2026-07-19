<template>
  <ElDialog
    :model-value="visible"
    :title="isEdit ? '编辑分类' : '新增分类'"
    width="520px"
    align-center
    @update:model-value="emit('update:visible', $event)"
    @closed="handleClosed"
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="88px">
      <ElFormItem v-if="!isEdit" label="上级分类" prop="parentId">
        <ElTreeSelect
          v-model="formData.parentId"
          :data="treeOptions"
          node-key="value"
          check-strictly
          clearable
          default-expand-all
          :render-after-expand="false"
          placeholder="请选择上级分类"
          style="width: 100%"
        />
      </ElFormItem>
      <ElFormItem v-else label="上级分类">
        <div class="category-dialog__readonly-field">
          <span>{{ currentParentName }}</span>
          <small>如需调整层级，请在分类列表中拖动</small>
        </div>
      </ElFormItem>
      <ElFormItem label="分类名称" prop="name">
        <ElInput v-model="formData.name" maxlength="40" placeholder="请输入分类名称" />
      </ElFormItem>
      <ElFormItem label="分类图标">
        <AssetPicker v-model="iconAsset" media-kind="IMAGE" @change="handleIconChange" />
      </ElFormItem>
      <ElFormItem label="图标地址" prop="icon">
        <ElInput v-model="formData.icon" placeholder="请输入图标 URL" />
      </ElFormItem>
      <ElFormItem label="排序">
        <span class="category-dialog__sort-tip">
          {{ isEdit ? '请在分类列表中拖动调整顺序' : '新分类将添加到所选层级的末尾' }}
        </span>
      </ElFormItem>
      <ElFormItem label="状态" prop="status">
        <ElRadioGroup v-model="formData.status">
          <ElRadioButton value="ENABLED">启用</ElRadioButton>
          <ElRadioButton value="DISABLED">禁用</ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>
    </ElForm>

    <template #footer>
      <div class="dialog-footer">
        <ElButton @click="emit('update:visible', false)">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { computed, nextTick, reactive, ref, watch } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'

  interface TreeOption {
    value: number
    label: string
    children?: TreeOption[]
  }

  interface Props {
    visible: boolean
    category?: Api.Product.Category | null
    initialParentId?: number
    parentOptions: TreeOption[]
    submitting?: boolean
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit', value: Api.Product.CategoryForm): void
  }

  const props = withDefaults(defineProps<Props>(), {
    visible: false,
    category: null,
    initialParentId: 0,
    parentOptions: () => [],
    submitting: false
  })

  const emit = defineEmits<Emits>()

  const formRef = ref<FormInstance>()
  const defaultForm = (): Api.Product.CategoryForm => ({
    parentId: 0,
    name: '',
    icon: '',
    iconFileId: null,
    sortOrder: 0,
    status: 'ENABLED'
  })

  const formData = reactive<Api.Product.CategoryForm>(defaultForm())

  const treeOptions = computed<TreeOption[]>(() => [
    {
      value: 0,
      label: '顶级分类',
      children: props.parentOptions
    }
  ])

  const isEdit = computed(() => !!props.category?.id)

  const findOptionName = (items: TreeOption[], value: number): string | null => {
    for (const item of items) {
      if (item.value === value) return item.label
      const childName = findOptionName(item.children || [], value)
      if (childName) return childName
    }
    return null
  }

  const currentParentName = computed(() => {
    if (!props.category?.parentId) return '顶级分类'
    return (
      findOptionName(props.parentOptions, props.category.parentId) ||
      `分类 #${props.category.parentId}`
    )
  })

  const rules: FormRules<Api.Product.CategoryForm> = {
    name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
  }

  const syncForm = () => {
    const base = defaultForm()
    if (props.category?.id) {
      Object.assign(base, {
        parentId: props.category.parentId,
        name: props.category.name,
        icon: props.category.icon,
        iconFileId: props.category.iconFileId ?? null,
        sortOrder: props.category.sortOrder,
        status: props.category.status
      })
    } else {
      base.parentId = props.initialParentId ?? 0
    }
    Object.assign(formData, base)
  }

  watch(
    () => [props.visible, props.category],
    (value) => {
      const visible = value[0] as boolean
      if (!visible) return
      syncForm()
      nextTick(() => formRef.value?.clearValidate())
    },
    { immediate: true, deep: true }
  )

  const handleClosed = () => {
    formRef.value?.resetFields()
    Object.assign(formData, defaultForm())
  }

  const iconAsset = computed<Api.Common.AssetValue>({
    get: () => ({
      fileId: formData.iconFileId ?? null,
      url: formData.icon
    }),
    set: (value) => {
      formData.icon = value.url
      formData.iconFileId = value.fileId
    }
  })

  const handleIconChange = (value: Api.Common.AssetValue) => {
    formData.icon = value.url
    formData.iconFileId = value.fileId
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)
    if (!valid) return
    emit('submit', {
      parentId: formData.parentId ?? 0,
      name: formData.name.trim(),
      icon: formData.icon.trim(),
      iconFileId: formData.iconFileId ?? null,
      sortOrder: formData.sortOrder,
      status: formData.status
    })
  }
</script>

<style scoped lang="scss">
  .category-dialog__readonly-field {
    display: flex;
    flex-direction: column;
    line-height: 1.5;

    small {
      color: var(--el-text-color-placeholder);
    }
  }

  .category-dialog__sort-tip {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
</style>
