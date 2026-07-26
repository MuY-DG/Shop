<template>
  <div class="home-category-page art-full-height" :class="{ 'is-embedded': embedded }">
    <VueDraggable
      v-if="embedded"
      v-model="items"
      v-loading="loading || sorting"
      class="compact-tile-strip"
      draggable=".compact-content-tile"
      direction="horizontal"
      :animation="180"
      :disabled="sorting || !hasAuth('content:home-category:write')"
      ghost-class="compact-content-tile--ghost"
      chosen-class="compact-content-tile--chosen"
      @start="captureItemOrder"
      @end="handleItemReorder"
    >
      <article
        v-for="item in items"
        :key="item.id"
        class="compact-content-tile"
        :class="{
          'is-disabled': item.status !== 'ENABLED' || item.categoryStatus !== 'ENABLED'
        }"
        title="拖动排序，点击编辑"
      >
        <button
          class="compact-content-tile__media"
          type="button"
          :aria-label="`编辑分类 ${item.categoryName}`"
          @click="openEditor(item)"
        >
          <img :src="item.imageUrl" :alt="item.categoryName" />
          <span class="compact-content-tile__disabled-overlay" aria-hidden="true" />
        </button>
        <button
          v-auth="'content:home-category:write'"
          class="compact-content-tile__delete"
          type="button"
          :aria-label="`删除分类 ${item.categoryName}`"
          title="删除"
          @click="handleDelete(item)"
        >
          <ArtSvgIcon icon="ri:close-line" />
        </button>
      </article>

      <button
        v-auth="'content:home-category:write'"
        class="compact-add-tile"
        type="button"
        aria-label="新增分类"
        title="新增分类"
        @click="openEditor()"
      >
        <ArtSvgIcon icon="ri:add-line" />
      </button>
    </VueDraggable>

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
          <div class="asset-field">
            <AssetPicker
              :model-value="{ fileId: formData.imageFileId, url: formData.imageUrl }"
              media-kind="IMAGE"
              compact
              @change="handleImageChange"
            />
            <div class="image-guidance">
              <ArtSvgIcon icon="ri:information-line" />
              <span>
                建议使用 1:1 方图，推荐 480 × 480 px；透明背景的 PNG、WebP 或 SVG
                效果更佳，主体四周请适当留白
              </span>
            </div>
          </div>
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
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import { VueDraggable } from 'vue-draggable-plus'
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
  const sorting = ref(false)
  const currentItemId = ref<number | null>(null)
  const editingCategoryId = ref<number | null>(null)
  const items = ref<Api.Content.HomeCategoryItem[]>([])
  const categoryOptions = ref<Api.Content.HomeCategoryOption[]>([])
  const formRef = ref<FormInstance>()
  let itemOrderSnapshot: Api.Content.HomeCategoryItem[] = []

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
          updateHomeCategory(
            item.id,
            toHomeCategoryPayload({
              categoryId: item.categoryId,
              imageFileId: item.imageFileId,
              sortOrder: item.sortOrder,
              status: item.status
            }),
            false
          )
        )
      )
      ElMessage.success('分类排序已更新')
      await loadData()
      emit('changed')
    } catch {
      items.value = itemOrderSnapshot
      await loadData()
    } finally {
      sorting.value = false
      itemOrderSnapshot = []
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
    grid-auto-columns: 132px;
    grid-auto-flow: column;
    gap: 12px;
    padding: 1px 1px 6px;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .compact-add-tile,
  .compact-content-tile {
    width: 132px;
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
      display: grid;
      place-items: center;
      width: 100%;
      height: 100%;
      padding: 10px;
      overflow: hidden;
      cursor: pointer;
      background: var(--el-fill-color-light);
      border: 0;

      img {
        width: 100%;
        height: 100%;
        object-fit: contain;
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

  .asset-field {
    display: grid;
    gap: 10px;
    width: 100%;
  }

  .image-guidance {
    display: flex;
    gap: 6px;
    align-items: flex-start;
    font-size: 12px;
    line-height: 1.55;
    color: var(--el-text-color-secondary);

    :deep(.art-svg-icon) {
      flex: 0 0 auto;
      margin-top: 2px;
      color: var(--el-color-primary);
    }
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
