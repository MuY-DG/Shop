<template>
  <div class="home-category-page art-full-height" :class="{ 'is-embedded': embedded }">
    <div v-if="embedded" v-loading="loading" class="compact-tile-strip">
      <button
        v-auth="'content:home-category:write'"
        class="compact-add-tile"
        type="button"
        @click="openEditor()"
      >
        <span class="compact-add-tile__icon"><ArtSvgIcon icon="ri:add-line" /></span>
        <strong>新增分类</strong>
        <small>添加快捷入口</small>
      </button>

      <article v-for="item in items" :key="item.id" class="compact-content-tile">
        <button
          class="compact-content-tile__media"
          type="button"
          :aria-label="`编辑分类 ${item.categoryName}`"
          @click="openEditor(item)"
        >
          <img :src="item.imageUrl" :alt="item.categoryName" />
          <span v-if="item.categoryStatus !== 'ENABLED'" class="compact-content-tile__warning">
            分类已禁用
          </span>
        </button>
        <div class="compact-content-tile__body">
          <strong>{{ item.categoryName }}</strong>
          <small>排序 {{ item.sortOrder }}</small>
          <div class="compact-content-tile__footer">
            <ElSwitch
              :model-value="item.status === 'ENABLED'"
              inline-prompt
              active-text="展示"
              inactive-text="隐藏"
              :loading="statusUpdatingId === item.id"
              :disabled="!hasAuth('content:home-category:write')"
              @update:model-value="(enabled) => handleStatusChange(item, enabled)"
            />
            <span class="compact-content-tile__actions">
              <ElButton
                circle
                text
                aria-label="编辑分类"
                v-auth="'content:home-category:write'"
                @click="openEditor(item)"
              >
                <ArtSvgIcon icon="ri:edit-2-line" />
              </ElButton>
              <ElButton
                circle
                text
                type="danger"
                aria-label="删除分类"
                v-auth="'content:home-category:write'"
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
            <ElTooltip content="商城分类状态请前往商品分类中修改" placement="top">
              <ElSwitch
                :model-value="row.categoryStatus === 'ENABLED'"
                inline-prompt
                active-text="启用"
                inactive-text="禁用"
                disabled
              />
            </ElTooltip>
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
              :disabled="!hasAuth('content:home-category:write')"
              @update:model-value="(enabled) => handleStatusChange(row, enabled)"
            />
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
          <ElCascader
            v-model="formData.categoryId"
            :options="categoryTreeOptions"
            :props="categoryCascaderProps"
            filterable
            clearable
            :show-all-levels="true"
            placeholder="请选择分类"
            style="width: 100%"
          />
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
    createHomeCategory,
    deleteHomeCategory,
    fetchHomeCategories,
    fetchHomeCategoryOptions,
    updateHomeCategory
  } from '@/api/content'
  import { toHomeCategoryPayload } from '../home-decoration-state'

  defineOptions({ name: 'ContentHomeCategory' })

  const { embedded = false } = defineProps<{
    embedded?: boolean
  }>()
  const emit = defineEmits<{
    changed: []
  }>()
  const { hasAuth } = useAuth()

  interface EditorForm extends Api.Content.HomeCategoryForm {
    imageUrl: string
  }

  interface CategoryTreeOption {
    [key: string]: unknown
    value: number
    label: string
    disabled?: boolean
    children?: CategoryTreeOption[]
  }

  const loading = ref(false)
  const submitting = ref(false)
  const editorVisible = ref(false)
  const statusUpdatingId = ref<number | null>(null)
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
  const categoryCascaderProps = {
    emitPath: false,
    checkStrictly: true,
    expandTrigger: 'hover'
  } as const
  const categoryTreeOptions = computed<CategoryTreeOption[]>(() => {
    const childrenByParent = new Map<number, Api.Content.HomeCategoryOption[]>()
    categoryOptions.value.forEach((option) => {
      const siblings = childrenByParent.get(option.parentId) || []
      siblings.push(option)
      childrenByParent.set(option.parentId, siblings)
    })

    const buildChildren = (parentId: number): CategoryTreeOption[] =>
      (childrenByParent.get(parentId) || []).map((option) => {
        const children = buildChildren(option.id)
        return {
          value: option.id,
          label: option.name,
          disabled: usedCategoryIds.value.has(option.id) && option.id !== editingCategoryId.value,
          children: children.length ? children : undefined
        }
      })

    return buildChildren(0)
  })
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
      emit('changed')
    } finally {
      submitting.value = false
    }
  }

  const handleStatusChange = async (
    item: Api.Content.HomeCategoryItem,
    enabled: string | number | boolean
  ) => {
    const isEnabled = enabled === true
    if ((item.status === 'ENABLED') === isEnabled || statusUpdatingId.value === item.id) return
    statusUpdatingId.value = item.id
    try {
      await updateHomeCategory(
        item.id,
        toHomeCategoryPayload({
          categoryId: item.categoryId,
          imageFileId: item.imageFileId,
          sortOrder: item.sortOrder,
          status: isEnabled ? 'ENABLED' : 'DISABLED'
        })
      )
      await loadData()
      emit('changed')
    } finally {
      statusUpdatingId.value = null
    }
  }

  const handleDelete = async (item: Api.Content.HomeCategoryItem) => {
    await ElMessageBox.confirm(`确定删除首页分类“${item.categoryName}”吗？`, '删除确认', {
      type: 'warning'
    })
    await deleteHomeCategory(item.id)
    await loadData()
    emit('changed')
  }

  onMounted(loadData)
</script>

<style scoped lang="scss">
  .home-category-page.is-embedded {
    height: auto;
    min-height: 0;
  }

  .compact-tile-strip {
    display: grid;
    grid-auto-columns: 156px;
    grid-auto-flow: column;
    gap: 12px;
    padding: 1px 1px 6px;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .compact-add-tile,
  .compact-content-tile {
    min-height: 184px;
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
    grid-template-rows: 92px minmax(0, 1fr);
    background: var(--el-bg-color);
    box-shadow: 0 6px 18px rgb(24 40 72 / 5%);

    &__media {
      position: relative;
      display: grid;
      place-items: center;
      padding: 8px;
      overflow: hidden;
      cursor: pointer;
      background: var(--el-fill-color-light);
      border: 0;

      img {
        width: 74px;
        height: 74px;
        object-fit: contain;
      }
    }

    &__warning {
      position: absolute;
      right: 6px;
      bottom: 6px;
      padding: 2px 5px;
      font-size: 9px;
      color: var(--el-color-danger);
      background: var(--el-color-danger-light-9);
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
        color: var(--el-text-color-secondary);
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
