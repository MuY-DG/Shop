<template>
  <div class="payment-config">
    <ElAlert
      title="选择配置后可直接在下方编辑。正在使用的配置不能重复启用；切换到其他配置后，点击“使用此配置”即可立即生效。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-header__title">支付配置</div>
            <div class="section-header__subtitle"
              >配置项按名称在左、输入框在右的方式排列，保存敏感字段后只显示脱敏信息</div
            >
          </div>
          <div class="section-header__actions">
            <ElTag :type="effectiveConfig?.source === 'DB' ? 'success' : 'warning'">
              {{ runtimeLabel }}
            </ElTag>
            <ElButton
              type="primary"
              v-auth="'payment:config:write'"
              :disabled="creating || loading"
              @click="openCreateForm"
            >
              新增配置
            </ElButton>
          </div>
        </div>
      </template>

      <div v-loading="loading">
        <ElForm
          ref="formRef"
          :model="formData"
          :rules="rules"
          label-width="148px"
          class="payment-form"
        >
          <ElFormItem label="选择配置">
            <div class="config-selector">
              <ElInput v-if="creating" model-value="正在新增配置" disabled />
              <ElSelect
                v-else
                :model-value="selectedConfigKey"
                placeholder="请选择支付配置"
                @change="handleConfigSelect"
              >
                <ElOption
                  label="环境变量配置"
                  value="ENV"
                  :disabled="!environmentSetting?.available"
                >
                  <div class="config-option">
                    <span>环境变量配置</span>
                    <ElTag v-if="environmentUseState.active" type="success" size="small">
                      正在使用
                    </ElTag>
                    <ElTag v-else-if="!environmentSetting?.available" type="info" size="small">
                      配置不完整
                    </ElTag>
                  </div>
                </ElOption>
                <ElOption
                  v-for="config in configs"
                  :key="config.id"
                  :label="config.configName"
                  :value="config.id"
                >
                  <div class="config-option">
                    <span>{{ config.configName }}</span>
                    <ElTag
                      v-if="paymentConfigUseState(config, effectiveConfig).active"
                      type="success"
                      size="small"
                    >
                      正在使用
                    </ElTag>
                  </div>
                </ElOption>
              </ElSelect>
              <ElTag v-if="currentUseState.active" type="success">正在使用</ElTag>
            </div>
            <div v-if="selectedEnvironment" class="form-tip">
              环境变量配置来自后端启动环境，只能查看和切换，不能在此页面编辑。
            </div>
          </ElFormItem>

          <ElFormItem label="配置名称" prop="configName">
            <ElInput
              v-model="formData.configName"
              maxlength="80"
              :disabled="selectedEnvironment"
              placeholder="例如：生产微信支付"
            />
          </ElFormItem>
          <ElFormItem label="App ID" prop="appId">
            <ElInput
              v-model="formData.appId"
              maxlength="64"
              :disabled="selectedEnvironment"
              :placeholder="maskedPlaceholder(selectedConfig?.appIdMasked)"
            />
          </ElFormItem>
          <ElFormItem label="商户号" prop="mchId">
            <ElInput
              v-model="formData.mchId"
              maxlength="32"
              :disabled="selectedEnvironment"
              :placeholder="maskedPlaceholder(selectedConfig?.mchIdMasked)"
            />
          </ElFormItem>
          <ElFormItem label="商户证书序列号" prop="merchantSerialNo">
            <ElInput
              v-model="formData.merchantSerialNo"
              maxlength="128"
              :disabled="selectedEnvironment"
              :placeholder="maskedPlaceholder(selectedConfig?.merchantSerialNoMasked)"
            />
          </ElFormItem>
          <ElFormItem label="APIv3 Key" prop="apiV3Key">
            <ElInput
              v-model="formData.apiV3Key"
              maxlength="128"
              :type="selectedEnvironment ? 'text' : 'password'"
              :show-password="!selectedEnvironment"
              :disabled="selectedEnvironment"
              autocomplete="new-password"
              :placeholder="
                selectedConfig?.apiV3KeyConfigured ? '已配置，留空不修改' : '请输入 APIv3 Key'
              "
            />
          </ElFormItem>
          <ElFormItem label="支付回调 URL" prop="notifyUrl">
            <ElInput
              v-model="formData.notifyUrl"
              maxlength="255"
              :disabled="selectedEnvironment"
              placeholder="https://域名/wxpay/pay/notify"
            />
          </ElFormItem>
          <ElFormItem label="退款回调 URL" prop="refundNotifyUrl">
            <ElInput
              v-model="formData.refundNotifyUrl"
              maxlength="255"
              :disabled="selectedEnvironment"
              placeholder="https://域名/wxpay/refund/notify"
            />
          </ElFormItem>
          <ElFormItem label="验签模式" prop="verifyMode">
            <ElSegmented
              v-model="formData.verifyMode"
              :options="verifyModeOptions"
              :disabled="selectedEnvironment"
            />
          </ElFormItem>
          <ElFormItem label="微信公钥 ID" prop="wechatPublicKeyId">
            <ElInput
              v-model="formData.wechatPublicKeyId"
              maxlength="128"
              :disabled="selectedEnvironment || formData.verifyMode !== 'PUBLIC_KEY'"
              :placeholder="maskedPlaceholder(selectedConfig?.wechatPublicKeyIdMasked)"
            />
          </ElFormItem>
          <ElFormItem label="私钥文件" prop="privateKeyFileId">
            <ElInput v-if="selectedEnvironment" model-value="由服务器文件路径提供" disabled />
            <PaymentSecretFileField
              v-else
              :model-value="formData.privateKeyFileId"
              @change="handleSecretFileChange('privateKeyFileId', $event)"
            />
          </ElFormItem>
          <ElFormItem label="商户证书文件" prop="merchantCertificateFileId">
            <ElInput v-if="selectedEnvironment" model-value="由服务器环境变量管理" disabled />
            <PaymentSecretFileField
              v-else
              :model-value="formData.merchantCertificateFileId || null"
              @change="handleSecretFileChange('merchantCertificateFileId', $event)"
            />
          </ElFormItem>
          <ElFormItem label="微信公钥文件" prop="wechatPublicKeyFileId">
            <ElInput v-if="selectedEnvironment" model-value="由服务器文件路径提供" disabled />
            <PaymentSecretFileField
              v-else
              :model-value="formData.wechatPublicKeyFileId || null"
              @change="handleSecretFileChange('wechatPublicKeyFileId', $event)"
            />
          </ElFormItem>

          <ElFormItem>
            <ElButton
              v-if="!selectedEnvironment"
              type="primary"
              v-auth="'payment:config:write'"
              :loading="saving"
              :disabled="using"
              @click="handleSave"
            >
              {{ creating ? '保存配置' : '保存修改' }}
            </ElButton>
            <ElButton
              type="success"
              v-auth="'payment:config:enable'"
              :loading="using"
              :disabled="currentUseState.disabled || saving"
              @click="handleUse"
            >
              {{ creating ? '保存并使用' : currentUseState.label }}
            </ElButton>
            <ElButton
              v-if="!creating && !selectedEnvironment"
              :disabled="!dirty || saving || using"
              @click="resetSelectedForm"
            >
              撤销未保存修改
            </ElButton>
            <ElButton v-if="creating" :disabled="saving || using" @click="cancelCreate">
              取消新增
            </ElButton>
          </ElFormItem>
        </ElForm>
      </div>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import PaymentSecretFileField from '@/components/business/payment-secret-file-field/index.vue'
  import {
    createPaymentConfig,
    enablePaymentConfig,
    fetchEffectivePaymentConfig,
    fetchEnvironmentPaymentConfig,
    fetchPaymentConfigs,
    updatePaymentConfig,
    updatePaymentConfigSource
  } from '@/api/payment'
  import { environmentConfigUseState, paymentConfigUseState } from './payment-config-state'

  defineOptions({ name: 'PaymentConfig' })

  type PaymentConfigSelection = 'ENV' | number

  const loading = ref(false)
  const saving = ref(false)
  const using = ref(false)
  const creating = ref(false)
  const configs = ref<Api.Payment.Config[]>([])
  const effectiveConfig = ref<Api.Payment.EffectiveConfig | null>(null)
  const environmentSetting = ref<Api.Payment.EnvironmentConfig | null>(null)
  const selectedConfigKey = ref<PaymentConfigSelection | null>(null)
  const baseline = ref('')
  const formRef = ref<FormInstance>()

  const createDefaultForm = (): Api.Payment.ConfigForm => ({
    configName: '',
    appId: '',
    mchId: '',
    merchantSerialNo: '',
    apiV3Key: '',
    privateKeyFileId: null,
    merchantCertificateFileId: null,
    verifyMode: 'PUBLIC_KEY',
    wechatPublicKeyId: '',
    wechatPublicKeyFileId: null,
    notifyUrl: '',
    refundNotifyUrl: ''
  })

  const formData = reactive<Api.Payment.ConfigForm>(createDefaultForm())
  const verifyModeOptions = [{ label: '微信公钥', value: 'PUBLIC_KEY' }]

  const selectedEnvironment = computed(() => selectedConfigKey.value === 'ENV')
  const selectedConfig = computed(
    () => configs.value.find((config) => config.id === selectedConfigKey.value) || null
  )
  const snapshot = () =>
    JSON.stringify({
      configName: formData.configName,
      appId: formData.appId,
      mchId: formData.mchId,
      merchantSerialNo: formData.merchantSerialNo,
      apiV3Key: formData.apiV3Key,
      privateKeyFileId: formData.privateKeyFileId,
      merchantCertificateFileId: formData.merchantCertificateFileId,
      verifyMode: formData.verifyMode,
      wechatPublicKeyId: formData.wechatPublicKeyId,
      wechatPublicKeyFileId: formData.wechatPublicKeyFileId,
      notifyUrl: formData.notifyUrl,
      refundNotifyUrl: formData.refundNotifyUrl
    })
  const dirty = computed(() => snapshot() !== baseline.value)
  const environmentUseState = computed(() =>
    environmentConfigUseState(effectiveConfig.value, environmentSetting.value?.available)
  )
  const currentUseState = computed(() =>
    selectedEnvironment.value
      ? environmentUseState.value
      : selectedConfig.value
        ? paymentConfigUseState(selectedConfig.value, effectiveConfig.value, dirty.value)
        : {
            active: false,
            disabled: false,
            label: '保存并使用' as const
          }
  )
  const runtimeLabel = computed(() => {
    if (!effectiveConfig.value) return '暂无正在使用的配置'
    if (effectiveConfig.value.source === 'DB') {
      return `正在使用：${effectiveConfig.value.configName}`
    }
    return '正在使用：环境变量配置'
  })

  const hasText = (value?: string | null) => Boolean(String(value || '').trim())
  const hasNumber = (value?: number | null) => typeof value === 'number'

  const preserveableTextRule = (message: string, existingValue?: string | null) => ({
    validator: (_rule: unknown, value: string | undefined, callback: (error?: Error) => void) => {
      if (hasText(value) || (selectedConfig.value && hasText(existingValue))) {
        callback()
        return
      }
      callback(new Error(message))
    },
    trigger: 'blur'
  })

  const requiredFileRule = (message: string) => ({
    validator: (
      _rule: unknown,
      value: number | null | undefined,
      callback: (error?: Error) => void
    ) => {
      if (hasNumber(value)) {
        callback()
        return
      }
      callback(new Error(message))
    },
    trigger: 'change'
  })

  const rules = computed<FormRules<Api.Payment.ConfigForm>>(() => ({
    configName: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
    appId: [preserveableTextRule('请输入完整 App ID', selectedConfig.value?.appIdMasked)],
    mchId: [preserveableTextRule('请输入完整商户号', selectedConfig.value?.mchIdMasked)],
    merchantSerialNo: [
      preserveableTextRule('请输入完整商户证书序列号', selectedConfig.value?.merchantSerialNoMasked)
    ],
    apiV3Key: [
      {
        validator: (_rule, value, callback) => {
          if (!selectedConfig.value?.apiV3KeyConfigured && !String(value || '').trim()) {
            callback(new Error('请输入 APIv3 Key'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ],
    notifyUrl: [{ required: true, message: '请输入支付回调 URL', trigger: 'blur' }],
    refundNotifyUrl: [{ required: true, message: '请输入退款回调 URL', trigger: 'blur' }],
    verifyMode: [{ required: true, message: '请选择验签模式', trigger: 'change' }],
    privateKeyFileId: [requiredFileRule('请选择私钥文件')],
    merchantCertificateFileId: [
      {
        validator: (
          _rule: unknown,
          _value: number | null | undefined,
          callback: (error?: Error) => void
        ) => callback(),
        trigger: 'change'
      }
    ],
    wechatPublicKeyId: [
      {
        validator: (_rule, value, callback) => {
          if (
            formData.verifyMode === 'PUBLIC_KEY' &&
            !hasText(value) &&
            !(selectedConfig.value && hasText(selectedConfig.value.wechatPublicKeyIdMasked))
          ) {
            callback(new Error('请输入微信公钥 ID'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ],
    wechatPublicKeyFileId: [
      {
        validator: (_rule, value, callback) => {
          if (formData.verifyMode === 'PUBLIC_KEY' && !hasNumber(value)) {
            callback(new Error('请选择微信公钥文件'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ]
  }))

  const trimText = (value?: string) => String(value || '').trim()
  const maskedPlaceholder = (value?: string | null) =>
    value ? `当前：${value}，留空不修改` : '请输入完整值'

  const fillForm = (config?: Api.Payment.Config | null) => {
    Object.assign(formData, {
      ...createDefaultForm(),
      configName: config?.configName || '',
      privateKeyFileId: config?.privateKeyFileId ?? null,
      merchantCertificateFileId: config?.merchantCertificateFileId ?? null,
      verifyMode: 'PUBLIC_KEY',
      wechatPublicKeyFileId: config?.wechatPublicKeyFileId ?? null,
      notifyUrl: config?.notifyUrl || '',
      refundNotifyUrl: config?.refundNotifyUrl || ''
    })
    baseline.value = snapshot()
    formRef.value?.clearValidate()
  }

  const fillEnvironmentForm = () => {
    const config = environmentSetting.value?.config
    Object.assign(formData, {
      ...createDefaultForm(),
      configName: '环境变量配置',
      appId: config?.appIdMasked || '',
      mchId: config?.mchIdMasked || '',
      merchantSerialNo: config?.merchantSerialNoMasked || '',
      apiV3Key: config?.apiV3KeyConfigured ? '已配置（内容已隐藏）' : '未配置',
      verifyMode: 'PUBLIC_KEY',
      wechatPublicKeyId: config?.wechatPublicKeyIdMasked || '',
      notifyUrl: config?.notifyUrl || '',
      refundNotifyUrl: config?.refundNotifyUrl || ''
    })
    baseline.value = snapshot()
    formRef.value?.clearValidate()
  }

  const selectEnvironment = () => {
    creating.value = false
    selectedConfigKey.value = 'ENV'
    fillEnvironmentForm()
  }

  const selectConfig = (config: Api.Payment.Config) => {
    creating.value = false
    selectedConfigKey.value = config.id
    fillForm(config)
  }

  const loadData = async (preferredSelection?: PaymentConfigSelection) => {
    loading.value = true
    try {
      const [environmentResponse, effective, response] = await Promise.all([
        fetchEnvironmentPaymentConfig().catch(() => null),
        fetchEffectivePaymentConfig(),
        fetchPaymentConfigs({ current: 1, size: 100 })
      ])
      const environment: Api.Payment.EnvironmentConfig = environmentResponse || {
        available: true,
        config: effective.source === 'ENV' ? effective : null
      }
      environmentSetting.value = environment
      effectiveConfig.value = effective
      configs.value = response.records

      const targetSelection =
        preferredSelection ??
        selectedConfigKey.value ??
        (effective.source === 'ENV' ? 'ENV' : effective.id) ??
        (environment.available ? 'ENV' : null) ??
        response.records[0]?.id
      if (targetSelection === 'ENV' && environment.available) {
        selectEnvironment()
        return
      }
      const target = response.records.find((config) => config.id === targetSelection)
      if (target) {
        selectConfig(target)
      } else {
        creating.value = true
        selectedConfigKey.value = null
        fillForm()
      }
    } finally {
      loading.value = false
    }
  }

  const confirmDiscard = async () => {
    if (!dirty.value) return true
    try {
      await ElMessageBox.confirm('当前有未保存的修改，切换后这些修改会丢失。', '确认切换', {
        type: 'warning',
        confirmButtonText: '放弃修改并切换',
        cancelButtonText: '继续编辑'
      })
      return true
    } catch {
      return false
    }
  }

  const handleConfigSelect = async (selection: PaymentConfigSelection) => {
    if (selection === selectedConfigKey.value || !(await confirmDiscard())) return
    if (selection === 'ENV') {
      selectEnvironment()
      return
    }
    const target = configs.value.find((config) => config.id === selection)
    if (target) selectConfig(target)
  }

  const openCreateForm = async () => {
    if (!(await confirmDiscard())) return
    creating.value = true
    selectedConfigKey.value = null
    fillForm()
  }

  const cancelCreate = () => {
    if (effectiveConfig.value?.source === 'ENV' && environmentSetting.value?.available) {
      selectEnvironment()
      return
    }
    const activeId = effectiveConfig.value?.id || configs.value[0]?.id
    const target = configs.value.find((config) => config.id === activeId) || configs.value[0]
    if (target) {
      selectConfig(target)
      return
    }
    fillForm()
  }

  const resetSelectedForm = () => fillForm(selectedConfig.value)

  const handleSecretFileChange = (
    field: 'privateKeyFileId' | 'merchantCertificateFileId' | 'wechatPublicKeyFileId',
    value: number | null
  ) => {
    formData[field] = value
    formRef.value?.validateField(field)
  }

  const buildPayload = (): Api.Payment.ConfigForm => {
    const payload: Api.Payment.ConfigForm = {
      configName: trimText(formData.configName),
      appId: trimText(formData.appId),
      mchId: trimText(formData.mchId),
      merchantSerialNo: trimText(formData.merchantSerialNo),
      privateKeyFileId: formData.privateKeyFileId,
      merchantCertificateFileId: formData.merchantCertificateFileId || null,
      verifyMode: formData.verifyMode,
      wechatPublicKeyId:
        formData.verifyMode === 'PUBLIC_KEY' ? trimText(formData.wechatPublicKeyId) : '',
      wechatPublicKeyFileId:
        formData.verifyMode === 'PUBLIC_KEY' ? formData.wechatPublicKeyFileId || null : null,
      notifyUrl: trimText(formData.notifyUrl),
      refundNotifyUrl: trimText(formData.refundNotifyUrl)
    }

    const apiV3Key = trimText(formData.apiV3Key)
    if (apiV3Key) payload.apiV3Key = apiV3Key
    return payload
  }

  const persistCurrent = (showSuccessMessage = true) => {
    const payload = buildPayload()
    if (creating.value) return createPaymentConfig(payload, showSuccessMessage)
    if (!selectedConfig.value) throw new Error('请选择支付配置')
    return updatePaymentConfig(selectedConfig.value.id, payload, showSuccessMessage)
  }

  const handleSave = async () => {
    await formRef.value?.validate()
    saving.value = true
    try {
      const saved = await persistCurrent()
      await loadData(saved.id)
    } finally {
      saving.value = false
    }
  }

  const handleUse = async () => {
    if (selectedEnvironment.value) {
      await ElMessageBox.confirm('确定切换为环境变量配置吗？', '确认使用配置', {
        type: 'warning',
        confirmButtonText: '使用此配置',
        cancelButtonText: '取消'
      })
      using.value = true
      try {
        await updatePaymentConfigSource({ source: 'ENV' }, false)
        await loadData('ENV')
        ElMessage.success('已开始使用「环境变量配置」')
      } finally {
        using.value = false
      }
      return
    }

    await formRef.value?.validate()
    const configName = trimText(formData.configName)
    const saveFirst = creating.value || dirty.value
    await ElMessageBox.confirm(
      saveFirst
        ? `将先保存「${configName}」的当前内容，再设为正在使用的支付配置。`
        : `确定开始使用「${configName}」吗？`,
      '确认使用配置',
      {
        type: 'warning',
        confirmButtonText: saveFirst ? '保存并使用' : '使用此配置',
        cancelButtonText: '取消'
      }
    )

    using.value = true
    try {
      const target =
        saveFirst || !selectedConfig.value ? await persistCurrent(false) : selectedConfig.value
      await enablePaymentConfig(target.id, false)
      await updatePaymentConfigSource({ source: 'DB' }, false)
      await loadData(target.id)
      ElMessage.success(`已开始使用「${target.configName}」`)
    } finally {
      using.value = false
    }
  }

  onMounted(loadData)
</script>

<style scoped lang="scss">
  .payment-config {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .section-header {
    display: flex;
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .section-header__title {
    font-size: 15px;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .section-header__subtitle {
    margin-top: 2px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .section-header__actions,
  .config-selector,
  .config-option {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .section-header__actions {
    flex-shrink: 0;
  }

  .payment-form {
    max-width: 900px;
  }

  .config-selector {
    width: 100%;

    :deep(.el-select),
    :deep(.el-input) {
      width: min(100%, 520px);
    }
  }

  .config-option {
    justify-content: space-between;
    width: 100%;
  }

  .form-tip {
    width: 100%;
    margin-top: 4px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  @media (width <= 768px) {
    .section-header {
      flex-direction: column;
    }

    .section-header__actions {
      flex-wrap: wrap;
      width: 100%;
    }

    .config-selector {
      flex-direction: column;
      align-items: flex-start;
    }
  }
</style>
