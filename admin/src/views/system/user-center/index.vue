<template>
  <div class="mx-auto grid w-full max-w-5xl gap-5 p-4 lg:grid-cols-[300px_minmax(0,1fr)]">
    <ElCard class="h-fit">
      <div class="flex flex-col items-center py-6 text-center">
        <ElAvatar :size="84" :src="userInfo.avatar">
          {{ avatarFallback }}
        </ElAvatar>
        <h1 class="mt-4 text-xl font-semibold">{{ displayName }}</h1>
        <p class="mt-1 text-sm text-gray-500">{{ userInfo.userName }}</p>
        <p v-if="userInfo.email" class="mt-3 break-all text-sm text-gray-500">
          {{ userInfo.email }}
        </p>
        <div class="mt-5 flex flex-wrap justify-center gap-2">
          <ElTag v-for="role in roles" :key="role" effect="plain">{{ role }}</ElTag>
        </div>
      </div>
    </ElCard>

    <div class="grid gap-5">
      <ElCard>
        <template #header>
          <div>
            <h2 class="font-medium">基本资料</h2>
            <p class="mt-1 text-xs text-gray-500">以下信息来自当前登录的真实管理员账号</p>
          </div>
        </template>

        <ElForm
          ref="profileFormRef"
          :model="profileForm"
          :rules="profileRules"
          label-position="top"
        >
          <ElFormItem label="登录账号">
            <ElInput :model-value="userInfo.userName" disabled />
          </ElFormItem>
          <ElFormItem label="显示名称" prop="displayName">
            <ElInput v-model.trim="profileForm.displayName" maxlength="64" show-word-limit />
          </ElFormItem>
          <ElFormItem label="邮箱" prop="email">
            <ElInput
              v-model.trim="profileForm.email"
              type="email"
              maxlength="128"
              placeholder="可选"
              autocomplete="email"
            />
          </ElFormItem>
          <div class="flex justify-end">
            <ElButton type="primary" :loading="profileSaving" @click="saveProfile">
              保存资料
            </ElButton>
          </div>
        </ElForm>
      </ElCard>

      <ElCard>
        <template #header>
          <div>
            <h2 class="font-medium">修改密码</h2>
            <p class="mt-1 text-xs text-gray-500">
              修改成功后，当前账号的所有登录设备都会立即下线
            </p>
          </div>
        </template>

        <ElForm
          ref="passwordFormRef"
          :model="passwordForm"
          :rules="passwordRules"
          label-position="top"
        >
          <ElFormItem label="当前密码" prop="currentPassword">
            <ElInput
              v-model="passwordForm.currentPassword"
              type="password"
              autocomplete="current-password"
              show-password
            />
          </ElFormItem>
          <ElFormItem label="新密码" prop="newPassword">
            <ElInput
              v-model="passwordForm.newPassword"
              type="password"
              autocomplete="new-password"
              show-password
            />
          </ElFormItem>
          <ElFormItem label="确认新密码" prop="confirmPassword">
            <ElInput
              v-model="passwordForm.confirmPassword"
              type="password"
              autocomplete="new-password"
              show-password
            />
          </ElFormItem>
          <div class="flex justify-end">
            <ElButton type="primary" :loading="passwordSaving" @click="savePassword">
              修改密码
            </ElButton>
          </div>
        </ElForm>
      </ElCard>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { changeAdminPassword, fetchGetUserInfo, updateAdminProfile } from '@/api/auth'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'UserCenter' })

  interface ProfileForm {
    displayName: string
    email: string
  }

  interface PasswordForm {
    currentPassword: string
    newPassword: string
    confirmPassword: string
  }

  const userStore = useUserStore()
  const userInfo = computed(() => userStore.getUserInfo)
  const displayName = computed(
    () => userInfo.value.displayName || userInfo.value.userName || '管理员'
  )
  const avatarFallback = computed(() => displayName.value.slice(0, 1).toUpperCase())
  const roles = computed(() => userInfo.value.roles ?? [])

  const profileFormRef = ref<FormInstance>()
  const passwordFormRef = ref<FormInstance>()
  const profileSaving = ref(false)
  const passwordSaving = ref(false)

  const profileForm = reactive<ProfileForm>({
    displayName: '',
    email: ''
  })
  const passwordForm = reactive<PasswordForm>({
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  })

  const profileRules: FormRules<ProfileForm> = {
    displayName: [
      { required: true, message: '请输入显示名称', trigger: 'blur' },
      { max: 64, message: '显示名称不能超过 64 个字符', trigger: 'blur' }
    ],
    email: [
      { type: 'email', message: '请输入有效的邮箱地址', trigger: ['blur', 'change'] },
      { max: 128, message: '邮箱不能超过 128 个字符', trigger: 'blur' }
    ]
  }

  const validateConfirmedPassword = (
    _rule: unknown,
    value: string,
    callback: (error?: Error) => void
  ) => {
    if (!value) {
      callback(new Error('请再次输入新密码'))
      return
    }
    if (value !== passwordForm.newPassword) {
      callback(new Error('两次输入的新密码不一致'))
      return
    }
    callback()
  }

  const passwordRules: FormRules<PasswordForm> = {
    currentPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
    newPassword: [
      { required: true, message: '请输入新密码', trigger: 'blur' },
      { min: 8, max: 72, message: '新密码需为 8 至 72 个字符', trigger: 'blur' }
    ],
    confirmPassword: [{ validator: validateConfirmedPassword, trigger: 'blur' }]
  }

  const syncProfileForm = (info: Api.Auth.UserInfo) => {
    profileForm.displayName = info.displayName || info.userName
    profileForm.email = info.email || ''
  }

  onMounted(async () => {
    const info = await fetchGetUserInfo()
    userStore.setUserInfo(info)
    syncProfileForm(info)
  })

  const saveProfile = async () => {
    if (!profileFormRef.value) return
    await profileFormRef.value.validate()
    profileSaving.value = true
    try {
      const info = await updateAdminProfile({
        displayName: profileForm.displayName,
        email: profileForm.email
      })
      userStore.setUserInfo(info)
      syncProfileForm(info)
      ElMessage.success('资料已保存')
    } finally {
      profileSaving.value = false
    }
  }

  const savePassword = async () => {
    if (!passwordFormRef.value) return
    await passwordFormRef.value.validate()
    await ElMessageBox.confirm('修改后当前设备也会退出，需要使用新密码重新登录。', '确认修改密码', {
      confirmButtonText: '修改并退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    passwordSaving.value = true
    try {
      await changeAdminPassword({
        currentPassword: passwordForm.currentPassword,
        newPassword: passwordForm.newPassword
      })
      ElMessage.success('密码已修改，请重新登录')
      userStore.logOut()
    } finally {
      passwordSaving.value = false
    }
  }
</script>
