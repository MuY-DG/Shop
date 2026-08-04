<template>
  <div class="product-review-page art-full-height">
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      :show-expand="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card review-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />

      <ArtTable
        row-key="id"
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <template #product="{ row }">
          <div class="product-cell">
            <ElImage
              v-if="row.productImage"
              class="product-image"
              :src="row.productImage"
              fit="cover"
              :preview-src-list="[row.productImage]"
              preview-teleported
            />
            <div class="product-meta">
              <span class="product-title">{{ row.productTitle }}</span>
              <span class="secondary-text">SPU {{ row.spuId }}</span>
            </div>
          </div>
        </template>

        <template #reviewer="{ row }">
          <div class="reviewer-cell">
            <span>{{ row.reviewerName }}</span>
            <div class="reviewer-tags">
              <ElTag v-if="row.verifiedPurchase" type="success" size="small">已购</ElTag>
              <ElTag v-if="row.anonymous" type="info" size="small">匿名发布</ElTag>
              <span class="secondary-text">用户 {{ row.userId }}</span>
            </div>
          </div>
        </template>

        <template #rating="{ row }">
          <ElRate :model-value="row.rating" disabled size="small" />
        </template>

        <template #content="{ row }">
          <div class="review-content-cell">
            <ElTooltip
              :content="row.content || '用户未填写文字评价'"
              placement="top"
              :show-after="300"
            >
              <span class="review-content" :class="{ 'is-empty': !row.content }">
                {{ row.content || '仅评分' }}
              </span>
            </ElTooltip>
            <div v-if="row.images?.length" class="review-image-list review-image-list--table">
              <ElImage
                v-for="(image, index) in row.images.slice(0, 3)"
                :key="image.fileId"
                class="review-image"
                :src="image.url"
                fit="cover"
                :preview-src-list="reviewImageUrls(row.images)"
                :initial-index="index"
                preview-teleported
              />
              <span v-if="row.images.length > 3" class="review-image-count">
                +{{ row.images.length - 3 }}
              </span>
            </div>
          </div>
        </template>

        <template #orderNo="{ row }">
          <ElTag v-if="row.orderDataCleaned" type="info" size="small">订单已清理</ElTag>
          <span v-else>{{ row.orderNo || '-' }}</span>
        </template>

        <template #status="{ row }">
          <div class="status-cell">
            <ElSwitch
              v-if="canModerate"
              :model-value="row.status === 'PUBLISHED'"
              :loading="statusUpdatingIds.has(row.id)"
              :disabled="statusUpdatingIds.has(row.id)"
              :before-change="() => requestStatus(row)"
            />
            <ElTag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" size="small">
              {{ row.status === 'PUBLISHED' ? '显示中' : '已隐藏' }}
            </ElTag>
          </div>
        </template>

        <template #operation="{ row }">
          <ElButton type="primary" link @click="showDetail(row)">查看</ElButton>
          <ElButton v-if="canModerate" type="primary" link @click="showEdit(row)">编辑</ElButton>
          <ElButton v-if="canModerate" type="danger" link @click="requestDelete(row)">
            删除
          </ElButton>
        </template>
      </ArtTable>
    </ElCard>

    <ElDrawer v-model="detailVisible" title="评论详情" size="520px">
      <template v-if="currentReview">
        <div class="detail-product">
          <ElImage
            v-if="currentReview.productImage"
            class="detail-product-image"
            :src="currentReview.productImage"
            fit="cover"
          />
          <div>
            <div class="detail-product-title">{{ currentReview.productTitle }}</div>
            <div class="secondary-text">SPU {{ currentReview.spuId }}</div>
          </div>
        </div>

        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="评论 ID">{{ currentReview.id }}</ElDescriptionsItem>
          <ElDescriptionsItem label="评价用户">
            {{ currentReview.reviewerName }}（用户 {{ currentReview.userId }}）
            <ElTag
              v-if="currentReview.verifiedPurchase"
              class="detail-tag"
              type="success"
              size="small"
            >
              已购
            </ElTag>
            <ElTag v-if="currentReview.anonymous" class="detail-tag" type="info" size="small">
              匿名发布
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="评分">
            <ElRate :model-value="currentReview.rating" disabled size="small" />
          </ElDescriptionsItem>
          <ElDescriptionsItem label="评论内容">
            <div class="detail-content">
              {{ currentReview.content || '用户未填写文字评价' }}
            </div>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="currentReview.images?.length" label="评价图片">
            <div class="review-image-list review-image-list--detail">
              <ElImage
                v-for="(image, index) in currentReview.images"
                :key="image.fileId"
                class="review-image review-image--detail"
                :src="image.url"
                fit="cover"
                :preview-src-list="reviewImageUrls(currentReview.images)"
                :initial-index="index"
                preview-teleported
              />
            </div>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="购买规格">
            {{ currentReview.specText || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="关联订单">
            <ElTag v-if="currentReview.orderDataCleaned" type="info" size="small">
              订单已清理
            </ElTag>
            <template v-else>
              {{ currentReview.orderNo || '-' }}
              <template v-if="currentReview.orderId">（订单 {{ currentReview.orderId }}）</template>
            </template>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="评论时间">
            {{ formatDateTime(currentReview.createdAt) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="显示状态">
            <ElTag :type="currentReview.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ currentReview.status === 'PUBLISHED' ? '显示中' : '已隐藏' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="currentReview.moderatedAt" label="最近审核">
            管理员 {{ currentReview.moderatedByAdminUserId }} ·
            {{ formatDateTime(currentReview.moderatedAt) }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </template>
    </ElDrawer>

    <ElDialog
      v-model="editVisible"
      title="编辑评论"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <ElForm label-position="top">
        <ElFormItem label="评分" required>
          <ElRate v-model="editForm.rating" />
        </ElFormItem>
        <ElFormItem label="评论内容">
          <ElInput
            v-model="editForm.content"
            type="textarea"
            :rows="6"
            maxlength="1000"
            show-word-limit
            placeholder="允许为空，最多 1000 个字符"
          />
        </ElFormItem>
        <ElFormItem label="匿名展示">
          <ElSwitch v-model="editForm.anonymous" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton :disabled="editSubmitting" @click="editVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="editSubmitting" @click="submitEdit"> 保存修改 </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useTable } from '@/hooks/core/useTable'
  import { formatLocalDateTime as formatDateTime } from '@/utils/date-time'
  import {
    deleteProductReview,
    fetchProductReviews,
    updateProductReview,
    updateProductReviewStatus
  } from '@/api/product'

  defineOptions({ name: 'ProductReview' })

  const { hasAuth } = useAuth()
  const canModerate = computed(() => hasAuth('product:review:moderate'))
  const detailVisible = ref(false)
  const editVisible = ref(false)
  const editSubmitting = ref(false)
  const editingReviewId = ref(0)
  const editForm = ref<Api.Product.ProductReviewUpdateForm>({
    rating: 5,
    content: '',
    anonymous: false
  })
  const currentReview = ref<Api.Product.ProductReview | null>(null)
  const statusUpdatingIds = ref(new Set<number>())
  const reviewImageUrls = (images: Api.Product.ProductReviewImage[]) =>
    images.map((image) => image.url)

  const searchForm = ref<{
    productTitle?: string
    rating?: number
    status?: Api.Product.ProductReviewStatus
    anonymous?: boolean
  }>({})

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '商品名称',
      key: 'productTitle',
      type: 'input',
      span: 6,
      props: { clearable: true, placeholder: '请输入商品名称' }
    },
    {
      label: '评分',
      key: 'rating',
      type: 'select',
      span: 6,
      props: {
        clearable: true,
        placeholder: '请选择评分',
        options: [5, 4, 3, 2, 1].map((value) => ({ label: `${value} 星`, value }))
      }
    },
    {
      label: '显示状态',
      key: 'status',
      type: 'select',
      span: 6,
      props: {
        clearable: true,
        placeholder: '请选择状态',
        options: [
          { label: '显示中', value: 'PUBLISHED' },
          { label: '已隐藏', value: 'HIDDEN' }
        ]
      }
    },
    {
      label: '发布方式',
      key: 'anonymous',
      type: 'select',
      span: 6,
      props: {
        clearable: true,
        placeholder: '请选择发布方式',
        options: [
          { label: '匿名发布', value: true },
          { label: '实名发布', value: false }
        ]
      }
    }
  ])

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData,
    refreshUpdate
  } = useTable({
    core: {
      apiFn: fetchProductReviews,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 90 },
        { prop: 'product', label: '商品', minWidth: 230, useSlot: true },
        { prop: 'reviewer', label: '评价用户', minWidth: 170, useSlot: true },
        { prop: 'rating', label: '评分', width: 150, useSlot: true },
        { prop: 'content', label: '评论内容', minWidth: 240, useSlot: true },
        { prop: 'specText', label: '购买规格', minWidth: 120 },
        { prop: 'orderNo', label: '订单号', minWidth: 180, useSlot: true },
        {
          prop: 'createdAt',
          label: '评论时间',
          width: 180,
          formatter: (row) => formatDateTime(row.createdAt)
        },
        { prop: 'status', label: '显示状态', width: 160, useSlot: true },
        { prop: 'operation', label: '操作', width: 180, fixed: 'right', useSlot: true }
      ]
    }
  })

  const handleSearch = (params: Record<string, unknown>) => {
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = {}
    resetSearchParams()
    getData()
  }

  const showDetail = (row: Api.Product.ProductReview) => {
    currentReview.value = { ...row }
    detailVisible.value = true
  }

  const showEdit = (row: Api.Product.ProductReview) => {
    editingReviewId.value = row.id
    editForm.value = {
      rating: row.rating,
      content: row.content || '',
      anonymous: row.anonymous
    }
    editVisible.value = true
  }

  const isMessageBoxCancel = (error: unknown) => error === 'cancel' || error === 'close'

  const requestStatus = async (row: Api.Product.ProductReview): Promise<boolean> => {
    if (statusUpdatingIds.value.has(row.id)) return false
    const nextStatus: Api.Product.ProductReviewStatus =
      row.status === 'PUBLISHED' ? 'HIDDEN' : 'PUBLISHED'
    const actionText = nextStatus === 'PUBLISHED' ? '恢复显示' : '隐藏'

    try {
      await ElMessageBox.confirm(`确定${actionText}这条商品评论吗？`, `${actionText}评论`, {
        type: 'warning',
        confirmButtonText: `确定${actionText}`,
        cancelButtonText: '取消'
      })
      statusUpdatingIds.value.add(row.id)
      await updateProductReviewStatus(row.id, { status: nextStatus })
      await refreshUpdate()
      if (currentReview.value?.id === row.id) {
        currentReview.value.status = nextStatus
      }
      return true
    } catch (error) {
      if (!isMessageBoxCancel(error)) {
        ElMessage.error(`${actionText}失败，评论状态未变更`)
      }
      return false
    } finally {
      statusUpdatingIds.value.delete(row.id)
    }
  }

  const submitEdit = async () => {
    if (!editingReviewId.value || editSubmitting.value) return
    if (editForm.value.rating < 1 || editForm.value.rating > 5) {
      ElMessage.warning('请选择 1 至 5 星评分')
      return
    }
    editSubmitting.value = true
    try {
      await updateProductReview(editingReviewId.value, {
        rating: editForm.value.rating,
        content: editForm.value.content.trim(),
        anonymous: editForm.value.anonymous
      })
      ElMessage.success('评论已修改')
      editVisible.value = false
      await refreshUpdate()
      if (currentReview.value?.id === editingReviewId.value) {
        currentReview.value = {
          ...currentReview.value,
          rating: editForm.value.rating,
          content: editForm.value.content.trim(),
          anonymous: editForm.value.anonymous
        }
      }
    } catch {
      ElMessage.error('修改失败，评论未变更')
    } finally {
      editSubmitting.value = false
    }
  }

  const requestDelete = async (row: Api.Product.ProductReview) => {
    try {
      await ElMessageBox.confirm('删除后评论及其图片将无法恢复，确定继续吗？', '删除评论', {
        type: 'warning',
        confirmButtonText: '确定删除',
        cancelButtonText: '取消'
      })
      await deleteProductReview(row.id)
      ElMessage.success('评论已删除')
      if (currentReview.value?.id === row.id) {
        detailVisible.value = false
        currentReview.value = null
      }
      await refreshUpdate()
    } catch (error) {
      if (!isMessageBoxCancel(error)) {
        ElMessage.error('删除失败，评论仍然保留')
      }
    }
  }
</script>

<style scoped lang="scss">
  .review-table-card {
    margin-top: 12px;
  }

  .product-cell,
  .detail-product {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  .product-image {
    flex: 0 0 auto;
    width: 48px;
    height: 48px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .product-meta,
  .reviewer-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }

  .product-title {
    overflow: hidden;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .secondary-text {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .reviewer-tags,
  .status-cell {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .review-content {
    display: -webkit-box;
    overflow: hidden;
    line-height: 20px;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  .review-content-cell {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .review-content.is-empty {
    color: var(--el-text-color-placeholder);
  }

  .review-image-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .review-image-list--table {
    align-items: center;
  }

  .review-image {
    width: 38px;
    height: 38px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
  }

  .review-image--detail {
    width: 92px;
    height: 92px;
    border-radius: 8px;
  }

  .review-image-count {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .detail-product {
    padding: 0 0 20px;
  }

  .detail-product-image {
    width: 64px;
    height: 64px;
    border-radius: 10px;
  }

  .detail-product-title {
    margin-bottom: 6px;
    font-size: 16px;
    font-weight: 600;
  }

  .detail-tag {
    margin-left: 8px;
  }

  .detail-content {
    line-height: 22px;
    word-break: break-word;
    white-space: pre-wrap;
  }
</style>
