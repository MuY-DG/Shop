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
      <ElFormItem label="上级分类" prop="parentId">
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
      <ElFormItem label="分类名称" prop="name">
        <ElInput v-model="formData.name" maxlength="40" placeholder="请输入分类名称" />
      </ElFormItem>
      <ElFormItem label="分类图标">
        <AssetPicker v-model="iconAsset" media-kind="IMAGE" @change="handleIconChange" />
      </ElFormItem>
      <ElFormItem label="图标地址" prop="icon">
        <ElInput v-model="formData.icon" placeholder="请输入图标 URL" />
      </ElFormItem>
      <ElFormItem label="排序" prop="sortOrder">
        <ElInputNumber
          v-model="formData.sortOrder"
          :min="0"
          :step="1"
          :precision="0"
          controls-position="right"
          style="width: 100%"
        />
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

  const rules: FormRules<Api.Product.CategoryForm> = {
    name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }],
    sortOrder: [
      { required: true, message: '请输入排序值', trigger: 'blur' },
      {
        validator: (_rule, value, callback) => {
          if (!Number.isInteger(value) || value < 0) {
            callback(new Error('排序值需为大于等于 0 的整数'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ]
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
