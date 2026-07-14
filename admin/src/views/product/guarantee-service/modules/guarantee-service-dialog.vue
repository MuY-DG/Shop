<template>
  <ElDialog
    :model-value="visible"
    :title="isEdit ? '编辑保障服务' : '添加保障服务'"
    width="640px"
    align-center
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="emit('update:visible', $event)"
    @closed="resetForm"
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="104px">
      <ElFormItem label="服务条款名称" prop="termsName">
        <ElInput
          v-model="formData.termsName"
          maxlength="64"
          show-word-limit
          placeholder="例如：7 天无理由退换"
        />
      </ElFormItem>

      <ElFormItem label="服务内容描述" prop="contentDescription">
        <ElInput
          v-model="formData.contentDescription"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          resize="vertical"
          placeholder="请输入服务范围、条件和说明"
        />
      </ElFormItem>

      <ElFormItem label="服务条款图标" prop="icon">
        <AssetPicker :model-value="iconAsset" media-kind="IMAGE" @change="handleIconChange" />
      </ElFormItem>

      <ElFormItem label="排序" prop="sortOrder">
        <ElInputNumber
          v-model="formData.sortOrder"
          :min="0"
          :precision="0"
          :step="1"
          controls-position="right"
          style="width: 100%"
        />
      </ElFormItem>

      <ElFormItem label="是否显示" prop="visible">
        <ElSwitch
          v-model="formData.visible"
          inline-prompt
          active-text="显示"
          inactive-text="隐藏"
        />
      </ElFormItem>
    </ElForm>

    <template #footer>
      <div class="dialog-footer">
        <ElButton @click="emit('update:visible', false)">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submit">保存</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { computed, nextTick, reactive, ref, watch } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import { createProductGuaranteeService, updateProductGuaranteeService } from '@/api/product'

  defineOptions({ name: 'ProductGuaranteeServiceDialog' })

  interface Props {
    visible: boolean
    service?: Api.Product.GuaranteeService | null
  }

  interface Emits {
    (event: 'update:visible', value: boolean): void
    (event: 'success', mode: 'create' | 'update'): void
  }

  const props = withDefaults(defineProps<Props>(), {
    service: null
  })
  const emit = defineEmits<Emits>()

  const formRef = ref<FormInstance>()
  const submitting = ref(false)

  const defaultForm = (): Api.Product.GuaranteeServiceForm => ({
    termsName: '',
    contentDescription: '',
    icon: '',
    iconFileId: null,
    sortOrder: 0,
    visible: true
  })

  const formData = reactive<Api.Product.GuaranteeServiceForm>(defaultForm())
  const isEdit = computed(() => props.service != null)

  const rules: FormRules<Api.Product.GuaranteeServiceForm> = {
    termsName: [
      { required: true, message: '请输入服务条款名称', trigger: 'blur' },
      { max: 64, message: '服务条款名称不能超过 64 个字符', trigger: 'blur' }
    ],
    contentDescription: [
      { required: true, message: '请输入服务内容描述', trigger: 'blur' },
      { max: 500, message: '服务内容描述不能超过 500 个字符', trigger: 'blur' }
    ],
    icon: [{ required: true, message: '请选择服务条款图标', trigger: 'change' }],
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

  const iconAsset = computed<Api.Common.AssetValue>(() => ({
    fileId: formData.iconFileId ?? null,
    url: formData.icon
  }))

  const resetForm = () => {
    Object.assign(formData, defaultForm())
    formRef.value?.clearValidate()
  }

  const syncForm = () => {
    const value = defaultForm()
    if (props.service) {
      Object.assign(value, {
        termsName: props.service.termsName,
        contentDescription: props.service.contentDescription,
        icon: props.service.icon,
        iconFileId: props.service.iconFileId ?? null,
        sortOrder: props.service.sortOrder,
        visible: props.service.visible
      })
    }
    Object.assign(formData, value)
    nextTick(() => formRef.value?.clearValidate())
  }

  watch(
    () => [props.visible, props.service] as const,
    ([visible]) => {
      if (visible) syncForm()
    },
    { immediate: true, deep: true }
  )

  const handleIconChange = (value: Api.Common.AssetValue) => {
    formData.icon = value.url
    formData.iconFileId = value.fileId
    formRef.value?.validateField('icon').catch(() => undefined)
  }

  const submit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)
    if (!valid) return

    const payload: Api.Product.GuaranteeServiceForm = {
      termsName: formData.termsName.trim(),
      contentDescription: formData.contentDescription.trim(),
      icon: formData.icon.trim(),
      iconFileId: formData.iconFileId ?? null,
      sortOrder: formData.sortOrder,
      visible: formData.visible
    }

    submitting.value = true
    try {
      if (props.service) {
        await updateProductGuaranteeService(props.service.id, payload)
        emit('success', 'update')
      } else {
        await createProductGuaranteeService(payload)
        emit('success', 'create')
      }
      emit('update:visible', false)
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped lang="scss">
  .dialog-footer {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
  }
</style>
