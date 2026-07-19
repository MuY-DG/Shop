<template>
  <div class="home-product-page art-full-height" :class="{ 'is-embedded': embedded }">
    <div v-auth="writeAuth" class="auto-fill-toolbar">
      <span>自动补足到</span>
      <ElInputNumber v-model="autoFillTarget" :min="1" :max="50" :precision="0" />
      <span>个</span>
      <ElButton type="primary" plain :loading="autoFilling" @click="handleAutoFill">
        自动填充
      </ElButton>
      <small>保留现有商品；热门与推荐允许重复</small>
    </div>

    <VueDraggable
      v-if="embedded"
      v-model="items"
      v-loading="loading || sorting"
      class="compact-tile-strip"
      draggable=".compact-content-tile"
      direction="horizontal"
      :animation="180"
      :disabled="sorting || !hasAuth(writeAuth)"
      ghost-class="compact-content-tile--ghost"
      chosen-class="compact-content-tile--chosen"
      @start="captureItemOrder"
      @end="handleItemReorder"
    >
      <article
        v-for="item in items"
        :key="item.id"
        class="compact-content-tile"
        :class="{ 'is-disabled': item.status !== 'ENABLED' || item.productStatus !== 'ON_SALE' }"
        title="拖动排序，点击编辑"
      >
        <button
          class="compact-content-tile__media"
          type="button"
          :aria-label="`编辑商品 ${item.productTitle}`"
          @click="openEditor(item)"
        >
          <img :src="item.displayImageUrl" :alt="item.productTitle" />
          <span class="compact-content-tile__disabled-overlay" aria-hidden="true" />
        </button>
        <button
          v-auth="writeAuth"
          class="compact-content-tile__delete"
          type="button"
          :aria-label="`删除商品 ${item.productTitle}`"
          title="删除"
          @click="handleDelete(item)"
        >
          <ArtSvgIcon icon="ri:close-line" />
        </button>
      </article>

      <button
        v-auth="writeAuth"
        class="compact-add-tile"
        type="button"
        aria-label="添加商品"
        title="添加商品"
        @click="openEditor()"
      >
        <ArtSvgIcon icon="ri:add-line" />
      </button>
    </VueDraggable>

    <ElCard v-else>
      <template #header>
        <div class="card-header">
          <div>
            <div class="title">{{ title }}</div>
            <div class="description">选择已上架商品，可选用素材库图片覆盖商品主图</div>
          </div>
          <ElButton type="primary" v-auth="writeAuth" @click="openEditor()">添加商品</ElButton>
        </div>
      </template>

      <ElTable v-loading="loading" :data="items" row-key="id">
        <ElTableColumn label="展示图" width="100">
          <template #default="{ row }">
            <ElImage class="cover" :src="row.displayImageUrl" fit="cover" />
          </template>
        </ElTableColumn>
        <ElTableColumn label="商品" min-width="260">
          <template #default="{ row }">
            <div class="product-title">{{ row.productTitle }}</div>
            <div class="product-meta">{{ row.categoryName }} · SPU {{ row.spuId }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="价格" min-width="150">
          <template #default="{ row }">
            {{ formatPriceRange(row.minPriceCent, row.maxPriceCent) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="图片来源" width="110">
          <template #default="{ row }">
            <ElTag :type="row.imageFileId ? 'warning' : 'info'">
              {{ row.imageFileId ? '自定义' : '商品主图' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="sortOrder" label="排序" width="90" />
        <ElTableColumn label="首页状态" width="110">
          <template #default="{ row }">
            <ElSwitch
              :model-value="row.status === 'ENABLED'"
              inline-prompt
              active-text="展示"
              inactive-text="隐藏"
              :loading="statusUpdatingId === row.id"
              :disabled="!hasAuth(writeAuth)"
              @update:model-value="(enabled) => handleStatusChange(row, enabled)"
            />
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <ElButton link type="primary" v-auth="writeAuth" @click="openEditor(row)">
              编辑
            </ElButton>
            <ElButton link type="danger" v-auth="writeAuth" @click="handleDelete(row)">
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDrawer
      v-model="editorVisible"
      :title="currentItemId ? `编辑${title}` : `添加${title}`"
      size="640px"
      destroy-on-close
    >
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="92px">
        <ElFormItem label="商品" prop="spuId">
          <ElSelect
            v-model="formData.spuId"
            filterable
            remote
            reserve-keyword
            :remote-method="searchProducts"
            :loading="optionLoading"
            placeholder="输入商品名称搜索"
            @visible-change="handleSelectorVisible"
          >
            <ElOption
              v-for="option in productOptions"
              :key="option.id"
              :label="`${option.title}（${option.categoryName}）`"
              :value="option.id"
              :disabled="usedProductIds.has(option.id) && option.id !== editingSpuId"
            >
              <div class="product-option">
                <span>{{ option.title }}</span>
                <span>{{ formatPriceRange(option.minPriceCent, option.maxPriceCent) }}</span>
              </div>
            </ElOption>
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="自定义图片">
          <AssetPicker
            :model-value="{ fileId: formData.imageFileId, url: formData.imageUrl }"
            media-kind="IMAGE"
            allow-clear
            @change="handleImageChange"
          />
          <div class="field-tip">
            不选择时自动使用商品主图。{{ imageGuidance }}，支持 JPG、PNG、WebP
          </div>
        </ElFormItem>
        <ElFormItem label="排序" prop="sortOrder">
          <ElInputNumber v-model="formData.sortOrder" :min="0" :precision="0" />
        </ElFormItem>
        <ElFormItem label="位置角标" prop="badgeMode">
          <ElRadioGroup v-model="formData.badgeMode">
            <ElRadioButton value="AUTO">自动</ElRadioButton>
            <ElRadioButton value="CUSTOM">自定义</ElRadioButton>
            <ElRadioButton value="HIDDEN">不显示</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem v-if="formData.badgeMode === 'CUSTOM'" label="角标文字" prop="customBadgeText">
          <ElInput
            v-model="formData.customBadgeText"
            maxlength="12"
            show-word-limit
            placeholder="例如：店长推荐"
          />
        </ElFormItem>
        <ElFormItem label="状态" prop="status">
          <ElSwitch
            v-model="formData.status"
            inline-prompt
            active-text="展示"
            inactive-text="隐藏"
            active-value="ENABLED"
            inactive-value="DISABLED"
          />
        </ElFormItem>
      </ElForm>

      <template #footer>
        <ElButton @click="editorVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
      </template>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import { VueDraggable } from 'vue-draggable-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import { useAuth } from '@/hooks'
  import {
    createHomeProduct,
    autoFillHomeProducts,
    deleteHomeProduct,
    fetchHomeProductOptions,
    fetchHomeProducts,
    updateHomeProduct
  } from '@/api/content'
  import { formatPriceRange, toHomeProductPayload } from '../home-decoration-state'

  const props = defineProps<{
    section: Api.Content.HomeProductSection
    title: string
    writeAuth: string
    embedded?: boolean
  }>()
  const emit = defineEmits<{
    changed: []
  }>()
  const { hasAuth } = useAuth()

  interface EditorForm extends Api.Content.HomeProductForm {
    imageUrl: string
  }

  const loading = ref(false)
  const submitting = ref(false)
  const optionLoading = ref(false)
  const editorVisible = ref(false)
  const statusUpdatingId = ref<number | null>(null)
  const sorting = ref(false)
  const autoFilling = ref(false)
  const autoFillTarget = ref(props.section === 'HOT' ? 3 : 4)
  const currentItemId = ref<number | null>(null)
  const editingSpuId = ref<number | null>(null)
  const items = ref<Api.Content.HomeProductItem[]>([])
  const productOptions = ref<Api.Content.HomeProductOption[]>([])
  const formRef = ref<FormInstance>()
  let itemOrderSnapshot: Api.Content.HomeProductItem[] = []

  const defaultForm = (): EditorForm => ({
    spuId: null,
    imageFileId: null,
    imageUrl: '',
    sortOrder: 0,
    status: 'ENABLED',
    badgeMode: 'AUTO',
    customBadgeText: ''
  })
  const formData = reactive<EditorForm>(defaultForm())
  const usedProductIds = computed(() => new Set(items.value.map((item) => item.spuId)))
  const imageGuidance = computed(() =>
    props.section === 'HOT'
      ? '建议使用 1:1 图片，推荐 800 × 800 px'
      : '建议使用约 1.23:1 横图，推荐 1200 × 980 px'
  )
  const rules: FormRules<EditorForm> = {
    spuId: [{ required: true, message: '请选择商品', trigger: 'change' }],
    customBadgeText: [
      {
        validator: (_rule, value, callback) => {
          if (formData.badgeMode === 'CUSTOM' && !String(value || '').trim()) {
            callback(new Error('请输入自定义角标文字'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ]
  }

  const loadItems = async () => {
    loading.value = true
    try {
      items.value = await fetchHomeProducts(props.section)
    } finally {
      loading.value = false
    }
  }

  const captureItemOrder = () => {
    itemOrderSnapshot = [...items.value]
  }

  const handleItemReorder = async () => {
    const reordered = items.value.map((item, index) => ({ ...item, sortOrder: index }))
    const changed = reordered.filter((item, index) => itemOrderSnapshot[index]?.id !== item.id)
    if (!changed.length) return

    items.value = reordered
    sorting.value = true
    try {
      await Promise.all(
        changed.map((item) =>
          updateHomeProduct(
            props.section,
            item.id,
            toHomeProductPayload({
              spuId: item.spuId,
              imageFileId: item.imageFileId ?? null,
              sortOrder: item.sortOrder,
              status: item.status,
              badgeMode: item.badgeMode,
              customBadgeText: item.customBadgeText
            }),
            false
          )
        )
      )
      ElMessage.success(`${props.title}排序已更新`)
      await loadItems()
      emit('changed')
    } catch {
      items.value = itemOrderSnapshot
      await loadItems()
    } finally {
      sorting.value = false
      itemOrderSnapshot = []
    }
  }

  const searchProducts = async (keyword = '') => {
    optionLoading.value = true
    try {
      const response = await fetchHomeProductOptions({
        keyword: keyword.trim(),
        current: 1,
        size: 50
      })
      productOptions.value = response.records
    } finally {
      optionLoading.value = false
    }
  }

  const handleSelectorVisible = (visible: boolean) => {
    if (visible && productOptions.value.length === 0) searchProducts()
  }

  const openEditor = async (item?: Api.Content.HomeProductItem) => {
    currentItemId.value = item?.id ?? null
    editingSpuId.value = item?.spuId ?? null
    Object.assign(formData, defaultForm())
    if (item) {
      Object.assign(formData, {
        spuId: item.spuId,
        imageFileId: item.imageFileId ?? null,
        imageUrl: item.imageUrl || '',
        sortOrder: item.sortOrder,
        status: item.status,
        badgeMode: item.badgeMode,
        customBadgeText: item.customBadgeText
      })
    }
    editorVisible.value = true
    await searchProducts(item?.productTitle || '')
    if (item && !productOptions.value.some((option) => option.id === item.spuId)) {
      productOptions.value.unshift({
        id: item.spuId,
        categoryId: 0,
        categoryName: item.categoryName,
        title: item.productTitle,
        subtitle: item.productSubtitle,
        mainImage: item.productImageUrl,
        minPriceCent: item.minPriceCent,
        maxPriceCent: item.maxPriceCent
      })
    }
    requestAnimationFrame(() => formRef.value?.clearValidate())
  }

  const handleImageChange = (value: Api.Common.AssetValue) => {
    formData.imageFileId = value.fileId
    formData.imageUrl = value.url
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)
    if (!valid) return
    submitting.value = true
    try {
      const payload = toHomeProductPayload(formData)
      if (currentItemId.value) {
        await updateHomeProduct(props.section, currentItemId.value, payload)
      } else {
        await createHomeProduct(props.section, payload)
      }
      editorVisible.value = false
      await loadItems()
      emit('changed')
    } finally {
      submitting.value = false
    }
  }

  const handleStatusChange = async (
    item: Api.Content.HomeProductItem,
    enabled: string | number | boolean
  ) => {
    const isEnabled = enabled === true
    if ((item.status === 'ENABLED') === isEnabled || statusUpdatingId.value === item.id) return
    statusUpdatingId.value = item.id
    try {
      await updateHomeProduct(
        props.section,
        item.id,
        toHomeProductPayload({
          spuId: item.spuId,
          imageFileId: item.imageFileId ?? null,
          sortOrder: item.sortOrder,
          status: isEnabled ? 'ENABLED' : 'DISABLED',
          badgeMode: item.badgeMode,
          customBadgeText: item.customBadgeText
        })
      )
      await loadItems()
      emit('changed')
    } finally {
      statusUpdatingId.value = null
    }
  }

  const handleDelete = async (item: Api.Content.HomeProductItem) => {
    await ElMessageBox.confirm(`确定从${props.title}中删除“${item.productTitle}”吗？`, '删除确认', {
      type: 'warning'
    })
    await deleteHomeProduct(props.section, item.id)
    await loadItems()
    emit('changed')
  }

  const handleAutoFill = async () => {
    autoFilling.value = true
    try {
      const result = await autoFillHomeProducts(props.section, {
        targetCount: autoFillTarget.value
      })
      if (result.addedCount > 0) {
        ElMessage.success(`已自动添加 ${result.addedCount} 个商品，当前共 ${result.finalCount} 个`)
      } else if (result.insufficient) {
        ElMessage.warning(`暂无足够的合格商品，当前共 ${result.finalCount} 个`)
      } else {
        ElMessage.info(`当前已达到 ${result.finalCount} 个，无需补充`)
      }
      await loadItems()
      emit('changed')
    } finally {
      autoFilling.value = false
    }
  }

  onMounted(loadItems)
</script>

<style scoped lang="scss">
  .home-product-page.is-embedded {
    height: auto;
    min-height: 0;
  }

  .auto-fill-toolbar {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-bottom: 12px;
    color: var(--el-text-color-regular);

    .el-input-number {
      width: 112px;
    }

    small {
      color: var(--el-text-color-secondary);
    }
  }

  .compact-tile-strip {
    display: grid;
    grid-auto-columns: 136px;
    grid-auto-flow: column;
    gap: 12px;
    padding: 1px 1px 6px;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .compact-add-tile,
  .compact-content-tile {
    width: 136px;
    aspect-ratio: 1;
    overflow: hidden;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 14px;
  }

  .compact-add-tile {
    display: grid;
    place-items: center;
    font: inherit;
    font-size: 27px;
    color: var(--el-text-color-placeholder);
    cursor: pointer;
    background: var(--el-fill-color-extra-light);
    border-style: dashed;
    transition:
      background 0.18s ease,
      border-color 0.18s ease,
      transform 0.18s ease;

    &:hover {
      color: var(--el-color-primary-light-3);
      background: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary-light-5);
      transform: translateY(-2px);
    }
  }

  .compact-content-tile {
    position: relative;
    background: var(--el-bg-color);
    box-shadow: 0 6px 18px rgb(24 40 72 / 5%);
    transition:
      box-shadow 0.18s ease,
      transform 0.18s ease;

    &:hover {
      box-shadow: 0 9px 24px rgb(24 40 72 / 13%);
      transform: translateY(-2px);
    }

    &--chosen {
      cursor: grabbing;
    }

    &--ghost {
      opacity: 0.3;
    }

    &__media {
      position: relative;
      display: block;
      width: 100%;
      height: 100%;
      padding: 0;
      overflow: hidden;
      cursor: pointer;
      background: var(--el-fill-color-light);
      border: 0;

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }
    }

    &__disabled-overlay {
      position: absolute;
      inset: 0;
      pointer-events: none;
      background: rgb(15 23 42 / 30%);
      opacity: 0;
      transition: opacity 0.18s ease;
    }

    &.is-disabled &__disabled-overlay {
      opacity: 1;
    }

    &__delete {
      position: absolute;
      top: 7px;
      right: 7px;
      z-index: 3;
      display: grid;
      place-items: center;
      width: 24px;
      height: 24px;
      padding: 0;
      font-size: 16px;
      color: #fff;
      cursor: pointer;
      background: rgb(20 25 35 / 58%);
      border: 0;
      border-radius: 50%;
      opacity: 0;
      transition:
        background 0.18s ease,
        opacity 0.18s ease,
        transform 0.18s ease;

      &:hover,
      &:focus-visible {
        background: var(--el-color-danger);
        opacity: 1;
        transform: scale(1.06);
      }
    }

    &:hover &__delete,
    &:focus-within &__delete {
      opacity: 1;
    }
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .title,
  .product-title {
    font-weight: 600;
  }

  .description,
  .product-meta,
  .field-tip {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .cover {
    width: 56px;
    height: 56px;
    border-radius: 8px;
  }

  .product-option {
    display: flex;
    gap: 24px;
    justify-content: space-between;
  }
</style>
