<template>
  <div class="other-settings-tab">
    <ElForm label-position="left" label-width="120px" class="settings-form">
      <ElFormItem>
        <template #label>
          <span class="settings-label">
            排序
            <ElTooltip content="数值越小越靠前，默认 0" placement="top">
              <ElIcon><WarningFilled /></ElIcon>
            </ElTooltip>
          </span>
        </template>
        <ElInputNumber
          :model-value="modelValue.sortOrder"
          :min="0"
          :precision="0"
          :disabled="disabled"
          controls-position="right"
          class="settings-number"
          @update:model-value="patchForm({ sortOrder: $event ?? 0 })"
        />
      </ElFormItem>

      <ElFormItem>
        <template #label>
          <span class="settings-label">
            虚拟销量
            <ElTooltip content="展示销量 = 实际支付销量 + 虚拟销量" placement="top">
              <ElIcon><WarningFilled /></ElIcon>
            </ElTooltip>
          </span>
        </template>
        <ElInputNumber
          :model-value="modelValue.virtualSales"
          :min="0"
          :precision="0"
          :disabled="disabled"
          controls-position="right"
          class="settings-number"
          @update:model-value="patchForm({ virtualSales: $event ?? 0 })"
        />
      </ElFormItem>

      <ElFormItem label="商品标签">
        <ElCheckboxGroup
          :model-value="modelValue.tags"
          :disabled="disabled"
          class="tag-options"
          @update:model-value="updateTags"
        >
          <ElCheckboxButton
            v-for="option in PRODUCT_TAG_OPTIONS"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </ElCheckboxButton>
        </ElCheckboxGroup>
      </ElFormItem>

      <ElFormItem label="优惠券">
        <div class="coupon-field">
          <div class="coupon-field__toolbar">
            <ElSelect
              :model-value="modelValue.couponTemplateIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              placeholder="请选择优惠券"
              :loading="couponLoading"
              :disabled="disabled || !canBindCoupons"
              @update:model-value="patchForm({ couponTemplateIds: $event })"
            >
              <ElOption
                v-for="coupon in coupons"
                :key="coupon.id"
                :value="coupon.id"
                :label="coupon.name"
              >
                <div class="coupon-option">
                  <span>{{ coupon.name }}</span>
                  <ElTag size="small" :type="coupon.scopeType === 'PRODUCT' ? 'warning' : 'info'">
                    {{ coupon.scopeType === 'PRODUCT' ? '本商品专属' : '全场券' }}
                  </ElTag>
                </div>
              </ElOption>
            </ElSelect>
            <ElButton :loading="couponLoading" :disabled="disabled" @click="loadCoupons">
              刷新
            </ElButton>
            <ElButton
              v-auth="'product:coupon:create'"
              type="primary"
              plain
              :disabled="disabled || !spuId"
              @click="openCouponDialog"
            >
              创建专属商品券
            </ElButton>
          </div>
          <div v-if="!spuId" class="coupon-field__hint">首次保存商品后可创建专属商品券</div>
          <div v-else-if="!canBindCoupons" class="coupon-field__hint">
            当前账号仅可查看已绑定优惠券
          </div>
        </div>
      </ElFormItem>
    </ElForm>

    <ElDialog v-model="couponDialogVisible" title="创建专属商品优惠券" width="680px" align-center>
      <ElForm label-position="top">
        <ElFormItem label="优惠券名称" required>
          <ElInput v-model="couponForm.name" maxlength="80" show-word-limit />
        </ElFormItem>
        <ElFormItem label="优惠券描述">
          <ElInput
            v-model="couponForm.description"
            type="textarea"
            :rows="3"
            maxlength="300"
            show-word-limit
          />
        </ElFormItem>
        <div class="dialog-grid">
          <ElFormItem label="使用门槛" required>
            <ElSelect v-model="couponForm.couponType" style="width: 100%">
              <ElOption label="无门槛" value="NO_THRESHOLD" />
              <ElOption label="满额可用" value="MIN_SPEND" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="优惠方式" required>
            <ElSelect v-model="couponForm.discountType" style="width: 100%">
              <ElOption label="金额优惠" value="AMOUNT_OFF" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem v-if="couponForm.couponType === 'MIN_SPEND'" label="满额（元）" required>
            <ElInputNumber
              v-model="couponForm.thresholdYuan"
              :min="0.01"
              :precision="2"
              controls-position="right"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="优惠金额（元）" required>
            <ElInputNumber
              v-model="couponForm.discountYuan"
              :min="0.01"
              :precision="2"
              controls-position="right"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="发行总量" required>
            <ElInputNumber
              v-model="couponForm.totalStock"
              :min="1"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="每人限领" required>
            <ElInputNumber
              v-model="couponForm.perUserLimit"
              :min="1"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="有效开始时间" required>
            <ElDatePicker
              v-model="couponForm.validStartAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="有效结束时间" required>
            <ElDatePicker
              v-model="couponForm.validEndAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="couponForm.status" style="width: 100%">
              <ElOption label="启用" value="ENABLED" />
              <ElOption label="停用" value="DISABLED" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="排序">
            <ElInputNumber
              v-model="couponForm.sortOrder"
              :min="0"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
          </ElFormItem>
        </div>
      </ElForm>
      <template #footer>
        <ElButton @click="couponDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="couponSaving" @click="saveCoupon"> 创建并选择 </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { onMounted, reactive, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import { WarningFilled } from '@element-plus/icons-vue'
  import { fetchCouponTemplates } from '@/api/coupon'
  import { createProductSpuCoupon, fetchProductSpuCoupons } from '@/api/product'
  import type { ProductEditorCoupon, ProductEditorForm } from './editor-model'
  import { PRODUCT_TAG_OPTIONS, yuanToCent } from './editor-model'

  interface Props {
    modelValue: ProductEditorForm
    spuId?: number | null
    canBindCoupons?: boolean
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorForm): void
  }

  const props = withDefaults(defineProps<Props>(), {
    spuId: null,
    canBindCoupons: false,
    disabled: false
  })
  const emit = defineEmits<Emits>()

  const coupons = ref<ProductEditorCoupon[]>([])
  const couponLoading = ref(false)
  const couponSaving = ref(false)
  const couponDialogVisible = ref(false)

  const localDateTime = (date: Date) => {
    const pad = (value: number) => String(value).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
      date.getHours()
    )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }

  const createCouponForm = () => {
    const start = new Date()
    const end = new Date(start)
    end.setDate(end.getDate() + 30)
    return {
      name: '',
      description: '',
      couponType: 'NO_THRESHOLD' as 'NO_THRESHOLD' | 'MIN_SPEND',
      discountType: 'AMOUNT_OFF' as 'AMOUNT_OFF' | 'PERCENT_OFF',
      thresholdYuan: null as number | null,
      discountYuan: null as number | null,
      totalStock: 100,
      perUserLimit: 1,
      validStartAt: localDateTime(start),
      validEndAt: localDateTime(end),
      status: 'ENABLED' as 'ENABLED' | 'DISABLED',
      sortOrder: 0
    }
  }

  const couponForm = reactive(createCouponForm())

  const patchForm = (patch: Partial<ProductEditorForm>) => {
    emit('update:modelValue', { ...props.modelValue, ...patch })
  }

  const updateTags = (values: Array<string | number>) => {
    const allowed = new Set(PRODUCT_TAG_OPTIONS.map((item) => item.value))
    patchForm({
      tags: values.filter(
        (value): value is ProductEditorForm['tags'][number] =>
          typeof value === 'string' && allowed.has(value as ProductEditorForm['tags'][number])
      )
    })
  }

  const loadCoupons = async () => {
    couponLoading.value = true
    try {
      const [allResponse, bound] = await Promise.all([
        fetchCouponTemplates({ current: 1, size: 100, status: 'ENABLED' }),
        props.spuId ? fetchProductSpuCoupons(props.spuId) : Promise.resolve([])
      ])
      const allowed = allResponse.records.filter(
        (coupon) =>
          coupon.scopeType === 'ALL' ||
          (coupon.scopeType === 'PRODUCT' && String(props.spuId || '') === coupon.scopeValue)
      )
      const merged = new Map<number, ProductEditorCoupon>()
      ;[...allowed, ...bound].forEach((coupon) => merged.set(coupon.id, coupon))
      coupons.value = Array.from(merged.values())
    } finally {
      couponLoading.value = false
    }
  }

  const openCouponDialog = () => {
    Object.assign(couponForm, createCouponForm())
    couponDialogVisible.value = true
  }

  const saveCoupon = async () => {
    if (!props.spuId) {
      ElMessage.error('请先保存商品后再创建专属优惠券')
      return
    }
    if (!couponForm.name.trim()) {
      ElMessage.error('请输入优惠券名称')
      return
    }
    if (!couponForm.discountYuan) {
      ElMessage.error('请输入优惠金额')
      return
    }
    if (couponForm.couponType === 'MIN_SPEND' && !couponForm.thresholdYuan) {
      ElMessage.error('请输入使用门槛')
      return
    }
    if (
      couponForm.couponType === 'MIN_SPEND' &&
      (couponForm.discountYuan || 0) >= (couponForm.thresholdYuan || 0)
    ) {
      ElMessage.error('优惠金额必须小于使用门槛')
      return
    }
    if (!couponForm.validStartAt || !couponForm.validEndAt) {
      ElMessage.error('请选择有效时间')
      return
    }
    if (couponForm.validStartAt >= couponForm.validEndAt) {
      ElMessage.error('有效结束时间必须晚于开始时间')
      return
    }

    couponSaving.value = true
    try {
      const couponId = await createProductSpuCoupon(props.spuId, {
        name: couponForm.name.trim(),
        description: couponForm.description.trim(),
        couponType: couponForm.couponType,
        discountType: couponForm.discountType,
        thresholdCent:
          couponForm.couponType === 'NO_THRESHOLD' ? 0 : yuanToCent(couponForm.thresholdYuan) || 0,
        discountCent: yuanToCent(couponForm.discountYuan) || 0,
        totalStock: couponForm.totalStock,
        perUserLimit: couponForm.perUserLimit,
        validStartAt: couponForm.validStartAt,
        validEndAt: couponForm.validEndAt,
        status: couponForm.status,
        sortOrder: couponForm.sortOrder
      })
      couponDialogVisible.value = false
      patchForm({
        couponTemplateIds: Array.from(new Set([...props.modelValue.couponTemplateIds, couponId]))
      })
      await loadCoupons()
    } finally {
      couponSaving.value = false
    }
  }

  const validate = async () => {
    if (props.modelValue.sortOrder < 0) {
      ElMessage.error('排序不能小于 0')
      return false
    }
    if (props.modelValue.virtualSales < 0) {
      ElMessage.error('虚拟销量不能小于 0')
      return false
    }
    return true
  }

  defineExpose({ validate })
  onMounted(loadCoupons)
</script>

<style scoped lang="scss">
  .other-settings-tab {
    max-width: 1120px;
    margin: 0 auto;
  }

  .settings-form :deep(.el-form-item) {
    padding: 12px 0;
    margin-bottom: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .settings-form :deep(.el-form-item__label) {
    height: auto;
    min-height: 32px;
  }

  .settings-form :deep(.el-form-item__content) {
    min-width: 0;
    line-height: normal;
  }

  .dialog-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
  }

  .settings-label {
    display: inline-flex;
    gap: 5px;
    align-items: center;

    .el-icon {
      color: var(--el-color-warning);
      cursor: help;
    }
  }

  .settings-number {
    width: 220px;
  }

  .tag-options {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;

    :deep(.el-checkbox-button__inner) {
      border: 1px solid var(--el-border-color);
      border-radius: 8px;
      box-shadow: none;
    }
  }

  .coupon-field {
    display: grid;
    gap: 7px;
    width: 100%;
  }

  .coupon-field__toolbar {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;

    :deep(.el-select) {
      flex: 1 1 360px;
      min-width: 240px;
    }
  }

  .coupon-field__hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .coupon-option {
    display: flex;
    gap: 20px;
    align-items: center;
    justify-content: space-between;
  }

  @media (width <= 680px) {
    .dialog-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .settings-form :deep(.el-form-item) {
      display: block;
    }

    .settings-form :deep(.el-form-item__label) {
      justify-content: flex-start;
      width: auto !important;
      margin-bottom: 8px;
      text-align: left;
    }

    .settings-form :deep(.el-form-item__content) {
      margin-left: 0 !important;
    }

    .settings-number {
      width: 100%;
    }
  }
</style>
