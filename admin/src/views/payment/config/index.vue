<template>
  <div class="payment-config art-full-height">
    <ElCard class="payment-config__effective" shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-header__title">有效支付配置</div>
            <div class="section-header__subtitle">运行时实际使用的微信支付配置，只展示 masked 元数据</div>
          </div>
          <ElTag v-if="effectiveConfig" :type="effectiveConfig.source === 'ENV' ? 'warning' : 'success'">
            {{ formatSource(effectiveConfig.source) }}
          </ElTag>
        </div>
      </template>

      <div v-loading="effectiveLoading">
        <ElEmpty v-if="!effectiveLoading && !effectiveConfig" description="暂无有效配置" />
        <ElDescriptions v-else-if="effectiveConfig" :column="3" border>
          <ElDescriptionsItem label="配置名">{{ formatText(effectiveConfig.configName) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="App ID">{{ formatText(effectiveConfig.appIdMasked) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="商户号">{{ formatText(effectiveConfig.mchIdMasked) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="商户证书序列号">
            {{ formatText(effectiveConfig.merchantSerialNoMasked) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="APIv3 Key">
            <ElTag size="small" :type="effectiveConfig.apiV3KeyConfigured ? 'success' : 'danger'">
              {{ effectiveConfig.apiV3KeyConfigured ? '已配置' : '未配置' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="验签模式">
            {{ formatVerifyMode(effectiveConfig.verifyMode) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="私钥文件 ID">
            {{ formatText(effectiveConfig.privateKeyFileId) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="商户证书文件 ID">
            {{ formatText(effectiveConfig.merchantCertificateFileId) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="微信公钥文件 ID">
            {{ formatText(effectiveConfig.wechatPublicKeyFileId) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="微信公钥 ID">
            {{ formatText(effectiveConfig.wechatPublicKeyIdMasked) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="支付回调" :span="2">
            <span class="text-ellipsis">{{ formatText(effectiveConfig.notifyUrl) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="退款回调" :span="3">
            <span class="text-ellipsis">{{ formatText(effectiveConfig.refundNotifyUrl) }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>
      </div>
    </ElCard>

    <ElCard class="art-table-card payment-config__table" shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-header__title">DB 配置</div>
            <div class="section-header__subtitle">DB 配置可作为有效配置或 ENV 配置的备用方案</div>
          </div>
          <div class="section-header__actions">
            <ElButton @click="loadConfigs">刷新</ElButton>
            <ElButton type="primary" v-auth="'payment:config:write'" @click="openCreateDrawer">
              新增配置
            </ElButton>
          </div>
        </div>
      </template>

      <ElTable v-loading="tableLoading" :data="configs" border>
        <ElTableColumn label="配置" min-width="220">
          <template #default="{ row }">
            <div class="config-name-cell">
              <span class="title">{{ row.configName }}</span>
              <span class="subtitle">{{ row.appIdMasked }} / {{ row.mchIdMasked }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="merchantSerialNoMasked" label="证书序列号" min-width="180" />
        <ElTableColumn label="验签" width="120">
          <template #default="{ row }">{{ formatVerifyMode(row.verifyMode) }}</template>
        </ElTableColumn>
        <ElTableColumn label="APIv3 Key" width="120">
          <template #default="{ row }">
            <ElTag size="small" :type="row.apiV3KeyConfigured ? 'success' : 'danger'">
              {{ row.apiV3KeyConfigured ? '已配置' : '未配置' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="文件 ID" min-width="190">
          <template #default="{ row }">
            <div class="file-id-cell">
              <span>私钥 {{ formatText(row.privateKeyFileId) }}</span>
              <span>证书 {{ formatText(row.merchantCertificateFileId) }}</span>
              <span>公钥 {{ formatText(row.wechatPublicKeyFileId) }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="启用" width="90">
          <template #default="{ row }">
            <ElTag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? 'Active' : 'Standby' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <ElButton type="primary" link v-auth="'payment:config:write'" @click="openEditDrawer(row)">
                编辑
              </ElButton>
              <ElButton
                v-if="!row.enabled"
                type="success"
                link
                v-auth="'payment:config:enable'"
                :loading="enablingId === row.id"
                @click="handleEnable(row)"
              >
                启用
              </ElButton>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="table-pagination">
        <ElPagination
          background
          layout="total, prev, pager, next, sizes"
          :current-page="pagination.current"
          :page-size="pagination.size"
          :total="pagination.total"
          @current-change="handleCurrentChange"
          @size-change="handleSizeChange"
        />
      </div>
    </ElCard>

    <ElDrawer
      v-model="drawerVisible"
      :title="editingConfig ? '编辑支付配置' : '新增支付配置'"
      size="760px"
      destroy-on-close
      append-to-body
    >
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="128px">
        <ElFormItem label="配置名" prop="configName">
          <ElInput v-model="formData.configName" maxlength="80" placeholder="例如：生产微信支付" />
        </ElFormItem>
        <ElFormItem label="App ID" prop="appId">
          <ElInput v-model="formData.appId" maxlength="64" :placeholder="maskedPlaceholder(editingConfig?.appIdMasked)" />
        </ElFormItem>
        <ElFormItem label="商户号" prop="mchId">
          <ElInput v-model="formData.mchId" maxlength="32" :placeholder="maskedPlaceholder(editingConfig?.mchIdMasked)" />
        </ElFormItem>
        <ElFormItem label="商户证书序列号" prop="merchantSerialNo">
          <ElInput
            v-model="formData.merchantSerialNo"
            maxlength="128"
            :placeholder="maskedPlaceholder(editingConfig?.merchantSerialNoMasked)"
          />
        </ElFormItem>
        <ElFormItem label="APIv3 Key" prop="apiV3Key">
          <ElInput
            v-model="formData.apiV3Key"
            maxlength="128"
            show-password
            autocomplete="new-password"
            :placeholder="editingConfig?.apiV3KeyConfigured ? '已配置，留空不修改' : '请输入 APIv3 Key'"
          />
        </ElFormItem>
        <ElFormItem label="支付回调 URL" prop="notifyUrl">
          <ElInput v-model="formData.notifyUrl" maxlength="255" placeholder="https://域名/wxpay/pay/notify" />
        </ElFormItem>
        <ElFormItem label="退款回调 URL" prop="refundNotifyUrl">
          <ElInput v-model="formData.refundNotifyUrl" maxlength="255" placeholder="https://域名/wxpay/refund/notify" />
        </ElFormItem>
        <ElFormItem label="验签模式" prop="verifyMode">
          <ElSegmented v-model="formData.verifyMode" :options="verifyModeOptions" />
        </ElFormItem>
        <ElFormItem label="微信公钥 ID" prop="wechatPublicKeyId">
          <ElInput
            v-model="formData.wechatPublicKeyId"
            maxlength="128"
            :disabled="formData.verifyMode !== 'PUBLIC_KEY'"
            :placeholder="maskedPlaceholder(editingConfig?.wechatPublicKeyIdMasked)"
          />
        </ElFormItem>
        <ElFormItem label="私钥文件" prop="privateKeyFileId">
          <AssetPicker
            :model-value="assetValue(formData.privateKeyFileId)"
            purpose="PAYMENT_CERTIFICATE"
            @change="handleFileChange('privateKeyFileId', $event)"
          />
        </ElFormItem>
        <ElFormItem label="商户证书文件" prop="merchantCertificateFileId">
          <AssetPicker
            :model-value="assetValue(formData.merchantCertificateFileId || null)"
            purpose="PAYMENT_CERTIFICATE"
            @change="handleFileChange('merchantCertificateFileId', $event)"
          />
        </ElFormItem>
        <ElFormItem label="微信公钥文件" prop="wechatPublicKeyFileId">
          <AssetPicker
            :model-value="assetValue(formData.wechatPublicKeyFileId || null)"
            purpose="PAYMENT_CERTIFICATE"
            @change="handleFileChange('wechatPublicKeyFileId', $event)"
          />
        </ElFormItem>
      </ElForm>

      <template #footer>
        <div class="drawer-footer">
          <ElButton @click="drawerVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
        </div>
      </template>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import {
    createPaymentConfig,
    enablePaymentConfig,
    fetchEffectivePaymentConfig,
    fetchPaymentConfigs,
    updatePaymentConfig
  } from '@/api/payment'

  defineOptions({ name: 'PaymentConfig' })

  const effectiveLoading = ref(false)
  const tableLoading = ref(false)
  const submitting = ref(false)
  const drawerVisible = ref(false)
  const enablingId = ref<number | null>(null)
  const effectiveConfig = ref<Api.Payment.EffectiveConfig | null>(null)
  const configs = ref<Api.Payment.Config[]>([])
  const editingConfig = ref<Api.Payment.Config | null>(null)
  const formRef = ref<FormInstance>()

  const pagination = reactive<Api.Common.PaginationParams>({
    current: 1,
    size: 20,
    total: 0
  })

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

  const verifyModeOptions = [
    { label: '微信公钥', value: 'PUBLIC_KEY' },
    { label: '平台证书', value: 'CERTIFICATE' }
  ]

  const rules = computed<FormRules<Api.Payment.ConfigForm>>(() => ({
    configName: [{ required: true, message: '请输入配置名', trigger: 'blur' }],
    appId: [{ required: true, message: '请输入完整 App ID', trigger: 'blur' }],
    mchId: [{ required: true, message: '请输入完整商户号', trigger: 'blur' }],
    merchantSerialNo: [{ required: true, message: '请输入完整商户证书序列号', trigger: 'blur' }],
    apiV3Key: [
      {
        validator: (_rule, value, callback) => {
          if (!editingConfig.value?.apiV3KeyConfigured && !String(value || '').trim()) {
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
    privateKeyFileId: [
      {
        validator: (_rule, value, callback) => {
          if (!value) {
            callback(new Error('请选择私钥文件'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    merchantCertificateFileId: [
      {
        validator: (_rule, value, callback) => {
          if (formData.verifyMode === 'CERTIFICATE' && !value) {
            callback(new Error('请选择商户证书文件'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    wechatPublicKeyId: [
      {
        validator: (_rule, value, callback) => {
          if (formData.verifyMode === 'PUBLIC_KEY' && !String(value || '').trim()) {
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
          if (formData.verifyMode === 'PUBLIC_KEY' && !value) {
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
  const formatText = (value?: string | number | null) => (value === null || value === undefined || value === '' ? '-' : String(value))
  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')
  const formatSource = (source?: string) => (source === 'ENV' ? 'ENV 配置' : 'DB 配置')
  const formatVerifyMode = (value?: string) => (value === 'CERTIFICATE' ? '平台证书' : '微信公钥')
  const maskedPlaceholder = (value?: string | null) => (value ? `当前：${value}，请输入完整新值` : '请输入完整值')
  const assetValue = (fileId: number | null): Api.Common.AssetValue => ({ fileId, url: '' })

  const resetForm = () => {
    Object.assign(formData, createDefaultForm())
    formRef.value?.clearValidate()
  }

  const handleFileChange = (
    field: 'privateKeyFileId' | 'merchantCertificateFileId' | 'wechatPublicKeyFileId',
    value: Api.Common.AssetValue
  ) => {
    formData[field] = value.fileId
    formRef.value?.validateField(field)
  }

  const loadEffective = async () => {
    effectiveLoading.value = true
    try {
      effectiveConfig.value = await fetchEffectivePaymentConfig()
    } finally {
      effectiveLoading.value = false
    }
  }

  const loadConfigs = async () => {
    tableLoading.value = true
    try {
      const response = await fetchPaymentConfigs({
        current: pagination.current,
        size: pagination.size
      })
      configs.value = response.records
      pagination.current = response.current
      pagination.size = response.size
      pagination.total = response.total
    } finally {
      tableLoading.value = false
    }
  }

  const loadAll = async () => {
    await Promise.all([loadEffective(), loadConfigs()])
  }

  const openCreateDrawer = () => {
    editingConfig.value = null
    resetForm()
    drawerVisible.value = true
  }

  const openEditDrawer = (row: Api.Payment.Config) => {
    editingConfig.value = row
    resetForm()
    Object.assign(formData, {
      configName: row.configName,
      privateKeyFileId: row.privateKeyFileId ?? null,
      merchantCertificateFileId: row.merchantCertificateFileId ?? null,
      verifyMode: row.verifyMode === 'CERTIFICATE' ? 'CERTIFICATE' : 'PUBLIC_KEY',
      wechatPublicKeyFileId: row.wechatPublicKeyFileId ?? null,
      notifyUrl: row.notifyUrl,
      refundNotifyUrl: row.refundNotifyUrl
    })
    drawerVisible.value = true
  }

  const buildPayload = (): Api.Payment.ConfigForm => {
    const payload: Api.Payment.ConfigForm = {
      configName: trimText(formData.configName),
      appId: trimText(formData.appId),
      mchId: trimText(formData.mchId),
      merchantSerialNo: trimText(formData.merchantSerialNo),
      privateKeyFileId: formData.privateKeyFileId,
      merchantCertificateFileId:
        formData.verifyMode === 'CERTIFICATE' ? formData.merchantCertificateFileId || null : formData.merchantCertificateFileId || null,
      verifyMode: formData.verifyMode,
      wechatPublicKeyId: formData.verifyMode === 'PUBLIC_KEY' ? trimText(formData.wechatPublicKeyId) : '',
      wechatPublicKeyFileId: formData.verifyMode === 'PUBLIC_KEY' ? formData.wechatPublicKeyFileId || null : null,
      notifyUrl: trimText(formData.notifyUrl),
      refundNotifyUrl: trimText(formData.refundNotifyUrl)
    }

    const apiV3Key = trimText(formData.apiV3Key)
    if (apiV3Key) payload.apiV3Key = apiV3Key
    return payload
  }

  const handleSubmit = async () => {
    await formRef.value?.validate()
    submitting.value = true
    try {
      const payload = buildPayload()
      if (editingConfig.value) {
        await updatePaymentConfig(editingConfig.value.id, payload)
      } else {
        await createPaymentConfig(payload)
      }
      drawerVisible.value = false
      await loadAll()
    } finally {
      submitting.value = false
    }
  }

  const handleEnable = async (row: Api.Payment.Config) => {
    await ElMessageBox.confirm(`确定启用支付配置「${row.configName}」吗？`, '启用确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    enablingId.value = row.id
    try {
      await enablePaymentConfig(row.id)
      await loadAll()
    } finally {
      enablingId.value = null
    }
  }

  const handleCurrentChange = (current: number) => {
    pagination.current = current
    loadConfigs()
  }

  const handleSizeChange = (size: number) => {
    pagination.size = size
    pagination.current = 1
    loadConfigs()
  }

  onMounted(loadAll)
</script>

<style scoped lang="scss">
  .payment-config {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .section-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
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
  .table-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
  }

  .payment-config__table {
    flex: 1;
  }

  .config-name-cell,
  .file-id-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }

  .config-name-cell {
    .title {
      line-height: 20px;
      color: var(--el-text-color-primary);
    }

    .subtitle {
      font-size: 12px;
      line-height: 18px;
      color: var(--el-text-color-secondary);
    }
  }

  .file-id-cell {
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-regular);
  }

  .text-ellipsis {
    display: inline-block;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    vertical-align: bottom;
    white-space: nowrap;
  }

  .table-pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .drawer-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    width: 100%;
  }

  @media (max-width: 768px) {
    .section-header {
      flex-direction: column;
    }

    .section-header__actions {
      width: 100%;
      justify-content: flex-end;
    }
  }
</style>
