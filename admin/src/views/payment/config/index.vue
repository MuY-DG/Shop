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
            <div class="section-header__subtitle">
              PEM 文件仅在浏览器本地读取，保存后以加密正文入库且不再回显
            </div>
          </div>
          <div class="section-header__actions">
            <ElTag :type="effectiveConfig ? 'success' : 'warning'">
              {{ runtimeLabel }}
            </ElTag>
            <ElButton
              type="primary"
              v-auth="'payment:config:write'"
              :disabled="creating || loading || saving || using || importing || deleting"
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
          </ElFormItem>

          <ElFormItem label="配置名称" prop="configName">
            <ElInput
              v-model="formData.configName"
              maxlength="80"
              placeholder="例如：生产微信支付"
            />
          </ElFormItem>
          <ElFormItem label="App ID" prop="appId">
            <ElInput
              v-model="formData.appId"
              maxlength="64"
              :placeholder="maskedPlaceholder(selectedConfig?.appIdMasked)"
            />
          </ElFormItem>
          <ElFormItem label="商户号" prop="mchId">
            <ElInput
              v-model="formData.mchId"
              maxlength="32"
              :placeholder="maskedPlaceholder(selectedConfig?.mchIdMasked)"
            />
          </ElFormItem>
          <ElFormItem label="商户证书序列号" prop="merchantSerialNo">
            <ElInput
              v-model="formData.merchantSerialNo"
              maxlength="128"
              :placeholder="maskedPlaceholder(selectedConfig?.merchantSerialNoMasked)"
            />
          </ElFormItem>
          <ElFormItem label="APIv3 Key" prop="apiV3Key">
            <ElInput
              v-model="formData.apiV3Key"
              maxlength="32"
              type="password"
              show-password
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
              placeholder="https://域名/wxpay/pay/notify"
            />
          </ElFormItem>
          <ElFormItem label="退款回调 URL" prop="refundNotifyUrl">
            <ElInput
              v-model="formData.refundNotifyUrl"
              maxlength="255"
              placeholder="https://域名/wxpay/refund/notify"
            />
          </ElFormItem>
          <ElFormItem label="验签模式" prop="verifyMode">
            <ElSegmented v-model="formData.verifyMode" :options="verifyModeOptions" />
          </ElFormItem>
          <ElFormItem label="微信公钥 ID" prop="wechatPublicKeyId">
            <ElInput
              v-model="formData.wechatPublicKeyId"
              maxlength="128"
              :disabled="formData.verifyMode !== 'PUBLIC_KEY'"
              :placeholder="maskedPlaceholder(selectedConfig?.wechatPublicKeyIdMasked)"
            />
          </ElFormItem>
          <ElFormItem label="商户私钥 PEM" prop="privateKeyPem">
            <PaymentSecretFileField
              key-type="PRIVATE_KEY"
              :model-value="formData.privateKeyPem || ''"
              :configured="selectedConfig?.privateKeyConfigured"
              @change="handleSecretFileChange('privateKeyPem', $event)"
            />
          </ElFormItem>
          <ElFormItem label="微信公钥 PEM" prop="wechatPublicKeyPem">
            <PaymentSecretFileField
              key-type="PUBLIC_KEY"
              :model-value="formData.wechatPublicKeyPem || ''"
              :configured="selectedConfig?.wechatPublicKeyConfigured"
              @change="handleSecretFileChange('wechatPublicKeyPem', $event)"
            />
          </ElFormItem>

          <ElFormItem>
            <ElButton
              type="primary"
              v-auth="'payment:config:write'"
              :loading="saving"
              :disabled="using || importing || deleting"
              @click="handleSave"
            >
              {{ creating ? '保存配置' : '保存修改' }}
            </ElButton>
            <ElButton
              type="success"
              v-auth="'payment:config:enable'"
              :loading="using"
              :disabled="currentUseState.disabled || saving || importing || deleting"
              @click="handleUse"
            >
              {{ creating ? '保存并使用' : currentUseState.label }}
            </ElButton>
            <ElButton
              v-if="selectedConfig?.legacySecretFilesPendingImport"
              v-auth="'payment:config:write'"
              type="warning"
              plain
              :loading="importing"
              :disabled="saving || using || deleting"
              @click="handleImportLegacySecrets"
            >
              迁移旧秘密文件
            </ElButton>
            <ElButton
              v-if="!creating"
              :disabled="!dirty || saving || using || importing || deleting"
              @click="resetSelectedForm"
            >
              撤销未保存修改
            </ElButton>
            <ElButton
              v-if="!creating && selectedConfig"
              v-auth="'payment:config:delete'"
              type="danger"
              plain
              :title="deleteState.reason || '删除配置'"
              :loading="deleting"
              :disabled="deleteState.disabled || saving || using || importing || deleting"
              @click="handleDelete"
            >
              删除配置
            </ElButton>
            <ElButton
              v-if="creating && configs.length > 0"
              :disabled="saving || using || importing || deleting"
              @click="cancelCreate"
            >
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
    deletePaymentConfig,
    enablePaymentConfig,
    fetchEffectivePaymentConfig,
    fetchPaymentConfigs,
    importLegacyPaymentSecretFiles,
    updatePaymentConfig
  } from '@/api/payment'
  import { paymentConfigDeleteState, paymentConfigUseState } from './payment-config-state'

  defineOptions({ name: 'PaymentConfig' })

  type PaymentConfigSelection = number

  const loading = ref(false)
  const saving = ref(false)
  const using = ref(false)
  const importing = ref(false)
  const deleting = ref(false)
  const creating = ref(false)
  const configs = ref<Api.Payment.Config[]>([])
  const effectiveConfig = ref<Api.Payment.EffectiveConfig | null>(null)
  const selectedConfigKey = ref<PaymentConfigSelection | null>(null)
  const baseline = ref('')
  const formRef = ref<FormInstance>()

  const createDefaultForm = (): Api.Payment.ConfigForm => ({
    configName: '',
    appId: '',
    mchId: '',
    merchantSerialNo: '',
    apiV3Key: '',
    privateKeyPem: '',
    verifyMode: 'PUBLIC_KEY',
    wechatPublicKeyId: '',
    wechatPublicKeyPem: '',
    notifyUrl: '',
    refundNotifyUrl: ''
  })

  const formData = reactive<Api.Payment.ConfigForm>(createDefaultForm())
  const verifyModeOptions = [{ label: '微信公钥', value: 'PUBLIC_KEY' }]

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
      privateKeyPem: formData.privateKeyPem,
      verifyMode: formData.verifyMode,
      wechatPublicKeyId: formData.wechatPublicKeyId,
      wechatPublicKeyPem: formData.wechatPublicKeyPem,
      notifyUrl: formData.notifyUrl,
      refundNotifyUrl: formData.refundNotifyUrl
    })
  const dirty = computed(() => snapshot() !== baseline.value)
  const currentUseState = computed(() =>
    selectedConfig.value
      ? paymentConfigUseState(selectedConfig.value, effectiveConfig.value, dirty.value)
      : {
          active: false,
          disabled: false,
          label: '保存并使用' as const
        }
  )
  const deleteState = computed(() =>
    selectedConfig.value
      ? paymentConfigDeleteState(selectedConfig.value, effectiveConfig.value)
      : {
          disabled: true,
          reason: '请选择支付配置'
        }
  )
  const runtimeLabel = computed(() => {
    if (!effectiveConfig.value) {
      return '当前无正在使用的支付配置'
    }
    return `正在使用：${effectiveConfig.value.configName}`
  })

  const hasText = (value?: string | null) => Boolean(String(value || '').trim())

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

  const preserveablePemRule = (message: string, configured = false) => ({
    validator: (_rule: unknown, value: string | undefined, callback: (error?: Error) => void) => {
      if (hasText(value) || configured) {
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
          const candidate = String(value || '').trim()
          if (!selectedConfig.value?.apiV3KeyConfigured && !candidate) {
            callback(new Error('请输入 APIv3 Key'))
            return
          }
          if (candidate && new TextEncoder().encode(candidate).byteLength !== 32) {
            callback(new Error('APIv3 Key 必须恰好为 32 个 UTF-8 字节'))
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
    privateKeyPem: [
      preserveablePemRule('请选择商户私钥 PEM', selectedConfig.value?.privateKeyConfigured)
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
    wechatPublicKeyPem: [
      preserveablePemRule('请选择微信公钥 PEM', selectedConfig.value?.wechatPublicKeyConfigured)
    ]
  }))

  const trimText = (value?: string) => String(value || '').trim()
  const maskedPlaceholder = (value?: string | null) =>
    value ? `当前：${value}，留空不修改` : '请输入完整值'

  const fillForm = (config?: Api.Payment.Config | null) => {
    Object.assign(formData, {
      ...createDefaultForm(),
      configName: config?.configName || '',
      verifyMode: 'PUBLIC_KEY',
      notifyUrl: config?.notifyUrl || '',
      refundNotifyUrl: config?.refundNotifyUrl || ''
    })
    baseline.value = snapshot()
    formRef.value?.clearValidate()
  }

  const selectConfig = (config: Api.Payment.Config) => {
    creating.value = false
    selectedConfigKey.value = config.id
    fillForm(config)
  }

  const loadData = async (preferredSelection?: PaymentConfigSelection) => {
    loading.value = true
    try {
      const [effectiveState, response] = await Promise.all([
        fetchEffectivePaymentConfig(),
        fetchPaymentConfigs({ current: 1, size: 100 })
      ])
      const effective = effectiveState.config || null
      effectiveConfig.value = effective
      configs.value = response.records

      const candidateSelections = [
        preferredSelection,
        selectedConfigKey.value,
        effective?.id,
        response.records[0]?.id
      ]
      const targetSelection = candidateSelections.find((candidate) =>
        response.records.some((config) => config.id === candidate)
      )
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
    const activeId = effectiveConfig.value?.id || configs.value[0]?.id
    const target = configs.value.find((config) => config.id === activeId) || configs.value[0]
    if (target) {
      selectConfig(target)
      return
    }
    fillForm()
  }

  const resetSelectedForm = () => fillForm(selectedConfig.value)

  const handleSecretFileChange = (field: 'privateKeyPem' | 'wechatPublicKeyPem', value: string) => {
    formData[field] = value
    formRef.value?.validateField(field)
  }

  const buildPayload = (): Api.Payment.ConfigForm => {
    const payload: Api.Payment.ConfigForm = {
      configName: trimText(formData.configName),
      appId: trimText(formData.appId),
      mchId: trimText(formData.mchId),
      merchantSerialNo: trimText(formData.merchantSerialNo),
      verifyMode: formData.verifyMode,
      wechatPublicKeyId:
        formData.verifyMode === 'PUBLIC_KEY' ? trimText(formData.wechatPublicKeyId) : '',
      notifyUrl: trimText(formData.notifyUrl),
      refundNotifyUrl: trimText(formData.refundNotifyUrl)
    }

    const apiV3Key = trimText(formData.apiV3Key)
    if (apiV3Key) payload.apiV3Key = apiV3Key
    if (hasText(formData.privateKeyPem)) payload.privateKeyPem = formData.privateKeyPem
    if (hasText(formData.wechatPublicKeyPem)) {
      payload.wechatPublicKeyPem = formData.wechatPublicKeyPem
    }
    return payload
  }

  const persistCurrent = (showSuccessMessage = true) => {
    const payload = buildPayload()
    if (creating.value) return createPaymentConfig(payload, showSuccessMessage)
    if (!selectedConfig.value) throw new Error('请选择支付配置')
    return updatePaymentConfig(selectedConfig.value.id, payload, showSuccessMessage)
  }

  const handleImportLegacySecrets = async () => {
    if (!selectedConfig.value) return
    await ElMessageBox.confirm(
      '将读取旧私有存储中的密钥正文，加密写入支付配置，并解除旧文件引用。支付来源和启用状态不会改变。',
      '迁移旧秘密文件',
      {
        type: 'warning',
        confirmButtonText: '确认迁移',
        cancelButtonText: '取消'
      }
    )
    importing.value = true
    try {
      const imported = await importLegacyPaymentSecretFiles(selectedConfig.value.id, false)
      await loadData(imported.id)
      ElMessage.success('旧秘密文件已迁移为数据库加密正文')
    } finally {
      importing.value = false
    }
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
      await loadData(target.id)
      ElMessage.success(`已开始使用「${target.configName}」`)
    } finally {
      using.value = false
    }
  }

  const handleDelete = async () => {
    const config = selectedConfig.value
    if (!config || deleteState.value.disabled) return
    await ElMessageBox.confirm(
      `确定删除支付配置「${config.configName}」吗？这是软删除：删除后配置会从管理列表隐藏，但历史订单的支付查询、退款和回调不受影响。`,
      '确认删除支付配置',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    deleting.value = true
    try {
      await deletePaymentConfig(config.id, false)
      selectedConfigKey.value = null
      await loadData()
      ElMessage.success(`支付配置「${config.configName}」已删除`)
    } finally {
      deleting.value = false
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
