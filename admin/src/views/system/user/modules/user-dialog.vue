<template>
  <ElDialog
    v-model="dialogVisible"
    :title="type === 'add' ? '新增管理员' : '编辑管理员'"
    width="520px"
    align-center
    destroy-on-close
  >
    <ElForm ref="formRef" :model="form" :rules="rules" label-width="96px">
      <ElFormItem label="用户名" prop="username">
        <ElInput
          v-model="form.username"
          :disabled="type === 'edit'"
          placeholder="请输入登录用户名"
        />
      </ElFormItem>
      <ElFormItem label="显示名称" prop="displayName">
        <ElInput v-model="form.displayName" placeholder="请输入显示名称" />
      </ElFormItem>
      <ElFormItem label="邮箱" prop="email">
        <ElInput v-model="form.email" placeholder="请输入邮箱" />
      </ElFormItem>
      <ElFormItem :label="type === 'add' ? '登录密码' : '重置密码'" prop="password">
        <ElInput
          v-model="form.password"
          type="password"
          show-password
          :placeholder="type === 'add' ? '至少 6 位' : '留空则不修改'"
        />
      </ElFormItem>
      <ElFormItem label="头像地址" prop="avatar">
        <ElInput v-model="form.avatar" placeholder="可选" />
      </ElFormItem>
      <ElFormItem label="角色" prop="roleIds">
        <ElSelect
          v-model="form.roleIds"
          multiple
          filterable
          style="width: 100%"
          placeholder="请选择角色"
        >
          <ElOption
            v-for="role in roleOptions"
            :key="role.id"
            :label="`${role.name} (${role.code})`"
            :value="role.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem v-if="type === 'edit'" label="状态" prop="status">
        <ElRadioGroup v-model="form.status">
          <ElRadio value="ENABLED">启用</ElRadio>
          <ElRadio value="DISABLED">停用</ElRadio>
        </ElRadioGroup>
      </ElFormItem>
    </ElForm>

    <template #footer>
      <ElButton @click="dialogVisible = false">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage } from 'element-plus'
  import { createAdminUser, fetchGetRoleList, updateAdminUser } from '@/api/system-manage'
  import type { DialogType } from '@/types'

  interface Props {
    visible: boolean
    type: DialogType
    userData?: Api.SystemManage.UserListItem
  }

  const props = defineProps<Props>()
  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void
    (e: 'success'): void
  }>()

  const dialogVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const formRef = ref<FormInstance>()
  const submitting = ref(false)
  const roleOptions = ref<Api.SystemManage.RoleListItem[]>([])
  const form = reactive({
    username: '',
    displayName: '',
    email: '',
    password: '',
    avatar: '',
    status: 'ENABLED' as Api.SystemManage.AdminUserStatus,
    roleIds: [] as number[]
  })

  const validatePassword = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
    if (props.type === 'add' && !value) {
      callback(new Error('请输入登录密码'))
      return
    }
    if (value && value.length < 6) {
      callback(new Error('密码至少 6 位'))
      return
    }
    callback()
  }

  const rules: FormRules = {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    displayName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }],
    email: [
      { required: true, message: '请输入邮箱', trigger: 'blur' },
      { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
    ],
    password: [{ validator: validatePassword, trigger: 'blur' }],
    roleIds: [
      { type: 'array', required: true, min: 1, message: '请至少选择一个角色', trigger: 'change' }
    ]
  }

  const resetForm = () => {
    Object.assign(form, {
      username: props.userData?.username || '',
      displayName: props.userData?.displayName || '',
      email: props.userData?.email || '',
      password: '',
      avatar: props.userData?.avatar || '',
      status: props.userData?.status || 'ENABLED',
      roleIds: props.userData?.roleIds ? [...props.userData.roleIds] : []
    })
    nextTick(() => formRef.value?.clearValidate())
  }

  const loadRoles = async () => {
    const response = await fetchGetRoleList({ current: 1, size: 100, enabled: true })
    roleOptions.value = response.records
  }

  watch(
    () => props.visible,
    async (visible) => {
      if (!visible) return
      resetForm()
      await loadRoles()
    }
  )

  const handleSubmit = async () => {
    if (!formRef.value) return
    await formRef.value.validate()
    submitting.value = true
    try {
      if (props.type === 'add') {
        await createAdminUser({
          username: form.username,
          displayName: form.displayName,
          email: form.email,
          password: form.password,
          avatar: form.avatar,
          roleIds: form.roleIds
        })
        ElMessage.success('管理员创建成功')
      } else if (props.userData) {
        await updateAdminUser(props.userData.id, {
          displayName: form.displayName,
          email: form.email,
          password: form.password || undefined,
          avatar: form.avatar,
          status: form.status,
          roleIds: form.roleIds
        })
        ElMessage.success('管理员更新成功')
      }
      dialogVisible.value = false
      emit('success')
    } finally {
      submitting.value = false
    }
  }
</script>
