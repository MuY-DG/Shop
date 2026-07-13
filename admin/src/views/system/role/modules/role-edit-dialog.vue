<template>
  <ElDialog
    v-model="visible"
    :title="dialogType === 'add' ? '新增角色' : '编辑角色'"
    width="520px"
    align-center
    destroy-on-close
  >
    <ElForm ref="formRef" :model="form" :rules="rules" label-width="100px">
      <ElFormItem label="角色名称" prop="name">
        <ElInput v-model="form.name" placeholder="请输入角色名称" />
      </ElFormItem>
      <ElFormItem label="角色编码" prop="code">
        <ElInput v-model="form.code" placeholder="例如 R_OPERATOR" />
      </ElFormItem>
      <ElFormItem label="描述" prop="description">
        <ElInput
          v-model="form.description"
          type="textarea"
          :rows="3"
          placeholder="请输入角色描述"
        />
      </ElFormItem>
      <ElFormItem label="启用">
        <ElSwitch v-model="form.enabled" />
      </ElFormItem>
    </ElForm>

    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage } from 'element-plus'
  import { createAdminRole, updateAdminRole } from '@/api/system-manage'

  type RoleListItem = Api.SystemManage.RoleListItem

  const props = withDefaults(
    defineProps<{
      modelValue: boolean
      dialogType: 'add' | 'edit'
      roleData?: RoleListItem
    }>(),
    { modelValue: false, dialogType: 'add', roleData: undefined }
  )

  const emit = defineEmits<{
    (e: 'update:modelValue', value: boolean): void
    (e: 'success'): void
  }>()

  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })
  const formRef = ref<FormInstance>()
  const submitting = ref(false)
  const form = reactive<Api.SystemManage.RoleForm>({
    name: '',
    code: '',
    description: '',
    enabled: true
  })
  const rules: FormRules = {
    name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
    code: [{ required: true, message: '请输入角色编码', trigger: 'blur' }]
  }

  watch(
    () => props.modelValue,
    (open) => {
      if (!open) return
      Object.assign(form, {
        name: props.roleData?.name || '',
        code: props.roleData?.code || '',
        description: props.roleData?.description || '',
        enabled: props.roleData?.enabled ?? true
      })
      nextTick(() => formRef.value?.clearValidate())
    }
  )

  const handleSubmit = async () => {
    if (!formRef.value) return
    await formRef.value.validate()
    submitting.value = true
    try {
      if (props.dialogType === 'add') {
        await createAdminRole({ ...form })
        ElMessage.success('角色创建成功')
      } else if (props.roleData) {
        await updateAdminRole(props.roleData.id, { ...form })
        ElMessage.success('角色更新成功')
      }
      visible.value = false
      emit('success')
    } finally {
      submitting.value = false
    }
  }
</script>
