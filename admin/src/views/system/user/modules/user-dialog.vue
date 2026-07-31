<template>
  <ElDialog
    v-model="dialogVisible"
    :title="type === 'add' ? '新增账号' : '编辑账号'"
    width="560px"
    align-center
    destroy-on-close
  >
    <ElForm ref="formRef" :model="form" :rules="rules" label-width="126px">
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
      <ElAlert
        v-if="type === 'edit' && form.password"
        class="force-logout-alert"
        title="保存新密码后，该账号在所有设备上的登录都会立即失效，需要重新登录。"
        type="warning"
        show-icon
        :closable="false"
      />
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
          @change="handleRoleSelectionChange"
        >
          <ElOption
            v-for="role in roleOptions"
            :key="role.id"
            :label="`${role.name} (${role.code})`"
            :value="role.id"
            :disabled="role.code === GUEST_ROLE_CODE && protectedCustomerRoleIds.length > 0"
          />
        </ElSelect>
      </ElFormItem>
      <ElAlert
        v-if="protectedCustomerRoleLabels.length"
        class="customer-service-role-alert"
        :title="`已保留${protectedCustomerRoleLabels.join('、')}；客服身份请在客服管理中调整。`"
        type="info"
        show-icon
        :closable="false"
      />
      <ElFormItem label="同时登录设备上限" prop="maxSessions">
        <div class="session-limit-field">
          <ElInputNumber
            v-model="form.maxSessions"
            :min="0"
            :precision="0"
            :step="1"
            controls-position="right"
          />
          <div class="form-tip">
            0 表示不限制，1
            表示只能单设备登录，其他数字表示最多同时登录的设备数；保存后，超出上限的较早会话会立即下线。
          </div>
        </div>
      </ElFormItem>
      <ElFormItem v-if="type === 'edit'" label="状态" prop="status">
        <ElRadioGroup v-model="form.status">
          <ElRadio value="ENABLED">启用</ElRadio>
          <ElRadio value="DISABLED">停用</ElRadio>
        </ElRadioGroup>
      </ElFormItem>
      <ElAlert
        v-if="type === 'edit' && userData?.status !== 'DISABLED' && form.status === 'DISABLED'"
        class="force-logout-alert"
        title="停用后，该账号在所有设备上的登录都会立即失效。"
        type="warning"
        show-icon
        :closable="false"
      />
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
  import { useUserStore } from '@/store/modules/user'
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
  const userStore = useUserStore()
  const submitting = ref(false)
  const roleOptions = ref<Api.SystemManage.RoleListItem[]>([])
  const protectedCustomerRoleIds = ref<number[]>([])
  const protectedCustomerRoleLabels = ref<string[]>([])
  const previousEditableRoleIds = ref<number[]>([])
  const CUSTOMER_SERVICE_ROLE_CODES = new Set(['R_CUSTOMER_SERVICE', 'R_CUSTOMER_SERVICE_MANAGER'])
  const CUSTOMER_SERVICE_ROLE_LABELS: Record<string, string> = {
    R_CUSTOMER_SERVICE: '客服',
    R_CUSTOMER_SERVICE_MANAGER: '客服管理员'
  }
  const GUEST_ROLE_CODE = 'R_GUEST'
  const form = reactive({
    username: '',
    displayName: '',
    email: '',
    password: '',
    avatar: '',
    status: 'ENABLED' as Api.SystemManage.AdminUserStatus,
    maxSessions: 0,
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
    maxSessions: [
      {
        validator: (_rule: unknown, value: number, callback: (error?: Error) => void) => {
          if (!Number.isInteger(value) || value < 0) {
            callback(new Error('设备上限必须是大于等于 0 的整数'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    roleIds: [
      {
        validator: (_rule: unknown, value: number[], callback: (error?: Error) => void) => {
          const mergedRoleIds = new Set([...protectedCustomerRoleIds.value, ...(value || [])])
          if (!mergedRoleIds.size) {
            callback(new Error('请至少选择一个角色'))
            return
          }
          const guestRoleId = resolveGuestRoleId()
          if (
            guestRoleId !== undefined &&
            mergedRoleIds.has(guestRoleId) &&
            mergedRoleIds.size > 1
          ) {
            callback(new Error('游客角色不能与其他角色同时选择'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ]
  }

  const resetForm = () => {
    const existingRoleIds = props.userData?.roleIds || []
    const existingRoleCodes = props.userData?.roleCodes || []
    protectedCustomerRoleIds.value = existingRoleIds.filter((_roleId, index) =>
      CUSTOMER_SERVICE_ROLE_CODES.has(existingRoleCodes[index] || '')
    )
    protectedCustomerRoleLabels.value = existingRoleCodes
      .filter((roleCode) => CUSTOMER_SERVICE_ROLE_CODES.has(roleCode))
      .map((roleCode) => CUSTOMER_SERVICE_ROLE_LABELS[roleCode] || roleCode)
    previousEditableRoleIds.value = []
    Object.assign(form, {
      username: props.userData?.username || '',
      displayName: props.userData?.displayName || '',
      email: props.userData?.email || '',
      password: '',
      avatar: props.userData?.avatar || '',
      status: props.userData?.status || 'ENABLED',
      maxSessions: props.userData?.maxSessions ?? 0,
      roleIds: props.userData?.roleIds ? [...props.userData.roleIds] : []
    })
    nextTick(() => formRef.value?.clearValidate())
  }

  const loadRoles = async () => {
    const response = await fetchGetRoleList({ current: 1, size: 100, enabled: true })
    const existingRoleIds = new Set(props.userData?.roleIds || [])
    const protectedRoles = response.records.filter(
      (role) => CUSTOMER_SERVICE_ROLE_CODES.has(role.code) && existingRoleIds.has(role.id)
    )
    protectedCustomerRoleIds.value = [
      ...new Set([...protectedCustomerRoleIds.value, ...protectedRoles.map((role) => role.id)])
    ]
    protectedCustomerRoleLabels.value = [
      ...new Set([...protectedCustomerRoleLabels.value, ...protectedRoles.map((role) => role.name)])
    ]
    roleOptions.value = response.records.filter(
      (role) => !CUSTOMER_SERVICE_ROLE_CODES.has(role.code)
    )

    const protectedRoleIdSet = new Set(protectedCustomerRoleIds.value)
    let editableRoleIds = form.roleIds.filter((roleId) => !protectedRoleIdSet.has(roleId))
    const guestRoleId = resolveGuestRoleId()
    if (
      guestRoleId !== undefined &&
      editableRoleIds.includes(guestRoleId) &&
      (protectedCustomerRoleIds.value.length > 0 || editableRoleIds.length > 1)
    ) {
      editableRoleIds = editableRoleIds.filter((roleId) => roleId !== guestRoleId)
    }
    form.roleIds = editableRoleIds
    previousEditableRoleIds.value = [...editableRoleIds]
  }

  const resolveGuestRoleId = () =>
    roleOptions.value.find((role) => role.code === GUEST_ROLE_CODE)?.id

  const handleRoleSelectionChange = (selectedRoleIds: number[]) => {
    const guestRoleId = resolveGuestRoleId()
    let nextRoleIds = [...selectedRoleIds]
    if (guestRoleId !== undefined && nextRoleIds.includes(guestRoleId) && nextRoleIds.length > 1) {
      nextRoleIds = previousEditableRoleIds.value.includes(guestRoleId)
        ? nextRoleIds.filter((roleId) => roleId !== guestRoleId)
        : [guestRoleId]
    }
    form.roleIds = nextRoleIds
    previousEditableRoleIds.value = [...nextRoleIds]
  }

  const mergedRoleIds = () => [...new Set([...protectedCustomerRoleIds.value, ...form.roleIds])]

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
          maxSessions: form.maxSessions,
          roleIds: mergedRoleIds()
        })
        ElMessage.success('账号创建成功')
      } else if (props.userData) {
        const shouldLogoutCurrent =
          props.userData.id === userStore.info.userId && Boolean(form.password)
        await updateAdminUser(props.userData.id, {
          displayName: form.displayName,
          email: form.email,
          password: form.password || undefined,
          avatar: form.avatar,
          status: form.status,
          maxSessions: form.maxSessions,
          roleIds: mergedRoleIds()
        })
        ElMessage.success('账号更新成功')
        if (shouldLogoutCurrent) {
          dialogVisible.value = false
          userStore.logOut()
          return
        }
      }
      dialogVisible.value = false
      emit('success')
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped>
  .force-logout-alert {
    margin-bottom: 18px;
  }

  .customer-service-role-alert {
    width: calc(100% - 126px);
    margin: -6px 0 18px 126px;
  }

  .session-limit-field,
  .session-limit-field :deep(.el-input-number) {
    width: 100%;
  }

  .form-tip {
    margin-top: 6px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }
</style>
