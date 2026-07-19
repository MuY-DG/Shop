<template>
  <div class="category-manager art-full-height">
    <ElCard class="category-manager__card" shadow="never">
      <header class="category-manager__header">
        <div>
          <h3>商品分类</h3>
          <p>拖动分类可调整同级顺序，也可跨层级移动或拖入成为子分类</p>
        </div>
        <ElTooltip content="刷新分类" placement="top">
          <ElButton circle :loading="loading" @click="loadCategories">
            <ElIcon><Refresh /></ElIcon>
          </ElButton>
        </ElTooltip>
      </header>

      <div v-loading="loading || categorySorting" class="category-manager__table">
        <div class="category-manager__table-header">
          <span>分类名称</span>
          <span>ID</span>
          <span>状态</span>
          <span>操作</span>
        </div>

        <div class="category-manager__tree-scroll">
          <ElTree
            v-if="categories.length"
            class="category-manager__tree"
            :data="categories"
            node-key="id"
            default-expand-all
            :expand-on-click-node="false"
            :draggable="canSortCategories"
            :allow-drop="allowCategoryDrop"
            @node-drag-start="captureCategoryOrder"
            @node-drop="handleCategoryDrop"
          >
            <template #default="{ data }">
              <div class="category-manager__tree-row">
                <div class="category-manager__name-cell">
                  <ElIcon
                    v-if="canSortCategories"
                    class="category-manager__drag-handle"
                    title="拖动调整分类顺序或层级"
                  >
                    <Rank />
                  </ElIcon>
                  <div class="category-manager__icon">
                    <ElImage v-if="data.icon" :src="data.icon" fit="cover">
                      <template #error>
                        <ElIcon><FolderOpened /></ElIcon>
                      </template>
                    </ElImage>
                    <ElIcon v-else><FolderOpened /></ElIcon>
                  </div>
                  <span class="category-manager__name" :title="data.name">{{ data.name }}</span>
                </div>
                <span class="category-manager__id">#{{ data.id }}</span>
                <ElSwitch
                  :model-value="data.status === 'ENABLED'"
                  :loading="categoryStatusUpdatingIds.has(data.id)"
                  :disabled="!canUpdateCategory || categoryStatusUpdatingIds.has(data.id)"
                  inline-prompt
                  active-text="启用"
                  inactive-text="禁用"
                  @click.stop
                  :before-change="() => requestCategoryStatus(data, data.status !== 'ENABLED')"
                />
                <div class="category-manager__actions">
                  <ElButton
                    v-if="canCreateCategory"
                    link
                    type="primary"
                    @click.stop="openCreateDialog(data)"
                  >
                    新增子分类
                  </ElButton>
                  <ElButton
                    v-if="canUpdateCategory"
                    link
                    type="primary"
                    @click.stop="openEditDialog(data)"
                  >
                    编辑
                  </ElButton>
                </div>
              </div>
            </template>
          </ElTree>

          <ElEmpty v-else-if="!loading" description="暂无商品分类" :image-size="80" />

          <button
            v-if="canCreateCategory"
            type="button"
            class="category-manager__create-node"
            @click="openCreateDialog()"
          >
            <ElIcon><Plus /></ElIcon>
            <span>新建分类</span>
          </button>
        </div>
      </div>
    </ElCard>

    <CategoryDialog
      v-model:visible="dialogVisible"
      :category="currentCategory"
      :initial-parent-id="parentCategoryId"
      :parent-options="parentTreeOptions"
      :submitting="saving"
      @submit="handleSubmit"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { FolderOpened, Plus, Rank, Refresh } from '@element-plus/icons-vue'
  import { ElImage, ElMessage, type AllowDropFunction, type NodeDropType } from 'element-plus'
  import {
    createProductCategory,
    fetchProductCategories,
    updateProductCategory,
    updateProductCategoryPosition
  } from '@/api/product'
  import { useAuth } from '@/hooks'
  import CategoryDialog from './modules/category-dialog.vue'

  defineOptions({ name: 'ProductCategory' })

  interface TreeOption {
    value: number
    label: string
    children?: TreeOption[]
  }

  const loading = ref(false)
  const saving = ref(false)
  const categorySorting = ref(false)
  const categoryStatusUpdatingIds = ref(new Set<number>())
  const categories = ref<Api.Product.Category[]>([])
  const dialogVisible = ref(false)
  const currentCategory = ref<Api.Product.Category | null>(null)
  const parentCategoryId = ref(0)
  let categoryOrderSnapshot: Api.Product.Category[] = []

  const { hasAuth } = useAuth()
  const canCreateCategory = computed(() => hasAuth('product:category:create'))
  const canUpdateCategory = computed(() => hasAuth('product:category:update'))
  const canSortCategories = computed(() => canUpdateCategory.value && !categorySorting.value)

  const collectNodeIds = (category: Api.Product.Category): number[] => {
    const ids = [category.id]
    category.children?.forEach((child) => ids.push(...collectNodeIds(child)))
    return ids
  }

  const buildTreeOptions = (
    items: Api.Product.Category[],
    excludedIds: Set<number> = new Set()
  ): TreeOption[] =>
    items
      .filter((item) => !excludedIds.has(item.id))
      .map((item) => ({
        value: item.id,
        label: item.name,
        children: buildTreeOptions(item.children || [], excludedIds)
      }))

  const parentTreeOptions = computed<TreeOption[]>(() => {
    if (!currentCategory.value) return buildTreeOptions(categories.value)
    return buildTreeOptions(categories.value, new Set(collectNodeIds(currentCategory.value)))
  })

  const findCategory = (
    items: Api.Product.Category[],
    categoryId: number
  ): Api.Product.Category | null => {
    for (const item of items) {
      if (item.id === categoryId) return item
      const child = findCategory(item.children || [], categoryId)
      if (child) return child
    }
    return null
  }

  const cloneCategoryTree = (items: Api.Product.Category[]): Api.Product.Category[] =>
    items.map((item) => ({
      ...item,
      children: cloneCategoryTree(item.children || [])
    }))

  const categoryContainsId = (category: Api.Product.Category, categoryId: number): boolean =>
    category.id === categoryId ||
    category.children?.some((child) => categoryContainsId(child, categoryId)) ||
    false

  const allowCategoryDrop: AllowDropFunction = (draggingNode, dropNode, dropType) => {
    if (categorySorting.value) return false
    const draggedCategory = draggingNode.data as Api.Product.Category
    const targetCategory = dropNode.data as Api.Product.Category
    const targetParentId = dropType === 'inner' ? targetCategory.id : targetCategory.parentId
    return !categoryContainsId(draggedCategory, targetParentId)
  }

  const captureCategoryOrder = () => {
    categoryOrderSnapshot = cloneCategoryTree(categories.value)
  }

  const handleCategoryDrop = async (
    draggingNode: Parameters<AllowDropFunction>[0],
    dropNode: Parameters<AllowDropFunction>[1],
    dropType: NodeDropType
  ) => {
    if (dropType === 'none') return

    const draggedCategory = draggingNode.data as Api.Product.Category
    const targetCategory = dropNode.data as Api.Product.Category
    const targetParentId = dropType === 'inner' ? targetCategory.id : targetCategory.parentId
    const targetSiblings =
      targetParentId === 0
        ? categories.value
        : findCategory(categories.value, targetParentId)?.children || []
    const targetIndex = targetSiblings.findIndex((item) => item.id === draggedCategory.id)
    if (targetIndex < 0) {
      categories.value = categoryOrderSnapshot
      await loadCategories()
      return
    }

    categorySorting.value = true
    try {
      await updateProductCategoryPosition(draggedCategory.id, {
        parentId: targetParentId,
        index: targetIndex
      })
      ElMessage.success(
        targetParentId === draggedCategory.parentId ? '分类排序已更新' : '分类位置已更新'
      )
      await loadCategories()
    } catch {
      categories.value = categoryOrderSnapshot
      await loadCategories()
    } finally {
      categorySorting.value = false
      categoryOrderSnapshot = []
    }
  }

  const nextCategorySortOrder = (parentId: number) => {
    const siblings =
      parentId === 0 ? categories.value : findCategory(categories.value, parentId)?.children || []
    return siblings.reduce((maximum, category) => Math.max(maximum, category.sortOrder + 1), 0)
  }

  const requestCategoryStatus = async (
    category: Api.Product.Category,
    enabled: boolean
  ): Promise<boolean> => {
    if (!canUpdateCategory.value || categoryStatusUpdatingIds.value.has(category.id)) return false

    categoryStatusUpdatingIds.value.add(category.id)
    try {
      const status: Api.Product.CategoryStatus = enabled ? 'ENABLED' : 'DISABLED'
      await updateProductCategory(category.id, {
        parentId: category.parentId,
        name: category.name,
        icon: category.icon,
        iconFileId: category.iconFileId,
        sortOrder: category.sortOrder,
        status
      })
      category.status = status
      return true
    } catch {
      return false
    } finally {
      categoryStatusUpdatingIds.value.delete(category.id)
    }
  }

  const loadCategories = async () => {
    loading.value = true
    try {
      categories.value = await fetchProductCategories()
    } finally {
      loading.value = false
    }
  }

  const openCreateDialog = (parent?: Api.Product.Category) => {
    currentCategory.value = null
    parentCategoryId.value = parent?.id ?? 0
    dialogVisible.value = true
  }

  const openEditDialog = (category: Api.Product.Category) => {
    currentCategory.value = { ...category, children: category.children || [] }
    parentCategoryId.value = category.parentId
    dialogVisible.value = true
  }

  const handleSubmit = async (form: Api.Product.CategoryForm) => {
    saving.value = true
    try {
      if (currentCategory.value?.id) {
        await updateProductCategory(currentCategory.value.id, form)
      } else {
        await createProductCategory({
          ...form,
          sortOrder: nextCategorySortOrder(form.parentId)
        })
      }
      dialogVisible.value = false
      await loadCategories()
    } finally {
      saving.value = false
    }
  }

  onMounted(loadCategories)
</script>

<style scoped lang="scss">
  .category-manager__card {
    height: 100%;

    :deep(.el-card__body) {
      display: flex;
      flex-direction: column;
      height: 100%;
      padding: 0;
    }
  }

  .category-manager__header {
    display: flex;
    flex: none;
    align-items: center;
    justify-content: space-between;
    padding: 18px 20px;
    border-bottom: 1px solid var(--el-border-color-lighter);

    h3,
    p {
      margin: 0;
    }

    h3 {
      font-size: 17px;
      color: var(--el-text-color-primary);
    }

    p {
      margin-top: 5px;
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }
  }

  .category-manager__table {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
  }

  .category-manager__table-header,
  .category-manager__tree-row {
    display: grid;
    grid-template-columns: minmax(220px, 1fr) 90px 100px 180px;
    gap: 12px;
    align-items: center;
  }

  .category-manager__table-header {
    flex: none;
    min-height: 46px;
    padding: 0 16px 0 42px;
    font-size: 13px;
    font-weight: 600;
    color: var(--el-text-color-secondary);
    background: var(--el-fill-color-light);
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .category-manager__tree-scroll {
    flex: 1;
    min-height: 0;
    overflow: auto;
  }

  .category-manager__tree {
    --el-tree-node-hover-bg-color: var(--el-fill-color-light);

    :deep(.el-tree-node__content) {
      height: 62px;
      padding-right: 16px;
      border-bottom: 1px solid var(--el-border-color-lighter);
    }

    :deep(.el-tree-node__content:active) .category-manager__drag-handle {
      cursor: grabbing;
    }
  }

  .category-manager__tree-row {
    width: 100%;
    min-width: 0;
  }

  .category-manager__name-cell,
  .category-manager__actions {
    display: flex;
    align-items: center;
  }

  .category-manager__name-cell {
    gap: 9px;
    min-width: 0;
  }

  .category-manager__drag-handle {
    flex: none;
    color: var(--el-text-color-placeholder);
    cursor: grab;
  }

  .category-manager__icon {
    display: grid;
    flex: none;
    place-items: center;
    width: 38px;
    height: 38px;
    overflow: hidden;
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
    border-radius: 7px;

    .el-image {
      width: 100%;
      height: 100%;
    }
  }

  .category-manager__name {
    min-width: 0;
    overflow: hidden;
    font-size: 14px;
    color: var(--el-text-color-primary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .category-manager__id {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .category-manager__actions {
    justify-content: flex-start;
  }

  .category-manager__create-node {
    display: flex;
    gap: 8px;
    align-items: center;
    width: 100%;
    min-height: 54px;
    padding: 0 16px 0 42px;
    color: var(--el-color-primary);
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);

    &:hover {
      background: var(--el-color-primary-light-9);
    }
  }

  @media (width <= 900px) {
    .category-manager__table-header,
    .category-manager__tree-row {
      grid-template-columns: minmax(180px, 1fr) 76px 86px 150px;
    }
  }
</style>
