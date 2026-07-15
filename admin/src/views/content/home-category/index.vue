<template>
  <div class="home-category-page art-full-height">
    <ElCard>
      <template #header>
        <div class="card-header">
          <div>
            <div class="title">首页分类</div>
            <div class="description">选择商城分类并配置首页展示图片与顺序</div>
          </div>
          <ElButton type="primary" v-auth="'content:home-category:write'" @click="openEditor()">
            新增分类
          </ElButton>
        </div>
      </template>

      <ElTable v-loading="loading" :data="items" row-key="id">
        <ElTableColumn label="图片" width="100">
          <template #default="{ row }">
            <ElImage class="cover" :src="row.imageUrl" fit="cover" />
          </template>
        </ElTableColumn>
        <ElTableColumn prop="categoryName" label="分类" min-width="180" />
        <ElTableColumn label="分类状态" width="110">
          <template #default="{ row }">
            <ElTag :type="row.categoryStatus === 'ENABLED' ? 'success' : 'info'">
              {{ row.categoryStatus === 'ENABLED' ? '启用' : '禁用' }}
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
            <ElButton
              link
              type="primary"
              v-auth="'content:home-category:write'"
              @click="openEditor(row)"
            >
              编辑
            </ElButton>
            <ElButton
              link
              type="danger"
              v-auth="'content:home-category:write'"
              @click="handleDelete(row)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDrawer
      v-model="editorVisible"
      :title="currentItemId ? '编辑首页分类' : '新增首页分类'"
      size="640px"
      destroy-on-close
    >
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="92px">
        <ElFormItem label="商城分类" prop="categoryId">
          <ElSelect v-model="formData.categoryId" filterable placeholder="请选择分类">
            <ElOption
              v-for="option in categoryOptions"
              :key="option.id"
              :label="option.name"
              :value="option.id"
              :disabled="usedCategoryIds.has(option.id) && option.id !== editingCategoryId"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="展示图片" prop="imageFileId">
          <AssetPicker
            :model-value="{ fileId: formData.imageFileId, url: formData.imageUrl }"
            media-kind="IMAGE"
            @change="handleImageChange"
          />
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
    createHomeCategory,
    deleteHomeCategory,
    fetchHomeCategories,
    fetchHomeCategoryOptions,
    updateHomeCategory
  } from '@/api/content'
  import { toHomeCategoryPayload } from '../home-decoration-state'

  defineOptions({ name: 'ContentHomeCategory' })

  interface EditorForm extends Api.Content.HomeCategoryForm {
    imageUrl: string
  }

  const loading = ref(false)
  const submitting = ref(false)
  const editorVisible = ref(false)
  const currentItemId = ref<number | null>(null)
  const editingCategoryId = ref<number | null>(null)
  const items = ref<Api.Content.HomeCategoryItem[]>([])
  const categoryOptions = ref<Api.Content.HomeCategoryOption[]>([])
  const formRef = ref<FormInstance>()

  const defaultForm = (): EditorForm => ({
    categoryId: null,
    imageFileId: null,
    imageUrl: '',
    sortOrder: 0,
    status: 'ENABLED'
  })
  const formData = reactive<EditorForm>(defaultForm())
  const usedCategoryIds = computed(() => new Set(items.value.map((item) => item.categoryId)))
  const rules: FormRules<EditorForm> = {
    categoryId: [{ required: true, message: '请选择商城分类', trigger: 'change' }],
    imageFileId: [{ required: true, message: '请选择展示图片', trigger: 'change' }]
  }

  const loadData = async () => {
    loading.value = true
    try {
      const [categoryItems, options] = await Promise.all([
        fetchHomeCategories(),
        fetchHomeCategoryOptions()
      ])
      items.value = categoryItems
      categoryOptions.value = options
    } finally {
      loading.value = false
    }
  }

  const openEditor = (item?: Api.Content.HomeCategoryItem) => {
    currentItemId.value = item?.id ?? null
    editingCategoryId.value = item?.categoryId ?? null
    Object.assign(formData, defaultForm())
    if (item) {
      Object.assign(formData, {
        categoryId: item.categoryId,
        imageFileId: item.imageFileId,
        imageUrl: item.imageUrl,
        sortOrder: item.sortOrder,
        status: item.status
      })
    }
    editorVisible.value = true
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
      const payload = toHomeCategoryPayload(formData)
      if (currentItemId.value) await updateHomeCategory(currentItemId.value, payload)
      else await createHomeCategory(payload)
      editorVisible.value = false
      await loadData()
    } finally {
      submitting.value = false
    }
  }

  const handleDelete = async (item: Api.Content.HomeCategoryItem) => {
    await ElMessageBox.confirm(`确定删除首页分类“${item.categoryName}”吗？`, '删除确认', {
      type: 'warning'
    })
    await deleteHomeCategory(item.id)
    await loadData()
  }

  onMounted(loadData)
</script>

<style scoped lang="scss">
  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .title {
    font-size: 16px;
    font-weight: 600;
  }

  .description {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .cover {
    width: 56px;
    height: 56px;
    border-radius: 8px;
  }
</style>
