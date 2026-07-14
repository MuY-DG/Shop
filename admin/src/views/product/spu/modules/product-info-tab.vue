<template>
  <div class="product-info-tab">
    <ElForm label-position="left" label-width="136px" class="product-info-form">
      <ElFormItem label="商品名称" required>
        <ElInput
          :model-value="modelValue.title"
          maxlength="80"
          show-word-limit
          placeholder="请输入商品名称"
          :disabled="disabled"
          @update:model-value="patchForm({ title: $event })"
        />
      </ElFormItem>

      <ElFormItem label="商品分类" required>
        <ElTreeSelect
          :model-value="modelValue.categoryId"
          :data="categoryTreeOptions"
          node-key="value"
          check-strictly
          clearable
          default-expand-all
          :render-after-expand="false"
          placeholder="请选择商品分类"
          :disabled="disabled"
          style="width: 100%"
          @update:model-value="patchForm({ categoryId: $event || null })"
        />
      </ElFormItem>

      <ElFormItem required>
        <template #label>
          <span class="field-label">
            商品封面图
            <ElTooltip content="支持 JPG、PNG、WebP、GIF，单张最大 5 MB" placement="top">
              <ElIcon class="field-label__help"><WarningFilled /></ElIcon>
            </ElTooltip>
          </span>
        </template>
        <CompactAssetField
          :model-value="{ fileId: modelValue.mainImageFileId, url: modelValue.mainImage }"
          media-kind="IMAGE"
          :disabled="disabled"
          @change="updateCover"
        />
      </ElFormItem>

      <ElFormItem>
        <template #label>
          <span class="field-label">
            商品轮播图
            <ElTooltip content="横向按顺序展示；留空时商品端使用封面图" placement="top">
              <ElIcon class="field-label__help"><WarningFilled /></ElIcon>
            </ElTooltip>
          </span>
        </template>
        <div class="media-strip">
          <div
            v-for="(image, index) in galleryItems"
            :key="`gallery-${index}`"
            class="media-strip__item"
          >
            <span class="media-strip__index">{{ index + 1 }}</span>
            <CompactAssetField
              :model-value="{ fileId: image.fileId, url: image.url }"
              media-kind="IMAGE"
              :disabled="disabled"
              @change="updateGallery(index, $event)"
            />
          </div>
          <button
            type="button"
            class="media-strip__add"
            :disabled="disabled"
            aria-label="添加轮播图"
            @click="addGallery"
          >
            <ElIcon size="24"><Plus /></ElIcon>
            <span>添加</span>
          </button>
        </div>
      </ElFormItem>

      <ElFormItem>
        <template #label>
          <span class="field-label">
            主图视频
            <ElTooltip content="支持 MP4、WebM，单个最大 50 MB" placement="top">
              <ElIcon class="field-label__help"><WarningFilled /></ElIcon>
            </ElTooltip>
          </span>
        </template>
        <CompactAssetField
          :model-value="{ fileId: modelValue.mainVideoFileId, url: modelValue.mainVideo }"
          media-kind="VIDEO"
          :disabled="disabled"
          @change="updateVideo"
        />
      </ElFormItem>

      <ElFormItem label="保障服务">
        <div class="relation-field__toolbar">
          <ElSelect
            :model-value="modelValue.guaranteeServiceIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="请选择保障服务（可选）"
            :loading="guaranteeLoading"
            :disabled="disabled"
            style="width: min(620px, 100%)"
            @update:model-value="patchForm({ guaranteeServiceIds: $event })"
          >
            <ElOption
              v-for="service in visibleGuarantees"
              :key="service.id"
              :label="service.termsName"
              :value="service.id"
            >
              <div class="relation-option">
                <span>{{ service.termsName }}</span>
                <small>{{ service.contentDescription }}</small>
              </div>
            </ElOption>
          </ElSelect>
          <ElButton :loading="guaranteeLoading" :disabled="disabled" @click="loadGuarantees">
            刷新
          </ElButton>
          <ElButton
            v-auth="'product:guarantee:create'"
            type="primary"
            plain
            :disabled="disabled"
            @click="openGuaranteeDialog"
          >
            添加保障服务
          </ElButton>
        </div>
      </ElFormItem>

      <ElFormItem label="运费模板" required>
        <div class="relation-field__toolbar">
          <ElSelect
            :model-value="modelValue.freightTemplateId"
            filterable
            placeholder="请选择运费模板"
            :loading="freightLoading"
            :disabled="disabled"
            style="width: min(520px, 100%)"
            @update:model-value="patchForm({ freightTemplateId: $event || null })"
          >
            <ElOption
              v-for="template in enabledFreightTemplates"
              :key="template.id"
              :label="formatFreightLabel(template)"
              :value="template.id"
            />
          </ElSelect>
          <ElButton :loading="freightLoading" :disabled="disabled" @click="loadFreightTemplates">
            刷新
          </ElButton>
          <ElButton
            v-auth="'product:freight:create'"
            type="primary"
            plain
            :disabled="disabled"
            @click="openFreightDialog()"
          >
            新建模板
          </ElButton>
          <ElButton
            v-if="selectedFreightTemplate"
            v-auth="'product:freight:update'"
            plain
            :disabled="disabled"
            @click="openFreightDialog(selectedFreightTemplate)"
          >
            编辑当前模板
          </ElButton>
        </div>
      </ElFormItem>
    </ElForm>

    <ElDialog v-model="guaranteeDialogVisible" title="添加保障服务" width="620px" align-center>
      <ElForm label-position="top">
        <ElFormItem label="服务条款名称" required>
          <ElInput v-model="guaranteeForm.termsName" maxlength="64" show-word-limit />
        </ElFormItem>
        <ElFormItem label="服务内容描述" required>
          <ElInput
            v-model="guaranteeForm.contentDescription"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
          />
        </ElFormItem>
        <ElFormItem label="服务条款图标" required>
          <AssetPicker
            :model-value="{ fileId: guaranteeForm.iconFileId, url: guaranteeForm.icon }"
            media-kind="IMAGE"
            @change="updateGuaranteeIcon"
          />
        </ElFormItem>
        <div class="dialog-grid">
          <ElFormItem label="排序">
            <ElInputNumber v-model="guaranteeForm.sortOrder" :min="0" :precision="0" />
          </ElFormItem>
          <ElFormItem label="是否显示">
            <ElSwitch v-model="guaranteeForm.visible" />
          </ElFormItem>
        </div>
      </ElForm>
      <template #footer>
        <ElButton @click="guaranteeDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="guaranteeSaving" @click="saveGuarantee">
          添加并选择
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="freightDialogVisible"
      :title="freightEditingId ? '编辑运费模板' : '新建运费模板'"
      width="560px"
      align-center
    >
      <ElForm label-position="top">
        <ElFormItem label="模板名称" required>
          <ElInput v-model="freightForm.name" maxlength="64" show-word-limit />
        </ElFormItem>
        <ElFormItem label="计费方式" required>
          <ElRadioGroup v-model="freightForm.chargeMode">
            <ElRadioButton value="FREE">包邮</ElRadioButton>
            <ElRadioButton value="FIXED">固定运费</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem v-if="freightForm.chargeMode === 'FIXED'" label="固定运费（元）" required>
          <ElInputNumber
            v-model="freightForm.fixedAmountYuan"
            :min="0.01"
            :precision="2"
            :step="1"
            controls-position="right"
            style="width: 100%"
          />
        </ElFormItem>
        <div class="dialog-grid">
          <ElFormItem label="状态">
            <ElSelect v-model="freightForm.status" style="width: 100%">
              <ElOption label="启用" value="ENABLED" />
              <ElOption label="停用" value="DISABLED" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="排序">
            <ElInputNumber
              v-model="freightForm.sortOrder"
              :min="0"
              :precision="0"
              style="width: 100%"
            />
          </ElFormItem>
        </div>
      </ElForm>
      <template #footer>
        <ElButton @click="freightDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="freightSaving" @click="saveFreightTemplate">
          保存并选择
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import { Plus, WarningFilled } from '@element-plus/icons-vue'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import {
    createProductFreightTemplate,
    createProductGuaranteeService,
    fetchProductFreightTemplates,
    fetchProductGuaranteeServices,
    updateProductFreightTemplate
  } from '@/api/product'
  import type {
    ProductEditorForm,
    ProductEditorFreightTemplate,
    ProductEditorGuaranteeService
  } from './editor-model'
  import { createEmptyImage, yuanToCent } from './editor-model'
  import CompactAssetField from './compact-asset-field.vue'

  interface TreeOption {
    value: number
    label: string
    disabled?: boolean
    children?: TreeOption[]
  }

  interface Props {
    modelValue: ProductEditorForm
    categories: Api.Product.Category[]
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorForm): void
  }

  const props = withDefaults(defineProps<Props>(), {
    categories: () => [],
    disabled: false
  })
  const emit = defineEmits<Emits>()

  const guarantees = ref<ProductEditorGuaranteeService[]>([])
  const freightTemplates = ref<ProductEditorFreightTemplate[]>([])
  const guaranteeLoading = ref(false)
  const freightLoading = ref(false)
  const guaranteeSaving = ref(false)
  const freightSaving = ref(false)
  const guaranteeDialogVisible = ref(false)
  const freightDialogVisible = ref(false)
  const freightEditingId = ref<number | null>(null)

  const guaranteeForm = reactive({
    termsName: '',
    contentDescription: '',
    icon: '',
    iconFileId: null as number | null,
    sortOrder: 0,
    visible: true
  })

  const freightForm = reactive({
    name: '',
    chargeMode: 'FREE' as 'FREE' | 'FIXED',
    fixedAmountYuan: null as number | null,
    status: 'ENABLED' as 'ENABLED' | 'DISABLED',
    sortOrder: 0
  })

  const categoryTreeOptions = computed<TreeOption[]>(() => {
    const build = (items: Api.Product.Category[]): TreeOption[] =>
      items.map((item) => ({
        value: item.id,
        label: item.status === 'DISABLED' ? `${item.name}（已停用）` : item.name,
        disabled: item.status === 'DISABLED' && item.id !== props.modelValue.categoryId,
        children: build(item.children || [])
      }))
    return build(props.categories)
  })

  const visibleGuarantees = computed(() =>
    guarantees.value.filter(
      (item) => item.visible || props.modelValue.guaranteeServiceIds.includes(item.id)
    )
  )
  const enabledFreightTemplates = computed(() =>
    freightTemplates.value.filter(
      (item) => item.status === 'ENABLED' || item.id === props.modelValue.freightTemplateId
    )
  )
  const selectedFreightTemplate = computed(() =>
    freightTemplates.value.find((item) => item.id === props.modelValue.freightTemplateId)
  )
  const galleryItems = computed(() =>
    props.modelValue.images.length ? props.modelValue.images : [createEmptyImage()]
  )

  const patchForm = (patch: Partial<ProductEditorForm>) => {
    emit('update:modelValue', { ...props.modelValue, ...patch })
  }

  const updateCover = (asset: Api.Common.AssetValue) => {
    patchForm({ mainImage: asset.url, mainImageFileId: asset.fileId })
  }

  const updateVideo = (asset: Api.Common.AssetValue) => {
    patchForm({ mainVideo: asset.url, mainVideoFileId: asset.fileId })
  }

  const addGallery = () => {
    const images = props.modelValue.images.length ? props.modelValue.images : [createEmptyImage()]
    patchForm({ images: [...images, createEmptyImage()] })
  }

  const removeGallery = (index: number) => {
    const images = props.modelValue.images.filter((_, imageIndex) => imageIndex !== index)
    patchForm({ images })
  }

  const updateGallery = (index: number, asset: Api.Common.AssetValue) => {
    if (!asset.url && !asset.fileId && props.modelValue.images.length > 1) {
      removeGallery(index)
      return
    }
    const source = props.modelValue.images.length ? props.modelValue.images : [createEmptyImage()]
    const images = source.map((image, imageIndex) =>
      imageIndex === index ? { url: asset.url, fileId: asset.fileId } : image
    )
    patchForm({ images })
  }

  const loadGuarantees = async () => {
    guaranteeLoading.value = true
    try {
      const response = await fetchProductGuaranteeServices({ current: 1, size: 100 })
      guarantees.value = response.records as ProductEditorGuaranteeService[]
    } finally {
      guaranteeLoading.value = false
    }
  }

  const loadFreightTemplates = async () => {
    freightLoading.value = true
    try {
      freightTemplates.value =
        (await fetchProductFreightTemplates()) as ProductEditorFreightTemplate[]
      if (!props.modelValue.freightTemplateId) {
        const defaultTemplate = enabledFreightTemplates.value.find(
          (item) => item.chargeMode === 'FREE'
        )
        if (defaultTemplate) patchForm({ freightTemplateId: defaultTemplate.id })
      }
    } finally {
      freightLoading.value = false
    }
  }

  const openGuaranteeDialog = () => {
    Object.assign(guaranteeForm, {
      termsName: '',
      contentDescription: '',
      icon: '',
      iconFileId: null,
      sortOrder: 0,
      visible: true
    })
    guaranteeDialogVisible.value = true
  }

  const updateGuaranteeIcon = (asset: Api.Common.AssetValue) => {
    guaranteeForm.icon = asset.url
    guaranteeForm.iconFileId = asset.fileId
  }

  const saveGuarantee = async () => {
    if (!guaranteeForm.termsName.trim()) {
      ElMessage.error('请输入服务条款名称')
      return
    }
    if (!guaranteeForm.contentDescription.trim()) {
      ElMessage.error('请输入服务内容描述')
      return
    }
    if (!guaranteeForm.icon.trim()) {
      ElMessage.error('请选择服务条款图标')
      return
    }
    guaranteeSaving.value = true
    try {
      const serviceId = await createProductGuaranteeService({
        ...guaranteeForm,
        termsName: guaranteeForm.termsName.trim(),
        contentDescription: guaranteeForm.contentDescription.trim(),
        icon: guaranteeForm.icon.trim()
      })
      guaranteeDialogVisible.value = false
      await loadGuarantees()
      patchForm({
        guaranteeServiceIds: Array.from(
          new Set([...props.modelValue.guaranteeServiceIds, serviceId])
        )
      })
    } finally {
      guaranteeSaving.value = false
    }
  }

  const openFreightDialog = (template?: ProductEditorFreightTemplate) => {
    freightEditingId.value = template?.id || null
    Object.assign(freightForm, {
      name: template?.name || '',
      chargeMode: template?.chargeMode || 'FREE',
      fixedAmountYuan:
        template?.fixedAmountCent === null || template?.fixedAmountCent === undefined
          ? null
          : template.fixedAmountCent / 100,
      status: template?.status || 'ENABLED',
      sortOrder: template?.sortOrder || 0
    })
    freightDialogVisible.value = true
  }

  const saveFreightTemplate = async () => {
    if (!freightForm.name.trim()) {
      ElMessage.error('请输入运费模板名称')
      return
    }
    if (freightForm.chargeMode === 'FIXED' && !freightForm.fixedAmountYuan) {
      ElMessage.error('请输入固定运费')
      return
    }
    const payload = {
      name: freightForm.name.trim(),
      chargeMode: freightForm.chargeMode,
      fixedAmountCent:
        freightForm.chargeMode === 'FIXED' ? yuanToCent(freightForm.fixedAmountYuan) : null,
      status: freightForm.status,
      sortOrder: freightForm.sortOrder
    }
    freightSaving.value = true
    try {
      let templateId = freightEditingId.value
      if (templateId) {
        await updateProductFreightTemplate(templateId, payload)
      } else {
        templateId = await createProductFreightTemplate(payload)
      }
      freightDialogVisible.value = false
      await loadFreightTemplates()
      patchForm({ freightTemplateId: templateId })
    } finally {
      freightSaving.value = false
    }
  }

  const formatFreightLabel = (template: ProductEditorFreightTemplate) =>
    template.chargeMode === 'FREE'
      ? `${template.name}（包邮）`
      : `${template.name}（¥${((template.fixedAmountCent || 0) / 100).toFixed(2)}）`

  const validate = async () => {
    if (!props.modelValue.title.trim()) {
      ElMessage.error('请输入商品名称')
      return false
    }
    if (!props.modelValue.categoryId) {
      ElMessage.error('请选择商品分类')
      return false
    }
    if (!props.modelValue.mainImage.trim()) {
      ElMessage.error('请选择商品封面图')
      return false
    }
    if (!props.modelValue.freightTemplateId) {
      ElMessage.error('请选择运费模板')
      return false
    }
    const freightTemplate = freightTemplates.value.find(
      (item) => item.id === props.modelValue.freightTemplateId
    )
    if (freightTemplate && freightTemplate.status !== 'ENABLED') {
      ElMessage.error('当前运费模板已停用，请选择已启用模板')
      return false
    }
    return true
  }

  defineExpose({ validate })

  onMounted(() => Promise.all([loadGuarantees(), loadFreightTemplates()]))
</script>

<style scoped lang="scss">
  .product-info-tab {
    max-width: 1120px;
    margin: 0 auto;
  }

  .product-info-form :deep(.el-form-item) {
    padding: 12px 0;
    margin-bottom: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .product-info-form :deep(.el-form-item__label) {
    height: auto;
    min-height: 32px;
    line-height: 1.4;
  }

  .product-info-form :deep(.el-form-item__content) {
    min-width: 0;
    line-height: normal;
  }

  .field-label {
    display: inline-flex;
    gap: 5px;
    align-items: center;
  }

  .field-label__help {
    color: var(--el-color-warning);
    cursor: help;
  }

  .media-strip {
    display: flex;
    flex-wrap: wrap;
    gap: 14px;
    align-items: flex-start;
    width: 100%;
  }

  .media-strip__item {
    position: relative;
    flex: none;
  }

  .media-strip__index {
    position: absolute;
    top: 5px;
    left: 5px;
    z-index: 3;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    font-size: 11px;
    color: #fff;
    pointer-events: none;
    background: rgb(0 0 0 / 42%);
    border-radius: 50%;
  }

  .media-strip__add {
    display: flex;
    flex: none;
    flex-direction: column;
    gap: 6px;
    align-items: center;
    justify-content: center;
    width: 112px;
    aspect-ratio: 1;
    padding: 0;
    color: var(--el-text-color-secondary);
    cursor: pointer;
    background: var(--el-fill-color-lighter);
    border: 1px dashed var(--el-border-color);
    border-radius: 8px;
    transition:
      color 0.2s ease,
      border-color 0.2s ease;

    span {
      font-size: 12px;
    }

    &:hover:not(:disabled) {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary);
    }

    &:disabled {
      cursor: not-allowed;
      opacity: 0.6;
    }
  }

  .relation-field__toolbar {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    width: 100%;
  }

  .relation-option {
    display: flex;
    gap: 20px;
    justify-content: space-between;

    small {
      max-width: 280px;
      overflow: hidden;
      color: var(--el-text-color-secondary);
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .dialog-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  @media (width <= 640px) {
    .product-info-form :deep(.el-form-item) {
      display: block;
    }

    .product-info-form :deep(.el-form-item__label) {
      justify-content: flex-start;
      width: auto !important;
      margin-bottom: 8px;
      text-align: left;
    }

    .product-info-form :deep(.el-form-item__content) {
      margin-left: 0 !important;
    }

    .dialog-grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
