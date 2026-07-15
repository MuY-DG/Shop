<template>
  <div class="home-product-page art-full-height">
    <ElCard>
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
            <ElTag :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? '展示' : '隐藏' }}
            </ElTag>
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
          <ElRadioGroup v-model="formData.status">
            <ElRadioButton value="ENABLED">展示</ElRadioButton>
            <ElRadioButton value="DISABLED">隐藏</ElRadioButton>
          </ElRadioGroup>
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
  }>()

  interface EditorForm extends Api.Content.HomeProductForm {
    imageUrl: string
  }

  const loading = ref(false)
  const submitting = ref(false)
  const optionLoading = ref(false)
  const editorVisible = ref(false)
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
    } finally {
      submitting.value = false
    }
  }

  const handleDelete = async (item: Api.Content.HomeProductItem) => {
    await ElMessageBox.confirm(`确定从${props.title}中删除“${item.productTitle}”吗？`, '删除确认', {
      type: 'warning'
    })
    await deleteHomeProduct(props.section, item.id)
    await loadItems()
  }

  onMounted(loadItems)
</script>

<style scoped lang="scss">
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
    justify-content: space-between;
    gap: 24px;
  }
</style>
