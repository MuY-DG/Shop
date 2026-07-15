<template>
  <div v-loading="loading" class="spu-editor-page">
    <div class="spu-editor-page__header">
      <div>
        <div class="spu-editor-page__eyebrow"
          >商品管理 / {{ localSpuId ? '编辑商品' : '新增商品' }}</div
        >
        <div class="spu-editor-page__title-row">
          <h2>{{ localSpuId ? formData.title || '编辑商品' : '新增商品' }}</h2>
          <ElTag v-if="localSpuId" type="info">ID {{ localSpuId }}</ElTag>
          <ElTag v-if="currentStatus" :type="statusMeta.type">{{ statusMeta.label }}</ElTag>
          <ElTag v-if="isDirty" type="warning" effect="plain">有未保存修改</ElTag>
        </div>
        <p>可自由切换各步骤；提交时统一检查必填项，保存商品后不会自动上架。</p>
      </div>
      <ElButton :disabled="submitting" @click="requestClose">返回商品列表</ElButton>
    </div>

    <ElCard class="spu-editor-page__card" shadow="never">
      <ElTabs v-model="activeTab" class="spu-editor-tabs">
        <ElTabPane name="info">
          <template #label>
            <span class="tab-label"><b>1</b> 商品信息</span>
          </template>
          <ProductInfoTab
            ref="infoTabRef"
            v-model="formData"
            :categories="categories"
            :disabled="submitting"
          />
        </ElTabPane>

        <ElTabPane name="specification">
          <template #label>
            <span class="tab-label"><b>2</b> 规格库存</span>
          </template>
          <ProductSpecificationTab
            ref="specificationTabRef"
            v-model="formData"
            :can-adjust-stock="canAdjustStock"
            :disabled="submitting"
          />
        </ElTabPane>

        <ElTabPane name="detail">
          <template #label>
            <span class="tab-label"><b>3</b> 商品详细</span>
          </template>
          <ProductDetailTab ref="detailTabRef" v-model="formData" :disabled="submitting" />
        </ElTabPane>

        <ElTabPane name="other">
          <template #label>
            <span class="tab-label"><b>4</b> 其他设置</span>
          </template>
          <ProductOtherSettingsTab
            ref="otherTabRef"
            v-model="formData"
            :spu-id="localSpuId"
            :can-bind-coupons="canBindCoupons"
            :disabled="submitting"
          />
        </ElTabPane>
      </ElTabs>
    </ElCard>

    <div class="spu-editor-page__footer">
      <div class="spu-editor-page__progress">
        第 {{ activeTabIndex + 1 }} / {{ tabs.length }} 步
        <span v-if="lastSavedAt">· 最近保存 {{ lastSavedAt }}</span>
      </div>
      <div class="spu-editor-page__footer-actions">
        <ElButton v-if="activeTabIndex > 0" :disabled="submitting" @click="goPrevious">
          上一步
        </ElButton>
        <ElButton
          v-if="activeTabIndex < tabs.length - 1"
          type="primary"
          plain
          :disabled="submitting"
          @click="goNext"
        >
          下一步
        </ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit()">提交</ElButton>
        <ElButton type="primary" plain :loading="submitting" @click="handleSubmit(true)">
          提交并返回商品列表
        </ElButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import {
    computed,
    nextTick,
    onBeforeUnmount,
    onMounted,
    ref,
    watch,
    type ComponentPublicInstance
  } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { onBeforeRouteLeave } from 'vue-router'
  import {
    bindProductSpuCoupons,
    createProductSpu,
    fetchProductSpuDetail,
    updateProductSpu
  } from '@/api/product'
  import { useAuth } from '@/hooks/core/useAuth'
  import ProductInfoTab from './product-info-tab.vue'
  import ProductSpecificationTab from './product-specification-tab.vue'
  import ProductDetailTab from './product-detail-tab.vue'
  import ProductOtherSettingsTab from './product-other-settings-tab.vue'
  import type {
    ProductEditorForm,
    ProductEditorSku,
    ProductEditorSpecGroup,
    ProductEditorSpecValue,
    ProductSkuStatus,
    ProductTagCode
  } from './editor-model'
  import { createDefaultForm } from './editor-model'
  import {
    buildCombinationKey,
    createEditorKey,
    createEmptySku,
    hydrateSkuImageFallbacks,
    normalizeDefaultSku
  } from './sku-matrix'

  interface Props {
    spuId?: number | null
    categories: Api.Product.Category[]
  }

  interface Emits {
    (event: 'success', spuId: number): void
    (event: 'success-and-close', spuId: number): void
    (event: 'cancel'): void
  }

  interface ValidatableTab extends ComponentPublicInstance {
    validate: () => Promise<boolean>
  }

  type DetailSku = Api.Product.Sku &
    Partial<{
      costPriceCent: number | null
      volumeCubicMeter: number | null
      defaultSelected: boolean
      combinationKey: string
      specValueKeys: string[]
    }>

  type DetailSpecValue = Partial<ProductEditorSpecValue> & {
    valueName: string
  }

  type DetailSpecGroup = Partial<Omit<ProductEditorSpecGroup, 'values'>> & {
    name: string
    values: DetailSpecValue[]
  }

  type ProductDetail = Api.Product.SpuDetail &
    Partial<{
      mainVideo: string
      mainVideoFileId: number | null
      specType: 'SINGLE' | 'MULTI'
      freightTemplateId: number | null
      virtualSales: number
      specGroups: DetailSpecGroup[]
      tags: ProductTagCode[]
      guaranteeServiceIds: number[]
      couponTemplateIds: number[]
    }>

  const props = withDefaults(defineProps<Props>(), {
    spuId: null,
    categories: () => []
  })
  const emit = defineEmits<Emits>()
  const { hasAuth } = useAuth()

  const tabs = ['info', 'specification', 'detail', 'other'] as const
  type TabName = (typeof tabs)[number]

  const activeTab = ref<TabName>('info')
  const formData = ref<ProductEditorForm>(createDefaultForm())
  const localSpuId = ref<number | null>(props.spuId)
  const currentStatus = ref<Api.Product.ProductStatus | null>(null)
  const loading = ref(false)
  const submitting = ref(false)
  const lastSavedAt = ref('')
  const savedSnapshot = ref('')
  const loadSequence = ref(0)

  const infoTabRef = ref<ValidatableTab>()
  const specificationTabRef = ref<ValidatableTab>()
  const detailTabRef = ref<ValidatableTab>()
  const otherTabRef = ref<ValidatableTab>()

  const activeTabIndex = computed(() => tabs.indexOf(activeTab.value))
  const canBindCoupons = computed(() => hasAuth('product:coupon:bind'))
  const canAdjustStock = computed(() => hasAuth('product:sku:stock'))
  const currentSnapshot = computed(() => JSON.stringify(formData.value))
  const isDirty = computed(
    () => Boolean(savedSnapshot.value) && currentSnapshot.value !== savedSnapshot.value
  )
  const statusMeta = computed(() => {
    const map = {
      DRAFT: { label: '草稿', type: 'info' as const },
      ON_SALE: { label: '销售中', type: 'success' as const },
      OFF_SALE: { label: '已下架', type: 'warning' as const }
    }
    return currentStatus.value ? map[currentStatus.value] : map.DRAFT
  })

  const tabRefs = computed<Array<ValidatableTab | undefined>>(() => [
    infoTabRef.value,
    specificationTabRef.value,
    detailTabRef.value,
    otherTabRef.value
  ])

  const parseSpecJson = (value: string) => {
    try {
      const parsed = JSON.parse(value || '{}')
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') return {}
      return Object.fromEntries(
        Object.entries(parsed).map(([key, item]) => [key, String(item ?? '')])
      ) as Record<string, string>
    } catch {
      return {}
    }
  }

  const deriveLegacyMultiSpec = (skus: ProductEditorSku[]) => {
    const parsedRows = skus.map((sku) => parseSpecJson(sku.specJson))
    const groupNames = Array.from(new Set(parsedRows.flatMap((row) => Object.keys(row))))
    if (!groupNames.length) return { groups: [] as ProductEditorSpecGroup[], skus }

    const groups = groupNames.map<ProductEditorSpecGroup>((name, groupIndex) => {
      const valueNames = Array.from(
        new Set(
          parsedRows.map((row) => row[name]).filter((value): value is string => Boolean(value))
        )
      )
      return {
        groupKey: `legacy_group_${groupIndex + 1}`,
        name,
        imageEnabled: groupIndex === 0,
        sortOrder: groupIndex,
        values: valueNames.map((valueName, valueIndex) => {
          const sourceSku = skus.find((_, skuIndex) => parsedRows[skuIndex][name] === valueName)
          return {
            valueKey: `legacy_value_${groupIndex + 1}_${valueIndex + 1}`,
            valueName,
            image: groupIndex === 0 ? sourceSku?.image || '' : '',
            imageFileId: groupIndex === 0 ? sourceSku?.imageFileId || null : null,
            sortOrder: valueIndex
          }
        })
      }
    })

    const normalizedSkus = skus.map((sku, skuIndex) => {
      const row = parsedRows[skuIndex]
      const parts = groups
        .map((group) => ({
          group,
          value: group.values.find((value) => value.valueName === row[group.name])
        }))
        .filter((part): part is { group: ProductEditorSpecGroup; value: ProductEditorSpecValue } =>
          Boolean(part.value)
        )
      return {
        ...sku,
        combinationKey: buildCombinationKey(parts),
        specValueKeys: parts.map((part) => part.value.valueKey)
      }
    })
    normalizeDefaultSku(normalizedSkus)
    return { groups, skus: normalizedSkus }
  }

  const mapSku = (sku: DetailSku, index: number): ProductEditorSku => ({
    id: sku.id,
    skuCode: sku.skuCode || '',
    specJson: sku.specJson || '{}',
    specText: sku.specText || '默认规格',
    priceCent: sku.priceCent ?? null,
    costPriceCent: sku.costPriceCent ?? null,
    originalPriceCent: sku.originalPriceCent ?? null,
    stockAvailable: sku.stockAvailable ?? 0,
    weightGram: sku.weightGram ?? null,
    volumeCubicMeter: sku.volumeCubicMeter ?? null,
    image: sku.image || '',
    imageFileId: sku.imageFileId ?? null,
    status: (sku.status || 'ENABLED') as ProductSkuStatus,
    defaultSelected: sku.defaultSelected ?? index === 0,
    combinationKey: sku.combinationKey || (index === 0 ? 'SINGLE' : `LEGACY:${sku.id || index}`),
    specValueKeys: sku.specValueKeys || [],
    sortOrder: sku.sortOrder ?? index
  })

  const mapSpecGroups = (groups: DetailSpecGroup[] = []): ProductEditorSpecGroup[] =>
    groups.map((group, groupIndex) => ({
      id: group.id,
      groupKey: group.groupKey || createEditorKey('group'),
      name: group.name,
      imageEnabled: Boolean(group.imageEnabled),
      sortOrder: group.sortOrder ?? groupIndex,
      values: (group.values || []).map((value, valueIndex) => ({
        id: value.id,
        valueKey: value.valueKey || createEditorKey('value'),
        valueName: value.valueName,
        image: value.image || '',
        imageFileId: value.imageFileId ?? null,
        sortOrder: value.sortOrder ?? valueIndex
      }))
    }))

  const fillForm = (detail?: ProductDetail) => {
    if (!detail) {
      const empty = createDefaultForm()
      empty.skus = [createEmptySku()]
      formData.value = empty
      currentStatus.value = null
      return
    }

    let skus = (detail.skus || []).map((sku, index) => mapSku(sku as DetailSku, index))
    let specGroups = mapSpecGroups(detail.specGroups || [])
    let specType = detail.specType || 'SINGLE'
    if (!specGroups.length && skus.length > 1) {
      const legacy = deriveLegacyMultiSpec(skus)
      specGroups = legacy.groups
      skus = legacy.skus
      if (specGroups.length) specType = 'MULTI'
    }
    if (specType === 'SINGLE') {
      const single = skus[0] || createEmptySku()
      skus = [
        {
          ...single,
          status: 'ENABLED',
          defaultSelected: true,
          combinationKey: 'SINGLE',
          specJson: '{}',
          specText: '默认规格',
          specValueKeys: [],
          sortOrder: 0
        }
      ]
      specGroups = []
    } else {
      normalizeDefaultSku(skus)
    }
    skus = hydrateSkuImageFallbacks(
      skus,
      specGroups,
      detail.mainImage || '',
      detail.mainImageFileId ?? null
    )

    formData.value = {
      categoryId: detail.categoryId,
      title: detail.title || '',
      subtitle: detail.subtitle || '',
      mainImage: detail.mainImage || '',
      mainImageFileId: detail.mainImageFileId ?? null,
      mainVideo: detail.mainVideo || '',
      mainVideoFileId: detail.mainVideoFileId ?? null,
      sellingPoints: detail.sellingPoints || '',
      detailHtml: detail.detailHtml || '',
      specType,
      freightTemplateId: detail.freightTemplateId ?? null,
      virtualSales: detail.virtualSales ?? 0,
      sortOrder: detail.sortOrder ?? 0,
      images: (detail.images || []).map((image) => ({
        url: image.url || '',
        fileId: image.fileId ?? null
      })),
      skus,
      specGroups,
      tags: detail.tags || [],
      guaranteeServiceIds: detail.guaranteeServiceIds || [],
      couponTemplateIds: detail.couponTemplateIds || []
    }
    currentStatus.value = detail.status
  }

  const rememberSavedState = async () => {
    await nextTick()
    savedSnapshot.value = JSON.stringify(formData.value)
    const now = new Date()
    lastSavedAt.value = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }

  const loadDetail = async () => {
    const sequence = ++loadSequence.value
    activeTab.value = 'info'
    localSpuId.value = props.spuId
    loading.value = true
    try {
      if (!props.spuId) {
        fillForm()
      } else {
        const detail = (await fetchProductSpuDetail(props.spuId)) as ProductDetail
        if (sequence !== loadSequence.value) return
        fillForm(detail)
      }
      await rememberSavedState()
    } finally {
      if (sequence === loadSequence.value) loading.value = false
    }
  }

  const validateTab = async (index: number) => {
    const tab = tabRefs.value[index]
    return tab ? tab.validate() : true
  }

  const goPrevious = () => {
    if (activeTabIndex.value > 0) activeTab.value = tabs[activeTabIndex.value - 1]
  }

  const goNext = () => {
    if (activeTabIndex.value < tabs.length - 1) {
      activeTab.value = tabs[activeTabIndex.value + 1]
    }
  }

  const validateAll = async () => {
    for (let index = 0; index < tabs.length; index += 1) {
      if (!(await validateTab(index))) {
        activeTab.value = tabs[index]
        return false
      }
    }
    return true
  }

  const buildPayload = () => {
    const form = formData.value
    const specGroups = form.specType === 'MULTI' ? form.specGroups : []
    const skus = form.skus.map((sku, index) => ({
      id: sku.id,
      skuCode: sku.skuCode.trim(),
      specJson: form.specType === 'SINGLE' ? '{}' : sku.specJson,
      specText: form.specType === 'SINGLE' ? '默认规格' : sku.specText,
      priceCent: sku.priceCent,
      costPriceCent: sku.costPriceCent,
      originalPriceCent: sku.originalPriceCent,
      stockAvailable: sku.stockAvailable,
      weightGram: sku.weightGram,
      volumeCubicMeter: sku.volumeCubicMeter,
      image: sku.image.trim(),
      imageFileId: sku.imageFileId,
      status: form.specType === 'SINGLE' ? 'ENABLED' : sku.status,
      defaultSelected: form.specType === 'SINGLE' ? true : sku.defaultSelected,
      combinationKey: form.specType === 'SINGLE' ? 'SINGLE' : sku.combinationKey,
      specValueKeys: form.specType === 'SINGLE' ? [] : sku.specValueKeys,
      sortOrder: index
    }))

    return {
      categoryId: form.categoryId,
      title: form.title.trim(),
      subtitle: form.subtitle,
      mainImage: form.mainImage.trim(),
      mainImageFileId: form.mainImageFileId,
      mainVideo: form.mainVideo.trim(),
      mainVideoFileId: form.mainVideoFileId,
      sellingPoints: form.sellingPoints,
      detailHtml: form.detailHtml,
      specType: form.specType,
      freightTemplateId: form.freightTemplateId,
      virtualSales: form.virtualSales,
      sortOrder: form.sortOrder,
      images: form.images
        .map((image) => ({ url: image.url.trim(), fileId: image.fileId }))
        .filter((image) => image.url),
      skus,
      specGroups: specGroups.map((group, groupIndex) => ({
        id: group.id,
        groupKey: group.groupKey,
        name: group.name.trim(),
        imageEnabled: group.imageEnabled,
        sortOrder: groupIndex,
        values: group.values.map((value, valueIndex) => ({
          id: value.id,
          valueKey: value.valueKey,
          valueName: value.valueName.trim(),
          image: value.image.trim(),
          imageFileId: value.imageFileId,
          sortOrder: valueIndex
        }))
      })),
      tags: form.tags,
      guaranteeServiceIds: form.guaranteeServiceIds
    } as Api.Product.SpuForm
  }

  const handleSubmit = async (returnToListAfterSave = false) => {
    if (!(await validateAll())) return
    submitting.value = true
    try {
      const payload = buildPayload()
      if (localSpuId.value) {
        await updateProductSpu(localSpuId.value, payload)
      } else {
        localSpuId.value = await createProductSpu(payload)
        currentStatus.value = 'DRAFT'
      }
      if (canBindCoupons.value) {
        await bindProductSpuCoupons(localSpuId.value, {
          couponTemplateIds: formData.value.couponTemplateIds
        })
      }
      await rememberSavedState()
      if (returnToListAfterSave) {
        ElMessage.success('商品已保存，正在返回商品列表')
        emit('success-and-close', localSpuId.value)
      } else {
        ElMessage.success('商品已保存，可继续编辑或返回列表管理上下架')
        emit('success', localSpuId.value)
      }
    } finally {
      submitting.value = false
    }
  }

  const confirmDiscardChanges = async () => {
    if (!isDirty.value) return true
    try {
      await ElMessageBox.confirm('当前商品有未保存修改，确定离开当前页面吗？', '离开编辑器', {
        type: 'warning',
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑'
      })
      return true
    } catch {
      return false
    }
  }

  let allowNextRouteLeave = false

  const requestClose = async () => {
    if (!(await confirmDiscardChanges())) return
    allowNextRouteLeave = true
    emit('cancel')
  }

  onBeforeRouteLeave(async () => {
    if (allowNextRouteLeave) {
      allowNextRouteLeave = false
      return true
    }
    return confirmDiscardChanges()
  })

  const handleBeforeUnload = (event: BeforeUnloadEvent) => {
    if (!isDirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }

  defineExpose({ isDirty, requestClose })

  watch(() => props.spuId, loadDetail)
  onMounted(() => {
    window.addEventListener('beforeunload', handleBeforeUnload)
    loadDetail()
  })
  onBeforeUnmount(() => window.removeEventListener('beforeunload', handleBeforeUnload))
</script>

<style scoped lang="scss">
  .spu-editor-page {
    min-height: calc(100vh - 120px);
    padding-bottom: 88px;
  }

  .spu-editor-page__header {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 16px;

    h2 {
      max-width: min(720px, 70vw);
      margin: 0;
      overflow: hidden;
      font-size: 24px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    p {
      margin: 8px 0 0;
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }
  }

  .spu-editor-page__eyebrow {
    margin-bottom: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .spu-editor-page__title-row {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  .spu-editor-page__card {
    min-height: 680px;
  }

  .spu-editor-tabs :deep(.el-tabs__header) {
    margin-bottom: 22px;
  }

  .tab-label {
    display: inline-flex;
    gap: 7px;
    align-items: center;

    b {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 22px;
      height: 22px;
      font-size: 12px;
      background: var(--el-fill-color);
      border-radius: 50%;
    }
  }

  :deep(.el-tabs__item.is-active) .tab-label b {
    color: #fff;
    background: var(--el-color-primary);
  }

  .spu-editor-page__footer {
    position: fixed;
    right: 24px;
    bottom: 18px;
    left: calc(var(--art-sidebar-width, 230px) + 24px);
    z-index: 12;
    display: flex;
    gap: 20px;
    align-items: center;
    justify-content: space-between;
    padding: 14px 18px;
    background: color-mix(in srgb, var(--el-bg-color) 94%, transparent);
    backdrop-filter: blur(10px);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 12px;
    box-shadow: 0 8px 24px rgb(0 0 0 / 8%);
  }

  .spu-editor-page__progress {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .spu-editor-page__footer-actions {
    display: flex;
    gap: 10px;
  }

  @media (width <= 900px) {
    .spu-editor-page__footer {
      left: 24px;
    }
  }

  @media (width <= 640px) {
    .spu-editor-page__header,
    .spu-editor-page__footer {
      flex-direction: column;
      align-items: flex-start;
    }

    .spu-editor-page__footer {
      right: 12px;
      bottom: 10px;
      left: 12px;
    }

    .spu-editor-page__footer-actions {
      justify-content: flex-end;
      width: 100%;
    }
  }
</style>
