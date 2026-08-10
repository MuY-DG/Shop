<!-- 注册页面 -->
<template>
  <div class="flex w-full h-screen">
    <LoginLeftView />

    <div class="relative flex-1">
      <AuthTopBar />

      <div class="auth-right-wrap">
        <div class="form">
          <h3 class="title">{{ $t('register.title') }}</h3>
          <p class="sub-title">{{ $t('register.subTitle') }}</p>
          <ElAlert
            class="mt-5"
            :type="registrationEnabled ? 'info' : 'warning'"
            :closable="false"
            :title="
              availabilityLoading
                ? $t('register.checkingAvailability')
                : registrationEnabled
                  ? $t('register.guestNotice')
                  : $t('register.closedNotice')
            "
          />
          <ElForm
            class="mt-7.5"
            ref="formRef"
            :model="formData"
            :rules="rules"
            label-position="top"
            :key="formKey"
          >
            <ElFormItem prop="username">
              <ElInput
                class="custom-height"
                v-model.trim="formData.username"
                :placeholder="$t('register.placeholder.username')"
                autocomplete="username"
              />
            </ElFormItem>

            <ElFormItem prop="password">
              <ElInput
                class="custom-height"
                v-model="formData.password"
                :placeholder="$t('register.placeholder.password')"
                type="password"
                autocomplete="new-password"
                show-password
              />
            </ElFormItem>

            <ElFormItem prop="confirmPassword">
              <ElInput
                class="custom-height"
                v-model="formData.confirmPassword"
                :placeholder="$t('register.placeholder.confirmPassword')"
                type="password"
                autocomplete="new-password"
                @keyup.enter="register"
                show-password
              />
            </ElFormItem>

            <div style="margin-top: 15px">
              <ElButton
                class="w-full custom-height"
                type="primary"
                @click="register"
                :loading="loading"
                :disabled="availabilityLoading || !registrationEnabled"
                v-ripple
              >
                {{ $t('register.submitBtnText') }}
              </ElButton>
            </div>

            <div class="mt-5 text-sm text-g-600">
              <span>{{ $t('register.hasAccount') }}</span>
              <RouterLink class="text-theme" :to="{ name: 'Login' }">{{
                $t('register.toLogin')
              }}</RouterLink>
            </div>
          </ElForm>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { ElMessage } from 'element-plus'
  import type { FormInstance, FormRules } from 'element-plus'
  import { fetchAdminRegistrationAvailability, registerGuestAdmin } from '@/api/auth'

  defineOptions({ name: 'Register' })

  interface RegisterForm {
    username: string
    password: string
    confirmPassword: string
  }

  const USERNAME_MIN_LENGTH = 3
  const USERNAME_MAX_LENGTH = 64
  const USERNAME_PATTERN = /^[A-Za-z0-9._-]+$/
  const PASSWORD_MIN_LENGTH = 8

  const { t, locale } = useI18n()
  const router = useRouter()
  const formRef = ref<FormInstance>()

  const loading = ref(false)
  const availabilityLoading = ref(true)
  const registrationEnabled = ref(false)
  const formKey = ref(0)

  // 监听语言切换，重置表单
  watch(locale, () => {
    formKey.value++
  })

  const formData = reactive<RegisterForm>({
    username: '',
    password: '',
    confirmPassword: ''
  })

  /**
   * 验证密码
   * 当密码输入后，如果确认密码已填写，则触发确认密码的验证
   */
  const validatePassword = (_rule: any, value: string, callback: (error?: Error) => void) => {
    if (!value) {
      callback(new Error(t('register.placeholder.password')))
      return
    }

    if (formData.confirmPassword) {
      formRef.value?.validateField('confirmPassword')
    }

    callback()
  }

  /**
   * 验证确认密码
   * 检查确认密码是否与密码一致
   */
  const validateConfirmPassword = (
    _rule: any,
    value: string,
    callback: (error?: Error) => void
  ) => {
    if (!value) {
      callback(new Error(t('register.rule.confirmPasswordRequired')))
      return
    }

    if (value !== formData.password) {
      callback(new Error(t('register.rule.passwordMismatch')))
      return
    }

    callback()
  }

  const rules = computed<FormRules<RegisterForm>>(() => ({
    username: [
      { required: true, message: t('register.placeholder.username'), trigger: 'blur' },
      {
        min: USERNAME_MIN_LENGTH,
        max: USERNAME_MAX_LENGTH,
        message: t('register.rule.usernameLength'),
        trigger: 'blur'
      },
      {
        pattern: USERNAME_PATTERN,
        message: t('register.rule.usernamePattern'),
        trigger: 'blur'
      }
    ],
    password: [
      { required: true, validator: validatePassword, trigger: 'blur' },
      { min: PASSWORD_MIN_LENGTH, message: t('register.rule.passwordLength'), trigger: 'blur' }
    ],
    confirmPassword: [{ required: true, validator: validateConfirmPassword, trigger: 'blur' }]
  }))

  onMounted(async () => {
    try {
      registrationEnabled.value = (await fetchAdminRegistrationAvailability()).enabled
    } catch {
      registrationEnabled.value = false
    } finally {
      availabilityLoading.value = false
    }
  })

  /**
   * 注册用户
   * 验证表单后提交注册请求
   */
  const register = async () => {
    if (!formRef.value || !registrationEnabled.value) return

    try {
      await formRef.value.validate()
      loading.value = true
      await registerGuestAdmin({
        username: formData.username,
        password: formData.password
      })
      ElMessage.success(t('register.successMessage'))
      await router.push({
        name: 'Login',
        query: { account: formData.username }
      })
    } catch (error) {
      console.error('注册失败:', error)
    } finally {
      loading.value = false
    }
  }
</script>

<style scoped>
  @import '../login/style.css';
</style>
