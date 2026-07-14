<template>
  <div class="other-settings-tab">
    <section class="settings-section">
      <div class="settings-section__heading">
        <h3>基础设置</h3>
        <p>排序越小越靠前；展示销量 = 实际支付销量 + 虚拟销量。</p>
      </div>
      <div class="settings-grid">
        <ElFormItem label="排序">
          <ElInputNumber
            :model-value="modelValue.sortOrder"
            :min="0"
            :precision="0"
            :disabled="disabled"
            controls-position="right"
            style="width: 100%"
            @update:model-value="patchForm({ sortOrder: $event ?? 0 })"
          />
          <div class="field-hint">可选，默认 0</div>
        </ElFormItem>
        <ElFormItem label="虚拟销量">
          <ElInputNumber
            :model-value="modelValue.virtualSales"
            :min="0"
            :precision="0"
            :disabled="disabled"
            controls-position="right"
            style="width: 100%"
            @update:model-value="patchForm({ virtualSales: $event ?? 0 })"
          />
          <div class="field-hint">为 0 时只展示实际支付销量</div>
        </ElFormItem>
      </div>
    </section>

    <section class="settings-section">
      <div class="settings-section__heading">
        <h3>商品标签</h3>
        <p>可多选，标签用于后台运营标记和后续商品聚合展示。</p>
      </div>
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
    </section>

    <section class="settings-section">
      <div class="settings-section__heading settings-section__heading--row">
        <div>
          <h3>优惠券</h3>
          <p>可绑定全场券和当前商品专属券；专属商品券需先保存商品一次。</p>
        </div>
        <div class="coupon-actions">
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
      </div>

      <ElAlert
        v-if="!spuId"
        type="info"
        :closable="false"
        title="新商品首次提交后会保留商品 ID，届时可以创建专属商品优惠券。"
      />
      <ElAlert
        v-else-if="!canBindCoupons"
        type="info"
        :closable="false"
        title="当前账号没有优惠券绑定权限，仅可查看已绑定优惠券。"
      />

      <ElSelect
        :model-value="modelValue.couponTemplateIds"
        multiple
        filterable
        collapse-tags
        collapse-tags-tooltip
        placeholder="请选择优惠券"
        :loading="couponLoading"
        :disabled="disabled || !canBindCoupons"
        style="width: 100%; margin-top: 12px"
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
    </section>

    <section class="settings-section settings-section--summary">
      <div class="settings-section__heading">
        <h3>保障服务</h3>
        <p>保障服务只在“商品信息”中维护，这里仅展示摘要，避免两处选择不一致。</p>
      </div>
      <ElTag v-if="modelValue.guaranteeServiceIds.length" type="success">
        已选择 {{ modelValue.guaranteeServiceIds.length }} 项保障服务
      </ElTag>
      <span v-else class="empty-summary">未选择保障服务</span>
    </section>

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
    display: grid;
    gap: 18px;
    max-width: 940px;
    margin: 0 auto;
  }

  .settings-section {
    padding: 20px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 12px;
  }

  .settings-section__heading {
    margin-bottom: 18px;

    h3 {
      margin: 0;
      font-size: 16px;
    }

    p {
      margin: 6px 0 0;
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }
  }

  .settings-section__heading--row {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .settings-grid,
  .dialog-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
  }

  .field-hint {
    margin-top: 5px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
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

  .coupon-actions {
    display: flex;
    gap: 8px;
  }

  .coupon-option {
    display: flex;
    gap: 20px;
    align-items: center;
    justify-content: space-between;
  }

  .settings-section--summary {
    display: flex;
    gap: 20px;
    align-items: center;
    justify-content: space-between;

    .settings-section__heading {
      margin-bottom: 0;
    }
  }

  .empty-summary {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  @media (width <= 680px) {
    .settings-grid,
    .dialog-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .settings-section__heading--row,
    .settings-section--summary {
      flex-direction: column;
      align-items: flex-start;
    }
  }
</style>
