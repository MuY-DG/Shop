<template>
  <ElDrawer
    :model-value="visible"
    :title="drawerTitle"
    size="720px"
    destroy-on-close
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="coupon-template-dialog">
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="112px">
        <ElRow :gutter="16">
          <ElCol :xs="24" :md="12">
            <ElFormItem label="优惠券名称" prop="name">
              <ElInput v-model="formData.name" maxlength="40" placeholder="请输入优惠券名称" />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="状态" prop="status">
              <ElSelect v-model="formData.status" placeholder="请选择状态">
                <ElOption label="启用" value="ENABLED" />
                <ElOption label="禁用" value="DISABLED" />
              </ElSelect>
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24">
            <ElFormItem label="描述" prop="description">
              <ElInput
                v-model="formData.description"
                type="textarea"
                :rows="3"
                maxlength="120"
                show-word-limit
                placeholder="请输入优惠券描述"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="优惠券类型" prop="couponType">
              <ElSegmented v-model="formData.couponType" :options="couponTypeOptions" block />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="适用范围">
              <ElInput value="全场券" disabled />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="门槛金额(元)" prop="thresholdYuan">
              <ElInputNumber
                v-model="formData.thresholdYuan"
                :min="0"
                :step="1"
                :precision="2"
                :disabled="formData.couponType === 'NO_THRESHOLD'"
                controls-position="right"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="优惠金额(元)" prop="discountYuan">
              <ElInputNumber
                v-model="formData.discountYuan"
                :min="0.01"
                :step="1"
                :precision="2"
                controls-position="right"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="发放总量" prop="totalStock">
              <ElInputNumber
                v-model="formData.totalStock"
                :min="1"
                :step="1"
                :precision="0"
                controls-position="right"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="每人限领" prop="perUserLimit">
              <ElInputNumber
                v-model="formData.perUserLimit"
                :min="1"
                :step="1"
                :precision="0"
                controls-position="right"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24">
            <ElFormItem label="有效期" prop="validRange">
              <ElDatePicker
                v-model="formData.validRange"
                type="datetimerange"
                value-format="YYYY-MM-DDTHH:mm:ss"
                range-separator="至"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="排序" prop="sortOrder">
              <ElInputNumber
                v-model="formData.sortOrder"
                :min="0"
                :step="1"
                :precision="0"
                controls-position="right"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
        </ElRow>
      </ElForm>
    </div>

    <template #footer>
      <div class="drawer-footer">
        <ElButton @click="emit('update:visible', false)">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
      </div>
    </template>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { computed, nextTick, reactive, ref, watch } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { createCouponTemplate, updateCouponTemplate } from '@/api/coupon'

  interface Props {
    visible: boolean
    template?: Api.Marketing.CouponTemplate | null
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'success'): void
  }

  interface CouponTemplateFormState {
    name: string
    description: string
    couponType: Api.Marketing.CouponType
    thresholdYuan: number
    discountYuan: number
    totalStock: number
    perUserLimit: number
    validRange: [string, string] | []
    status: Api.Marketing.CouponTemplateStatus
    sortOrder: number
  }

  const props = withDefaults(defineProps<Props>(), {
    visible: false,
    template: null
  })

  const emit = defineEmits<Emits>()

  const formRef = ref<FormInstance>()
  const submitting = ref(false)

  const couponTypeOptions = [
    { label: '无门槛券', value: 'NO_THRESHOLD' },
    { label: '满减券', value: 'MIN_SPEND' }
  ]

  const createDefaultForm = (): CouponTemplateFormState => ({
    name: '',
    description: '',
    couponType: 'NO_THRESHOLD',
    thresholdYuan: 0,
    discountYuan: 0.01,
    totalStock: 1,
    perUserLimit: 1,
    validRange: [],
    status: 'DISABLED',
    sortOrder: 0
  })

  const formData = reactive<CouponTemplateFormState>(createDefaultForm())

  const drawerTitle = computed(() => (props.template?.id ? '编辑优惠券' : '新增优惠券'))

  const toCent = (value: number) => Math.round(value * 100)

  const rules: FormRules<CouponTemplateFormState> = {
    name: [{ required: true, message: '请输入优惠券名称', trigger: 'blur' }],
    description: [{ required: true, message: '请输入优惠券描述', trigger: 'blur' }],
    couponType: [{ required: true, message: '请选择优惠券类型', trigger: 'change' }],
    thresholdYuan: [
      {
        validator: (_rule, value: number, callback) => {
          if (formData.couponType === 'NO_THRESHOLD') {
            callback()
            return
          }
          if (!Number.isFinite(value) || value <= 0) {
            callback(new Error('满减券门槛金额需大于 0'))
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
          if (!Number.isFinite(value) || value <= 0) {
            callback(new Error('请输入大于 0 的优惠金额'))
            return
          }
          if (
            formData.couponType === 'MIN_SPEND' &&
            Number.isFinite(formData.thresholdYuan) &&
            toCent(value) >= toCent(formData.thresholdYuan)
          ) {
            callback(new Error('满减券优惠金额必须小于门槛金额'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    totalStock: [{ required: true, message: '请输入发放总量', trigger: 'change' }],
    perUserLimit: [{ required: true, message: '请输入每人限领', trigger: 'change' }],
    validRange: [
      {
        validator: (_rule, value: CouponTemplateFormState['validRange'], callback) => {
          if (!Array.isArray(value) || value.length !== 2 || !value[0] || !value[1]) {
            callback(new Error('请选择有效期'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ]
  }

  const resetFormData = () => {
    Object.assign(formData, createDefaultForm())
  }

  const fillForm = (template?: Api.Marketing.CouponTemplate | null) => {
    resetFormData()
    if (!template) return

    Object.assign(formData, {
      name: template.name,
      description: template.description,
      couponType: template.couponType,
      thresholdYuan: template.thresholdCent / 100,
      discountYuan: template.discountCent / 100,
      totalStock: template.totalStock,
      perUserLimit: template.perUserLimit,
      validRange: [template.validStartAt, template.validEndAt],
      status: template.status,
      sortOrder: template.sortOrder
    })
  }

  watch(
    () => props.visible,
    (visible) => {
      if (!visible) return
      fillForm(props.template)
      nextTick(() => formRef.value?.clearValidate())
    },
    { immediate: true }
  )

  watch(
    () => formData.couponType,
    (couponType) => {
      if (couponType === 'NO_THRESHOLD') {
        formData.thresholdYuan = 0
      }
    }
  )

  const buildPayload = (): Api.Marketing.CouponTemplateForm => {
    const [validStartAt = '', validEndAt = ''] = formData.validRange
    return {
      name: formData.name.trim(),
      description: formData.description.trim(),
      couponType: formData.couponType,
      discountType: 'AMOUNT_OFF',
      thresholdCent: formData.couponType === 'NO_THRESHOLD' ? 0 : toCent(formData.thresholdYuan),
      discountCent: toCent(formData.discountYuan),
      scopeType: 'ALL',
      scopeValue: '',
      strategyKey: '',
      totalStock: formData.totalStock,
      perUserLimit: formData.perUserLimit,
      validStartAt,
      validEndAt,
      status: formData.status,
      sortOrder: formData.sortOrder
    }
  }

  const handleSubmit = async () => {
    if (!formRef.value) return

    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)

    if (!valid) return

    const payload = buildPayload()
    submitting.value = true

    try {
      if (props.template?.id) {
        await updateCouponTemplate(props.template.id, payload)
      } else {
        await createCouponTemplate(payload)
      }
      emit('update:visible', false)
      emit('success')
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped lang="scss">
  .coupon-template-dialog {
    padding-right: 8px;
  }

  .drawer-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
  }
</style>
