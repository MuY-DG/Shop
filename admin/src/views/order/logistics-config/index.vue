<template>
  <div class="wechat-express-config-page">
    <WechatShippingRuntimeCard />

    <ElAlert
      title="电子面单与手动发货是两条独立链路"
      description="生成电子面单不会立即发货；只有订单页明确确认发货后，订单才会进入待收货。配置中不会保存快递账号密码。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElAlert
      v-if="!canRead"
      title="当前账号没有查看电子面单配置的权限"
      description="请联系管理员授予“查看电子面单配置”权限。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard v-else shadow="never" class="config-card">
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-header__title">电子面单配置</div>
            <div class="page-header__subtitle">
              先在微信沙箱跑通生成、预览、打印和轨迹模拟，绑定正式快递账号后只需切换配置
            </div>
          </div>
          <div class="page-header__actions">
            <ElButton :disabled="loading || saving" @click="handleReload">重新加载</ElButton>
            <ElButton :disabled="!dirty || loading || saving" @click="resetUnsavedChanges">
              撤销未保存修改
            </ElButton>
            <ElButton
              v-auth="'logistics:express:config:write'"
              type="primary"
              :loading="saving"
              :disabled="!dirty || loading"
              @click="handleSave"
            >
              保存配置
            </ElButton>
          </div>
        </div>
      </template>

      <ElAlert
        v-if="revisionConflict"
        title="配置已被其他管理员更新"
        description="当前编辑内容仍保留。请点击“重新加载”取得最新 revision，核对后再保存。"
        type="warning"
        :closable="false"
        show-icon
        class="revision-alert"
      />

      <ElForm v-loading="loading" :model="formData" label-position="top" class="config-form">
        <section class="config-section config-section--mode">
          <div class="section-heading">
            <div>
              <h2>运行模式</h2>
              <p>沙箱会强制使用微信官方测试运力和测试客户编码，前端填写值不能覆盖。</p>
            </div>
            <ElTag :type="modeTagType" effect="plain">{{ modeLabel }}</ElTag>
          </div>

          <ElFormItem label="电子面单模式" required>
            <ElSegmented
              v-model="formData.mode"
              :options="modeOptions"
              :disabled="interactionDisabled"
              block
            />
          </ElFormItem>

          <ElAlert
            v-if="formData.mode === 'SANDBOX'"
            title="微信官方沙箱：每天最多生成 10 单"
            description="下单用户的 OpenID 必须属于该小程序的管理员、运营者或开发者。实际调用固定使用 TEST / test_biz_id / 1 / test_service_name。"
            type="warning"
            :closable="false"
            show-icon
          />
          <ElAlert
            v-else-if="formData.mode === 'PRODUCTION'"
            title="正式环境不会保存月结账号密码"
            description="请先在微信小程序后台绑定快递账号，再填写快递公司、客户编码和服务类型标识。"
            type="warning"
            :closable="false"
            show-icon
          />
          <ElAlert
            v-else
            title="电子面单当前停用"
            description="手动填写快递公司和运单号的发货流程不受影响；可先完善下方配置，再切换到沙箱或正式环境。"
            type="info"
            :closable="false"
            show-icon
          />

          <div class="switch-row">
            <div>
              <div class="switch-row__title">手动发货物流消息</div>
              <div class="field-tip">
                开启后，手动填写的实体快递会尝试通过微信物流消息能力注册；失败不影响本地物流卡片。
              </div>
            </div>
            <ElSwitch
              v-model="formData.messageEnabled"
              :disabled="interactionDisabled"
              inline-prompt
              active-text="开"
              inactive-text="关"
              aria-label="手动发货物流消息开关"
            />
          </div>
        </section>

        <section class="config-section">
          <div class="section-heading">
            <div>
              <h2>寄件人信息</h2>
              <p>沙箱和正式电子面单都会使用这份结构化寄件快照。</p>
            </div>
          </div>

          <div class="field-grid">
            <ElFormItem label="寄件人姓名" :required="modeEnabled">
              <ElInput
                v-model="formData.sender.name"
                maxlength="64"
                :disabled="interactionDisabled"
                placeholder="请输入寄件人姓名"
              />
            </ElFormItem>
            <ElFormItem label="手机号码" :required="modeEnabled">
              <ElInput
                v-model="formData.sender.mobile"
                maxlength="32"
                :disabled="interactionDisabled"
                placeholder="请输入寄件人手机"
              />
            </ElFormItem>
            <ElFormItem label="公司名称">
              <ElInput
                v-model="formData.sender.company"
                maxlength="64"
                :disabled="interactionDisabled"
                placeholder="选填"
              />
            </ElFormItem>
            <ElFormItem label="省份" :required="modeEnabled">
              <ElInput
                v-model="formData.sender.province"
                maxlength="64"
                :disabled="interactionDisabled"
                placeholder="例如：广东省"
              />
            </ElFormItem>
            <ElFormItem label="城市" :required="modeEnabled">
              <ElInput
                v-model="formData.sender.city"
                maxlength="64"
                :disabled="interactionDisabled"
                placeholder="例如：深圳市"
              />
            </ElFormItem>
            <ElFormItem label="区县" :required="modeEnabled">
              <ElInput
                v-model="formData.sender.district"
                maxlength="64"
                :disabled="interactionDisabled"
                placeholder="例如：南山区"
              />
            </ElFormItem>
            <ElFormItem label="详细地址" :required="modeEnabled" class="field-grid__wide">
              <ElInput
                v-model="formData.sender.detailAddress"
                maxlength="512"
                :disabled="interactionDisabled"
                placeholder="街道、门牌号和楼层房间"
              />
            </ElFormItem>
          </div>
        </section>

        <section class="config-section">
          <div class="section-heading">
            <div>
              <h2>正式快递账号标识</h2>
              <p>沙箱模式下这些字段不会参与任何调用；切回停用或正式模式后可编辑。</p>
            </div>
            <ElTag v-if="formData.mode === 'SANDBOX'" type="info" effect="plain">
              沙箱中已停用
            </ElTag>
          </div>

          <div class="field-grid">
            <ElFormItem label="快递公司 ID" :required="productionRequired">
              <ElInput
                v-model="formData.production.deliveryId"
                maxlength="128"
                :disabled="productionFormDisabled"
                placeholder="例如：SF"
              />
            </ElFormItem>
            <ElFormItem label="快递公司名称" :required="productionRequired">
              <ElInput
                v-model="formData.production.deliveryName"
                maxlength="128"
                :disabled="productionFormDisabled"
                placeholder="例如：顺丰速运"
              />
            </ElFormItem>
            <ElFormItem label="客户编码 / 现付编码" :required="productionRequired">
              <ElInput
                v-model="formData.production.bizId"
                maxlength="128"
                :disabled="productionFormDisabled || formData.production.clearBizId"
                :placeholder="productionBizIdPlaceholder"
              />
              <div class="field-tip">留空表示保留后端现有值；本页不会要求快递账号密码。</div>
            </ElFormItem>
            <ElFormItem label="服务类型 ID" :required="productionRequired">
              <ElInputNumber
                v-model="formData.production.serviceType"
                :min="0"
                :precision="0"
                controls-position="right"
                :disabled="productionFormDisabled"
              />
              <div class="field-tip">填写快递服务类型整数；按快递公司配置可从 0 开始。</div>
            </ElFormItem>
            <ElFormItem label="服务名称" :required="productionRequired">
              <ElInput
                v-model="formData.production.serviceName"
                maxlength="128"
                :disabled="productionFormDisabled"
                placeholder="请输入微信文档中的服务名称"
              />
            </ElFormItem>
            <ElFormItem v-if="formData.production.bizIdMasked" label="已有客户编码">
              <div class="masked-value">
                <span>{{ formData.production.bizIdMasked }}</span>
                <ElCheckbox
                  v-model="formData.production.clearBizId"
                  :disabled="productionFormDisabled"
                >
                  清除已保存值
                </ElCheckbox>
              </div>
            </ElFormItem>
          </div>
        </section>

        <section class="config-section">
          <div class="section-heading">
            <div>
              <h2>默认包裹</h2>
              <p>生成面单时会带入订单弹窗，运营人员仍可按实际包裹调整。</p>
            </div>
          </div>

          <div class="parcel-grid">
            <ElFormItem label="包裹数量" :required="modeEnabled">
              <ElInputNumber
                v-model="formData.defaultParcel.count"
                :min="1"
                :precision="0"
                controls-position="right"
                :disabled="interactionDisabled"
              />
            </ElFormItem>
            <ElFormItem label="重量（kg）" :required="modeEnabled">
              <ElInputNumber
                v-model="formData.defaultParcel.weightKg"
                :min="0.01"
                :precision="2"
                controls-position="right"
                :disabled="interactionDisabled"
              />
            </ElFormItem>
            <ElFormItem label="长度（cm）" :required="modeEnabled">
              <ElInputNumber
                v-model="formData.defaultParcel.lengthCm"
                :min="0.1"
                :precision="1"
                controls-position="right"
                :disabled="interactionDisabled"
              />
            </ElFormItem>
            <ElFormItem label="宽度（cm）" :required="modeEnabled">
              <ElInputNumber
                v-model="formData.defaultParcel.widthCm"
                :min="0.1"
                :precision="1"
                controls-position="right"
                :disabled="interactionDisabled"
              />
            </ElFormItem>
            <ElFormItem label="高度（cm）" :required="modeEnabled">
              <ElInputNumber
                v-model="formData.defaultParcel.heightCm"
                :min="0.1"
                :precision="1"
                controls-position="right"
                :disabled="interactionDisabled"
              />
            </ElFormItem>
          </div>
        </section>

        <section class="config-section config-section--effective">
          <div class="section-heading">
            <div>
              <h2>保存后生效预览</h2>
              <p>这里展示当前表单对应的有效账号标识，最终值仍由后端强制校验。</p>
            </div>
          </div>

          <ElEmpty
            v-if="!effectiveAccount"
            description="停用模式没有有效电子面单账号"
            :image-size="64"
          />
          <dl v-else class="effective-grid">
            <div>
              <dt>环境</dt>
              <dd>{{ effectiveAccount.sandbox ? '微信沙箱' : '正式环境' }}</dd>
            </div>
            <div>
              <dt>快递公司</dt>
              <dd
                >{{ effectiveAccount.deliveryName || '-' }}（{{
                  effectiveAccount.deliveryId || '-'
                }}）</dd
              >
            </div>
            <div>
              <dt>客户编码</dt>
              <dd>{{ effectiveBizIdLabel }}</dd>
            </div>
            <div>
              <dt>服务类型</dt>
              <dd
                >{{ effectiveAccount.serviceName || '-' }}（{{
                  effectiveAccount.serviceType ?? '-'
                }}）</dd
              >
            </div>
          </dl>
          <div v-if="config?.updatedAt" class="updated-at">
            最近保存：{{ formatDateTime(config.updatedAt) }} · revision {{ formData.revision }}
          </div>
        </section>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, nextTick, onMounted, reactive, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { fetchWechatExpressConfig, updateWechatExpressConfig } from '@/api/waybill'
  import { useAuth } from '@/hooks/core/useAuth'
  import { isHttpError } from '@/utils/http/error'
  import { formatLocalDateTime } from '@/utils/date-time'
  import {
    canLoadWechatExpressConfig,
    canSaveWechatExpressConfig,
    createWechatExpressConfigForm,
    isWechatExpressConfigRevisionConflict,
    resolveEffectiveExpressAccount,
    toWechatExpressConfigUpdate,
    validateWechatExpressConfig,
    WECHAT_EXPRESS_MODE_OPTIONS,
    wechatExpressConfigSnapshot
  } from './logistics-config-state'
  import WechatShippingRuntimeCard from './wechat-shipping-runtime-card.vue'

  defineOptions({ name: 'OrderLogisticsConfig' })

  const emptyForm = (): Api.Waybill.WechatExpressConfigForm => ({
    mode: 'DISABLED',
    messageEnabled: true,
    sender: {
      name: '',
      mobile: '',
      company: '',
      province: '',
      city: '',
      district: '',
      detailAddress: ''
    },
    production: {
      deliveryId: '',
      deliveryName: '',
      bizId: '',
      bizIdMasked: '',
      clearBizId: false,
      serviceType: null,
      serviceName: ''
    },
    defaultParcel: {
      count: 1,
      weightKg: 1,
      lengthCm: 20,
      widthCm: 15,
      heightCm: 10
    },
    revision: 0
  })

  const { hasAuth } = useAuth()
  const config = ref<Api.Waybill.WechatExpressConfig | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const revisionConflict = ref(false)
  const baseline = ref('')
  const formData = reactive<Api.Waybill.WechatExpressConfigForm>(emptyForm())
  const modeOptions = [...WECHAT_EXPRESS_MODE_OPTIONS]

  const canRead = computed(() => hasAuth('logistics:express:config:read'))
  const canWrite = computed(() => hasAuth('logistics:express:config:write'))
  const interactionDisabled = computed(() => loading.value || saving.value || !canWrite.value)
  const productionFormDisabled = computed(
    () => interactionDisabled.value || formData.mode === 'SANDBOX'
  )
  const modeEnabled = computed(() => formData.mode !== 'DISABLED')
  const productionRequired = computed(() => formData.mode === 'PRODUCTION')
  const validationErrors = computed(() => validateWechatExpressConfig(formData))
  const formIsValid = computed(() => validationErrors.value.length === 0)
  const dirty = computed(
    () => config.value !== null && wechatExpressConfigSnapshot(formData) !== baseline.value
  )
  const effectiveAccount = computed(() => resolveEffectiveExpressAccount(formData))
  const productionBizIdPlaceholder = computed(() =>
    formData.production.bizIdMasked
      ? `已配置 ${formData.production.bizIdMasked}，留空不修改`
      : '请输入客户编码或现付编码'
  )
  const effectiveBizIdLabel = computed(() => {
    if (!effectiveAccount.value) return '-'
    if (effectiveAccount.value.sandbox) return effectiveAccount.value.bizId
    if (formData.production.clearBizId) return '保存后清除'
    if (formData.production.bizId.trim()) return '已填写新值，保存后脱敏显示'
    return effectiveAccount.value.bizId || '-'
  })
  const modeLabel = computed(
    () => modeOptions.find((option) => option.value === formData.mode)?.label || formData.mode
  )
  const modeTagType = computed<'info' | 'warning' | 'success'>(() => {
    if (formData.mode === 'SANDBOX') return 'warning'
    if (formData.mode === 'PRODUCTION') return 'success'
    return 'info'
  })

  const formatDateTime = (value?: string | null) => formatLocalDateTime(value, 'second')

  const fillForm = (value: Api.Waybill.WechatExpressConfig) => {
    const next = createWechatExpressConfigForm(value)
    config.value = value
    formData.mode = next.mode
    formData.messageEnabled = next.messageEnabled
    formData.revision = next.revision
    Object.assign(formData.sender, next.sender)
    Object.assign(formData.production, next.production)
    Object.assign(formData.defaultParcel, next.defaultParcel)
    baseline.value = wechatExpressConfigSnapshot(formData)
    revisionConflict.value = false
  }

  const loadConfig = async () => {
    if (!canLoadWechatExpressConfig(canRead.value)) return
    loading.value = true
    try {
      fillForm(await fetchWechatExpressConfig())
      await nextTick()
    } finally {
      loading.value = false
    }
  }

  const handleReload = async () => {
    if (!canLoadWechatExpressConfig(canRead.value) || loading.value || saving.value) return
    if (dirty.value) {
      await ElMessageBox.confirm('重新加载会丢弃当前未保存修改，是否继续？', '重新加载配置', {
        type: 'warning',
        confirmButtonText: '重新加载',
        cancelButtonText: '取消'
      })
    }
    await loadConfig()
  }

  const resetUnsavedChanges = () => {
    if (config.value) fillForm(config.value)
  }

  const handleSave = async () => {
    if (!canSaveWechatExpressConfig(canWrite.value, formIsValid.value)) {
      if (validationErrors.value[0]) ElMessage.warning(validationErrors.value[0])
      return
    }
    if (!dirty.value || saving.value) return

    saving.value = true
    try {
      const updated = await updateWechatExpressConfig(toWechatExpressConfigUpdate(formData))
      fillForm(updated)
      ElMessage.success('电子面单配置已保存')
    } catch (error) {
      if (isWechatExpressConfigRevisionConflict(error)) {
        revisionConflict.value = true
        ElMessage.warning('配置已被其他管理员修改，请重新加载后核对再保存')
      } else if (isHttpError(error)) {
        ElMessage.error(error.message)
      } else {
        ElMessage.error('电子面单配置保存失败，请稍后重试')
      }
    } finally {
      saving.value = false
    }
  }

  onMounted(loadConfig)
</script>

<style scoped lang="scss">
  .wechat-express-config-page {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .config-card :deep(.el-card__body) {
    padding: 18px;
  }

  .page-header,
  .section-heading,
  .switch-row {
    display: flex;
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .page-header__title {
    font-size: 16px;
    font-weight: 600;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .page-header__subtitle,
  .section-heading p,
  .field-tip,
  .updated-at {
    margin: 3px 0 0;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .page-header__actions {
    display: flex;
    flex-shrink: 0;
    gap: 8px;
  }

  .revision-alert {
    margin-bottom: 16px;
  }

  .config-form {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  .config-section {
    min-width: 0;
    padding: 18px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .config-section--mode,
  .config-section--effective {
    grid-column: 1 / -1;
  }

  .section-heading {
    margin-bottom: 16px;
  }

  .section-heading h2 {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .switch-row {
    align-items: center;
    padding: 14px;
    margin-top: 16px;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  .switch-row__title {
    font-size: 14px;
    font-weight: 500;
    color: var(--el-text-color-primary);
  }

  .field-grid,
  .parcel-grid,
  .effective-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 16px;
  }

  .field-grid :deep(.el-form-item),
  .parcel-grid :deep(.el-form-item) {
    min-width: 0;
    margin-bottom: 18px;
  }

  .field-grid__wide {
    grid-column: 1 / -1;
  }

  .parcel-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .parcel-grid :deep(.el-input-number),
  .field-grid :deep(.el-input-number) {
    width: 100%;
  }

  .masked-value {
    display: flex;
    gap: 14px;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    min-height: 32px;
    padding: 0 10px;
    background: var(--el-fill-color-light);
    border-radius: 4px;
  }

  .effective-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
    margin: 0;
  }

  .effective-grid div {
    min-width: 0;
    padding: 12px 14px;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  .effective-grid dt {
    margin-bottom: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .effective-grid dd {
    margin: 0;
    font-size: 13px;
    color: var(--el-text-color-primary);
    overflow-wrap: anywhere;
  }

  .updated-at {
    margin-top: 12px;
    text-align: right;
  }

  @media (width <= 1100px) {
    .config-form {
      grid-template-columns: 1fr;
    }

    .config-section--mode,
    .config-section--effective {
      grid-column: auto;
    }

    .effective-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (width <= 720px) {
    .page-header,
    .section-heading {
      flex-direction: column;
    }

    .page-header__actions {
      flex-wrap: wrap;
      width: 100%;
    }

    .field-grid,
    .parcel-grid,
    .effective-grid {
      grid-template-columns: 1fr;
    }

    .field-grid__wide {
      grid-column: auto;
    }
  }
</style>
