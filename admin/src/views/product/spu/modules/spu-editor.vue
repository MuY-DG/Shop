<template>
  <ElDrawer
    :model-value="visible"
    :title="drawerTitle"
    size="960px"
    destroy-on-close
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-loading="loading" class="spu-editor">
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="96px">
        <ElRow :gutter="16">
          <ElCol :xs="24" :md="12">
            <ElFormItem label="商品分类" prop="categoryId">
              <ElTreeSelect
                v-model="formData.categoryId"
                :data="categoryTreeOptions"
                node-key="value"
                check-strictly
                clearable
                default-expand-all
                :render-after-expand="false"
                placeholder="请选择分类"
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
          <ElCol :xs="24" :md="12">
            <ElFormItem label="商品标题" prop="title">
              <ElInput v-model="formData.title" maxlength="80" placeholder="请输入商品标题" />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="副标题" prop="subtitle">
              <ElInput v-model="formData.subtitle" maxlength="120" placeholder="请输入副标题" />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24">
            <ElFormItem label="主图地址" prop="mainImage">
              <ElInput v-model="formData.mainImage" placeholder="请输入主图 URL" />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24">
            <ElFormItem label="卖点" prop="sellingPoints">
              <ElInput
                v-model="formData.sellingPoints"
                type="textarea"
                :rows="3"
                maxlength="300"
                show-word-limit
                placeholder="请输入商品卖点"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24">
            <ElFormItem label="详情 HTML" prop="detailHtml">
              <ElInput
                v-model="formData.detailHtml"
                type="textarea"
                :rows="6"
                placeholder="请输入详情 HTML"
              />
            </ElFormItem>
          </ElCol>
        </ElRow>

        <ElDivider content-position="left">图集</ElDivider>
        <ElSpace direction="vertical" fill style="width: 100%">
          <div v-for="(image, index) in formData.images" :key="`image-${index}`" class="image-row">
            <ElInput v-model="formData.images[index]" placeholder="请输入图片 URL" />
            <ElButton text type="danger" @click="removeImage(index)">删除</ElButton>
          </div>
          <ElButton @click="addImage" plain>新增图片</ElButton>
        </ElSpace>

        <ElDivider content-position="left">
          <div class="section-title">
            <span>SKU 列表</span>
            <ElButton size="small" @click="addSku" plain>新增 SKU</ElButton>
          </div>
        </ElDivider>

        <ElTable :data="formData.skus" border class="sku-table">
          <ElTableColumn label="SKU 编码" min-width="140">
            <template #default="{ row }">
              <ElInput v-model="row.skuCode" placeholder="编码" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="规格名" min-width="140">
            <template #default="{ row }">
              <ElInput v-model="row.specText" placeholder="如：黑色 / L" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="规格 JSON" min-width="180">
            <template #default="{ row }">
              <ElInput v-model="row.specJson" placeholder='如：{"color":"black"}' />
            </template>
          </ElTableColumn>
          <ElTableColumn label="售价(分)" width="120">
            <template #default="{ row }">
              <ElInputNumber
                v-model="row.priceCent"
                :min="1"
                :precision="0"
                controls-position="right"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn label="原价(分)" width="120">
            <template #default="{ row }">
              <ElInputNumber
                v-model="row.originalPriceCent"
                :min="0"
                :precision="0"
                controls-position="right"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn label="库存" width="120">
            <template #default="{ row }">
              <ElInputNumber
                v-model="row.stockAvailable"
                :min="0"
                :precision="0"
                controls-position="right"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn label="重量(g)" width="120">
            <template #default="{ row }">
              <ElInputNumber
                v-model="row.weightGram"
                :min="0"
                :precision="0"
                controls-position="right"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn label="图片" min-width="160">
            <template #default="{ row }">
              <ElInput v-model="row.image" placeholder="图片 URL" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="110">
            <template #default="{ row }">
              <ElSelect v-model="row.status">
                <ElOption label="启用" value="ENABLED" />
                <ElOption label="禁用" value="DISABLED" />
              </ElSelect>
            </template>
          </ElTableColumn>
          <ElTableColumn label="排序" width="110">
            <template #default="{ row }">
              <ElInputNumber
                v-model="row.sortOrder"
                :min="0"
                :precision="0"
                controls-position="right"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="80" fixed="right">
            <template #default="{ $index }">
              <ElButton text type="danger" @click="removeSku($index)">删除</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
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
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import { createProductSpu, fetchProductSpuDetail, updateProductSpu } from '@/api/product'

  interface TreeOption {
    value: number
    label: string
    children?: TreeOption[]
  }

  interface Props {
    visible: boolean
    spuId?: number | null
    categories: Api.Product.Category[]
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'success'): void
  }

  const props = withDefaults(defineProps<Props>(), {
    visible: false,
    spuId: null,
    categories: () => []
  })

  const emit = defineEmits<Emits>()

  const formRef = ref<FormInstance>()
  const loading = ref(false)
  const submitting = ref(false)

  const createEmptySku = (): Api.Product.Sku => ({
    skuCode: '',
    specJson: '',
    specText: '',
    priceCent: 1,
    originalPriceCent: 0,
    stockAvailable: 0,
    weightGram: 0,
    image: '',
    status: 'ENABLED',
    sortOrder: 0
  })

  const createDefaultForm = (): Api.Product.SpuForm => ({
    categoryId: 0,
    title: '',
    subtitle: '',
    mainImage: '',
    sellingPoints: '',
    detailHtml: '',
    sortOrder: 0,
    images: [''],
    skus: [createEmptySku()]
  })

  const formData = reactive<Api.Product.SpuForm>(createDefaultForm())

  const categoryTreeOptions = computed<TreeOption[]>(() => {
    const buildTree = (categories: Api.Product.Category[]): TreeOption[] =>
      categories.map((item) => ({
        value: item.id,
        label: item.name,
        children: buildTree(item.children || [])
      }))
    return buildTree(props.categories)
  })

  const drawerTitle = computed(() => (props.spuId ? '编辑商品' : '新增商品'))

  const rules: FormRules<Api.Product.SpuForm> = {
    categoryId: [
      {
        validator: (_rule, value, callback) => {
          if (!value || value <= 0) {
            callback(new Error('请选择商品分类'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    title: [{ required: true, message: '请输入商品标题', trigger: 'blur' }],
    mainImage: [{ required: true, message: '请输入主图地址', trigger: 'blur' }]
  }

  const resetFormData = () => {
    Object.assign(formData, createDefaultForm())
  }

  const fillForm = (detail?: Api.Product.SpuDetail) => {
    resetFormData()
    if (!detail) return
    Object.assign(formData, {
      categoryId: detail.categoryId,
      title: detail.title,
      subtitle: detail.subtitle,
      mainImage: detail.mainImage,
      sellingPoints: detail.sellingPoints,
      detailHtml: detail.detailHtml,
      sortOrder: detail.sortOrder,
      images: detail.images?.length ? detail.images.map((item) => item.url) : [''],
      skus: detail.skus?.length ? detail.skus.map((item) => ({ ...item })) : [createEmptySku()]
    })
  }

  const loadDetail = async () => {
    if (!props.visible) return

    if (!props.spuId) {
      fillForm()
      nextTick(() => formRef.value?.clearValidate())
      return
    }

    loading.value = true
    try {
      const detail = await fetchProductSpuDetail(props.spuId)
      fillForm(detail)
      nextTick(() => formRef.value?.clearValidate())
    } finally {
      loading.value = false
    }
  }

  watch(
    () => [props.visible, props.spuId],
    (value) => {
      const visible = value[0] as boolean
      if (!visible) return
      loadDetail()
    },
    { immediate: true }
  )

  const addImage = () => {
    formData.images.push('')
  }

  const removeImage = (index: number) => {
    if (formData.images.length === 1) {
      formData.images[0] = ''
      return
    }
    formData.images.splice(index, 1)
  }

  const addSku = () => {
    formData.skus.push(createEmptySku())
  }

  const removeSku = (index: number) => {
    if (formData.skus.length === 1) {
      formData.skus.splice(0, 1, createEmptySku())
      return
    }
    formData.skus.splice(index, 1)
  }

  const validateSkus = () => {
    if (!formData.skus.length) {
      ElMessage.error('请至少配置一个 SKU')
      return false
    }

    const invalidSku = formData.skus.find(
      (item: Api.Product.Sku) =>
        !item.skuCode.trim() ||
        !item.specText.trim() ||
        !item.specJson.trim() ||
        item.priceCent < 1 ||
        item.originalPriceCent < 0 ||
        item.stockAvailable < 0 ||
        item.weightGram < 0 ||
        item.sortOrder < 0
    )

    if (invalidSku) {
      ElMessage.error('请完整填写 SKU 信息')
      return false
    }

    return true
  }

  const buildPayload = (): Api.Product.SpuForm => ({
    categoryId: formData.categoryId,
    title: formData.title.trim(),
    subtitle: formData.subtitle.trim(),
    mainImage: formData.mainImage.trim(),
    sellingPoints: formData.sellingPoints.trim(),
    detailHtml: formData.detailHtml,
    sortOrder: formData.sortOrder,
    images: formData.images.map((item) => item.trim()).filter(Boolean),
    skus: formData.skus.map((item: Api.Product.Sku) => ({
      ...item,
      skuCode: item.skuCode.trim(),
      specText: item.specText.trim(),
      specJson: item.specJson.trim(),
      image: item.image.trim()
    }))
  })

  const handleSubmit = async () => {
    if (!formRef.value) return

    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)

    if (!valid || !validateSkus()) return

    const payload = buildPayload()
    submitting.value = true

    try {
      if (props.spuId) {
        await updateProductSpu(props.spuId, payload)
      } else {
        await createProductSpu(payload)
      }
      emit('update:visible', false)
      emit('success')
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped lang="scss">
  .spu-editor {
    padding-right: 8px;
  }

  .image-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 12px;
    align-items: center;
  }

  .section-title {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .sku-table {
    width: 100%;
  }

  .drawer-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
  }
</style>
