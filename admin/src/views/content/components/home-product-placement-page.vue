<template>
  <div class="home-product-page art-full-height" :class="{ 'is-embedded': embedded }">
    <div v-if="embedded" v-loading="loading" class="compact-tile-strip">
      <button v-auth="writeAuth" class="compact-add-tile" type="button" @click="openEditor()">
        <span class="compact-add-tile__icon"><ArtSvgIcon icon="ri:add-line" /></span>
        <strong>添加商品</strong>
        <small>选择已上架商品</small>
      </button>

      <article v-for="item in items" :key="item.id" class="compact-content-tile">
        <button
          class="compact-content-tile__media"
          type="button"
          :aria-label="`编辑商品 ${item.productTitle}`"
          @click="openEditor(item)"
        >
          <img :src="item.displayImageUrl" :alt="item.productTitle" />
          <span v-if="item.imageFileId" class="compact-content-tile__badge">自定义图</span>
        </button>
        <div class="compact-content-tile__body">
          <strong>{{ item.productTitle }}</strong>
          <small>{{ formatPriceRange(item.minPriceCent, item.maxPriceCent) }}</small>
          <div class="compact-content-tile__footer">
            <ElSwitch
              :model-value="item.status === 'ENABLED'"
              inline-prompt
              active-text="展示"
              inactive-text="隐藏"
              :loading="statusUpdatingId === item.id"
              :disabled="!hasAuth(writeAuth)"
              @update:model-value="(enabled) => handleStatusChange(item, enabled)"
            />
            <span class="compact-content-tile__actions">
              <ElButton
                circle
                text
                aria-label="编辑商品"
                v-auth="writeAuth"
                @click="openEditor(item)"
              >
                <ArtSvgIcon icon="ri:edit-2-line" />
              </ElButton>
              <ElButton
                circle
                text
                type="danger"
                aria-label="删除商品"
                v-auth="writeAuth"
                @click="handleDelete(item)"
              >
                <ArtSvgIcon icon="ri:delete-bin-6-line" />
              </ElButton>
            </span>
          </div>
        </div>
      </article>
    </div>

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
          <div class="field-tip">不选择时自动使用商品主图</div>
        </ElFormItem>
        <ElFormItem label="排序" prop="sortOrder">
          <ElInputNumber v-model="formData.sortOrder" :min="0" :precision="0" />
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
  import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import { useAuth } from '@/hooks'
  import {
    createHomeProduct,
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
  const currentItemId = ref<number | null>(null)
  const editingSpuId = ref<number | null>(null)
  const items = ref<Api.Content.HomeProductItem[]>([])
  const productOptions = ref<Api.Content.HomeProductOption[]>([])
  const formRef = ref<FormInstance>()

  const defaultForm = (): EditorForm => ({
    spuId: null,
    imageFileId: null,
    imageUrl: '',
    sortOrder: 0,
    status: 'ENABLED'
  })
  const formData = reactive<EditorForm>(defaultForm())
  const usedProductIds = computed(() => new Set(items.value.map((item) => item.spuId)))
  const rules: FormRules<EditorForm> = {
    spuId: [{ required: true, message: '请选择商品', trigger: 'change' }]
  }

  const loadItems = async () => {
    loading.value = true
    try {
      items.value = await fetchHomeProducts(props.section)
    } finally {
      loading.value = false
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
        status: item.status
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
          status: isEnabled ? 'ENABLED' : 'DISABLED'
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

  onMounted(loadItems)
</script>

<style scoped lang="scss">
  .home-product-page.is-embedded {
    height: auto;
    min-height: 0;
  }

  .compact-tile-strip {
    display: grid;
    grid-auto-columns: 164px;
    grid-auto-flow: column;
    gap: 12px;
    padding: 1px 1px 6px;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .compact-add-tile,
  .compact-content-tile {
    min-height: 190px;
    overflow: hidden;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 14px;
  }

  .compact-add-tile {
    display: grid;
    gap: 5px;
    place-items: center;
    align-content: center;
    font: inherit;
    color: var(--el-color-primary);
    cursor: pointer;
    border-style: dashed;
    transition:
      background 0.18s ease,
      border-color 0.18s ease,
      transform 0.18s ease;

    &:hover {
      background: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary-light-5);
      transform: translateY(-2px);
    }

    &__icon {
      display: grid;
      place-items: center;
      width: 40px;
      height: 40px;
      font-size: 22px;
      background: var(--el-color-primary-light-8);
      border-radius: 12px;
    }

    strong {
      font-size: 13px;
    }

    small {
      font-size: 10px;
      color: var(--el-text-color-secondary);
    }
  }

  .compact-content-tile {
    display: grid;
    grid-template-rows: 100px minmax(0, 1fr);
    background: var(--el-bg-color);
    box-shadow: 0 6px 18px rgb(24 40 72 / 5%);

    &__media {
      position: relative;
      display: block;
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

    &__badge {
      position: absolute;
      top: 6px;
      right: 6px;
      padding: 2px 6px;
      font-size: 9px;
      color: #9a6715;
      background: rgb(255 246 217 / 92%);
      border-radius: 999px;
    }

    &__body {
      display: grid;
      grid-template-rows: auto auto 1fr;
      min-width: 0;
      padding: 9px 10px 7px;

      > strong,
      > small {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      > strong {
        font-size: 12px;
      }

      > small {
        margin-top: 2px;
        font-size: 10px;
        font-weight: 600;
        color: var(--el-color-danger);
      }
    }

    &__footer {
      display: flex;
      align-items: end;
      justify-content: space-between;
      min-width: 0;
      margin-top: 6px;

      :deep(.el-switch) {
        --el-switch-width: 42px;
      }
    }

    &__actions {
      display: flex;
      gap: 1px;

      :deep(.el-button) {
        width: 25px;
        height: 25px;
        margin: 0;
      }
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
