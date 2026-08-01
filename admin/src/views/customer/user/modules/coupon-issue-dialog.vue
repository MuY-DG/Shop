<template>
  <ElDialog
    :model-value="props.visible"
    :title="`发送优惠券${props.customer ? ` · ${props.customer.nickname}` : ''}`"
    width="680px"
    align-center
    destroy-on-close
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-if="props.customer" class="customer-summary">
      <div>
        <div class="customer-summary__name">{{ props.customer.nickname }}</div>
        <div class="customer-summary__id">ID {{ props.customer.id }}</div>
      </div>
      <ElTag type="success" effect="plain">
        当前可用券 {{ props.customer.couponAvailableCount }} 张
      </ElTag>
    </div>

    <div class="issue-mode">
      <ElSegmented v-model="issueMode" :options="issueModeOptions" block />
      <div class="issue-mode__tip">
        {{
          issueMode === 'DIRECT'
            ? '现场创建一张仅属于当前用户的专属券，不占用营销优惠券库存。'
            : '从营销管理中现有的公开优惠券模板发放，并占用模板库存。'
        }}
      </div>
    </div>

    <ElForm
      v-if="issueMode === 'DIRECT'"
      ref="formRef"
      :model="form"
      :rules="directRules"
      label-width="104px"
    >
      <ElRow :gutter="16">
        <ElCol :xs="24" :md="12">
          <ElFormItem label="优惠券名称" prop="name">
            <ElInput v-model="form.name" maxlength="80" placeholder="例如：专属补偿券" />
          </ElFormItem>
        </ElCol>
        <ElCol :xs="24" :md="12">
          <ElFormItem label="优惠券类型" prop="couponType">
            <ElSegmented v-model="form.couponType" :options="couponTypeOptions" block />
          </ElFormItem>
        </ElCol>
        <ElCol :xs="24">
          <ElFormItem label="描述" prop="description">
            <ElInput
              v-model="form.description"
              type="textarea"
              :rows="2"
              maxlength="120"
              show-word-limit
              placeholder="选填，用户可看到的优惠券说明"
            />
          </ElFormItem>
        </ElCol>
        <ElCol :xs="24" :md="12">
          <ElFormItem label="门槛金额(元)" prop="thresholdYuan">
            <ElInputNumber
              v-model="form.thresholdYuan"
              :min="0"
              :step="1"
              :precision="2"
              :disabled="form.couponType === 'NO_THRESHOLD'"
              controls-position="right"
              style="width: 100%"
            />
          </ElFormItem>
        </ElCol>
        <ElCol :xs="24" :md="12">
          <ElFormItem label="优惠金额(元)" prop="discountYuan">
            <ElInputNumber
              v-model="form.discountYuan"
              :min="0.01"
              :step="1"
              :precision="2"
              controls-position="right"
              style="width: 100%"
            />
          </ElFormItem>
        </ElCol>
        <ElCol :xs="24">
          <ElFormItem label="有效期" prop="validRange">
            <ElDatePicker
              v-model="form.validRange"
              type="datetimerange"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              style="width: 100%"
            />
          </ElFormItem>
        </ElCol>
        <ElCol :xs="24">
          <ElFormItem label="创建备注" prop="note">
            <ElInput
              v-model="form.note"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="选填，例如：订单延迟专属补偿"
            />
          </ElFormItem>
        </ElCol>
      </ElRow>
    </ElForm>

    <ElForm v-else ref="formRef" :model="form" :rules="existingRules" label-width="88px">
      <ElFormItem label="优惠券" prop="templateId">
        <div class="coupon-selector">
          <ElSelect
            v-model="form.templateId"
            filterable
            :loading="loadingTemplates"
            placeholder="请选择要发送的优惠券"
            style="width: 100%"
          >
            <ElOption
              v-for="template in templates"
              :key="template.id"
              :label="couponOptionLabel(template)"
              :value="template.id"
            />
          </ElSelect>
          <div v-if="!loadingTemplates && templates.length === 0" class="field-tip">
            该用户目前没有可发放的优惠券模板。请检查模板状态、有效期、库存和每人限领。
          </div>
        </div>
      </ElFormItem>

      <div v-if="selectedTemplate" class="coupon-detail">
        <div class="coupon-detail__header">
          <div>
            <div class="coupon-detail__title">{{ selectedTemplate.name }}</div>
            <div class="coupon-detail__description">
              {{ selectedTemplate.description || '暂无描述' }}
            </div>
          </div>
          <strong>{{ formatDiscount(selectedTemplate) }}</strong>
        </div>
        <div class="coupon-detail__grid">
          <span>使用门槛</span><b>{{ formatThreshold(selectedTemplate) }}</b> <span>适用范围</span
          ><b>{{ formatScope(selectedTemplate) }}</b> <span>剩余库存</span
          ><b>{{ selectedTemplate.stockRemaining }} 张</b>
          <span>用户领取</span>
          <b>{{ selectedTemplate.userClaimCount }}/{{ selectedTemplate.perUserLimit }} 张</b>
          <span>有效期</span>
          <b class="coupon-detail__validity">
            {{ formatDateTime(selectedTemplate.validStartAt) }} 至
            {{ formatDateTime(selectedTemplate.validEndAt) }}
          </b>
        </div>
      </div>

      <ElFormItem label="发放备注" prop="note">
        <ElInput
          v-model="form.note"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="选填，例如：订单延迟补偿"
        />
      </ElFormItem>
    </ElForm>

    <ElAlert
      :title="
        issueMode === 'DIRECT'
          ? '创建后会立即进入该用户券包，其他用户不可领取；成功后不能从本页面撤回。'
          : '发放会占用模板库存，并计入每人限领；成功后不能从本页面撤回。'
      "
      type="warning"
      :closable="false"
      show-icon
    />

    <template #footer>
      <ElButton @click="emit('update:visible', false)">取消</ElButton>
      <ElButton
        type="primary"
        :loading="submitting"
        :disabled="issueMode === 'EXISTING' && templates.length === 0"
        @click="handleSubmit"
      >
        {{ issueMode === 'DIRECT' ? '创建并发送' : '确认发送' }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { computed, nextTick, reactive, ref, watch } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage } from 'element-plus'
  import { formatLocalDateTime as formatDateTime, toOffsetDateTime } from '@/utils/date-time'
  import {
    createDirectCustomerCoupon,
    fetchIssuableCouponTemplates,
    issueCustomerCoupon
  } from '@/api/customer'

  type IssueMode = 'DIRECT' | 'EXISTING'

  interface Props {
    visible: boolean
    customer: Api.Customer.CustomerListItem | null
  }

  interface CouponIssueFormState {
    name: string
    description: string
    couponType: Api.Marketing.CouponType
    thresholdYuan: number
    discountYuan: number
    validRange: [string, string] | []
    templateId?: number
    note: string
  }

  const props = defineProps<Props>()
  const emit = defineEmits<{
    (event: 'update:visible', value: boolean): void
    (event: 'success'): void
  }>()

  const issueModeOptions = [
    { label: '创建专属优惠券', value: 'DIRECT' },
    { label: '发送已有优惠券', value: 'EXISTING' }
  ]
  const couponTypeOptions = [
    { label: '无门槛券', value: 'NO_THRESHOLD' },
    { label: '满减券', value: 'MIN_SPEND' }
  ]

  const defaultForm = (): CouponIssueFormState => {
    const validStartAt = new Date()
    const validEndAt = new Date(validStartAt.getTime() + 7 * 24 * 60 * 60 * 1000)
    return {
      name: '',
      description: '',
      couponType: 'NO_THRESHOLD',
      thresholdYuan: 0,
      discountYuan: 0.01,
      validRange: [toOffsetDateTime(validStartAt), toOffsetDateTime(validEndAt)],
      templateId: undefined,
      note: ''
    }
  }

  const issueMode = ref<IssueMode>('DIRECT')
  const formRef = ref<FormInstance>()
  const loadingTemplates = ref(false)
  const templatesLoaded = ref(false)
  const submitting = ref(false)
  const templates = ref<Api.Customer.IssuableCouponTemplate[]>([])
  const form = reactive<CouponIssueFormState>(defaultForm())

  const directRules: FormRules<CouponIssueFormState> = {
    name: [
      { required: true, message: '请输入优惠券名称', trigger: 'blur' },
      { max: 80, message: '名称长度不能超过 80 个字符', trigger: 'blur' }
    ],
    description: [{ max: 120, message: '描述长度不能超过 120 个字符', trigger: 'blur' }],
    couponType: [{ required: true, message: '请选择优惠券类型', trigger: 'change' }],
    thresholdYuan: [
      {
        validator: (_rule, value: number, callback) => {
          if (form.couponType === 'MIN_SPEND' && value <= 0) {
            callback(new Error('满减券的门槛金额必须大于 0'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    discountYuan: [
      {
        validator: (_rule, value: number, callback) => {
          if (value <= 0) {
            callback(new Error('优惠金额必须大于 0'))
            return
          }
          if (form.couponType === 'MIN_SPEND' && value >= form.thresholdYuan) {
            callback(new Error('优惠金额必须小于门槛金额'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    validRange: [
      {
        validator: (_rule, value: [string, string] | [], callback) => {
          if (value.length !== 2 || !value[0] || !value[1]) {
            callback(new Error('请选择有效期'))
            return
          }
          if (new Date(value[0]).getTime() >= new Date(value[1]).getTime()) {
            callback(new Error('结束时间必须晚于开始时间'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    note: [{ max: 200, message: '备注长度不能超过 200 个字符', trigger: 'blur' }]
  }

  const existingRules: FormRules<CouponIssueFormState> = {
    templateId: [{ required: true, message: '请选择要发送的优惠券', trigger: 'change' }],
    note: [{ max: 200, message: '备注长度不能超过 200 个字符', trigger: 'blur' }]
  }

  const selectedTemplate = computed(() =>
    templates.value.find((template) => template.id === form.templateId)
  )

  const formatMoney = (cent: number) => `¥${(cent / 100).toFixed(2)}`

  const formatDiscount = (template: Api.Customer.IssuableCouponTemplate) => {
    if (template.discountType === 'PERCENT_OFF') {
      return `${(template.discountCent / 100).toFixed(2)} 折`
    }
    return `减 ${formatMoney(template.discountCent)}`
  }

  const formatThreshold = (template: Api.Customer.IssuableCouponTemplate) =>
    template.couponType === 'NO_THRESHOLD' ? '无门槛' : `满 ${formatMoney(template.thresholdCent)}`

  const formatScope = (template: Api.Customer.IssuableCouponTemplate) =>
    template.scopeType === 'PRODUCT' ? `指定商品 #${template.scopeValue}` : '全场通用'

  const couponOptionLabel = (template: Api.Customer.IssuableCouponTemplate) =>
    `${template.name} · ${formatDiscount(template)} · 库存 ${template.stockRemaining}`

  const reset = () => {
    issueMode.value = 'DIRECT'
    Object.assign(form, defaultForm())
    templates.value = []
    templatesLoaded.value = false
    nextTick(() => formRef.value?.clearValidate())
  }

  const loadTemplates = async () => {
    if (!props.customer || templatesLoaded.value || loadingTemplates.value) return
    loadingTemplates.value = true
    try {
      templates.value = await fetchIssuableCouponTemplates(props.customer.id)
      templatesLoaded.value = true
    } finally {
      loadingTemplates.value = false
    }
  }

  watch(
    () => props.visible,
    (visible) => {
      if (visible) reset()
    }
  )

  watch(issueMode, async (mode) => {
    form.templateId = undefined
    await nextTick()
    formRef.value?.clearValidate()
    if (mode === 'EXISTING') await loadTemplates()
  })

  watch(
    () => form.couponType,
    (couponType) => {
      if (couponType === 'NO_THRESHOLD') form.thresholdYuan = 0
    }
  )

  const handleSubmit = async () => {
    if (!formRef.value || !props.customer) return
    await formRef.value.validate()

    submitting.value = true
    try {
      if (issueMode.value === 'DIRECT') {
        const [validStartAt, validEndAt] = form.validRange
        if (!validStartAt || !validEndAt) return
        await createDirectCustomerCoupon(props.customer.id, {
          name: form.name.trim(),
          description: form.description.trim() || undefined,
          couponType: form.couponType,
          thresholdCent:
            form.couponType === 'NO_THRESHOLD' ? 0 : Math.round(form.thresholdYuan * 100),
          discountCent: Math.round(form.discountYuan * 100),
          validStartAt,
          validEndAt,
          note: form.note.trim() || undefined
        })
        ElMessage.success(`已为“${props.customer.nickname}”创建并发送专属优惠券`)
      } else {
        if (!form.templateId) return
        await issueCustomerCoupon(props.customer.id, {
          templateId: form.templateId,
          note: form.note.trim() || undefined
        })
        ElMessage.success(`已向“${props.customer.nickname}”发送优惠券`)
      }
      emit('update:visible', false)
      emit('success')
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped lang="scss">
  .customer-summary {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    margin-bottom: 16px;
    background: var(--el-fill-color-light);
    border-radius: 8px;

    &__name {
      font-size: 16px;
      font-weight: 600;
      color: var(--el-text-color-primary);
    }

    &__id {
      margin-top: 4px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .issue-mode {
    margin-bottom: 20px;

    &__tip {
      margin-top: 8px;
      font-size: 12px;
      line-height: 1.5;
      color: var(--el-text-color-secondary);
    }
  }

  .coupon-selector {
    width: 100%;
  }

  .field-tip {
    margin-top: 6px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }

  .coupon-detail {
    padding: 14px 16px;
    margin: -4px 0 18px 88px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;

    &__header {
      display: flex;
      gap: 16px;
      align-items: flex-start;
      justify-content: space-between;

      strong {
        font-size: 18px;
        color: var(--el-color-danger);
        white-space: nowrap;
      }
    }

    &__title {
      font-weight: 600;
      color: var(--el-text-color-primary);
    }

    &__description {
      margin-top: 4px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    &__grid {
      display: grid;
      grid-template-columns: 72px 1fr 72px 1fr;
      gap: 8px 12px;
      margin-top: 14px;
      font-size: 13px;

      span {
        color: var(--el-text-color-secondary);
      }

      b {
        font-weight: 500;
        color: var(--el-text-color-regular);
      }
    }

    &__validity {
      grid-column: span 3;
    }
  }

  @media (width <= 700px) {
    .customer-summary {
      flex-direction: column;
      gap: 10px;
      align-items: flex-start;
    }

    .coupon-detail {
      margin-left: 0;

      &__grid {
        grid-template-columns: 72px 1fr;
      }

      &__validity {
        grid-column: auto;
      }
    }
  }
</style>
