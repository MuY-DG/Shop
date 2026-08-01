<template>
  <div class="content-banner art-full-height" :class="{ 'is-embedded': embedded }">
    <ArtSearchBar
      v-if="!embedded"
      v-model="searchForm"
      :items="searchItems"
      :show-expand="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <VueDraggable
      v-if="embedded"
      v-model="banners"
      v-loading="loading || sorting"
      class="compact-tile-strip"
      draggable=".compact-content-tile"
      direction="horizontal"
      :animation="180"
      :disabled="sorting || !hasAuth('content:banner:update')"
      ghost-class="compact-content-tile--ghost"
      chosen-class="compact-content-tile--chosen"
      @start="captureBannerOrder"
      @end="handleBannerReorder"
    >
      <article
        v-for="banner in banners"
        :key="banner.id"
        class="compact-content-tile"
        :class="{ 'is-disabled': banner.status !== 'ENABLED' }"
        title="拖动排序，点击编辑"
      >
        <button
          class="compact-content-tile__media"
          type="button"
          :aria-label="`编辑轮播 ${banner.title || banner.id}`"
          @click="openEditor(banner)"
        >
          <img :src="banner.imageUrl" :alt="banner.title || '轮播图'" />
          <span class="compact-content-tile__disabled-overlay" aria-hidden="true" />
        </button>
      </article>

      <button
        v-auth="'content:banner:create'"
        class="compact-add-tile"
        type="button"
        aria-label="新增轮播"
        title="新增轮播"
        @click="openEditor()"
      >
        <ArtSvgIcon icon="ri:add-line" />
      </button>
    </VueDraggable>

    <ElCard v-else class="art-table-card" :style="{ marginTop: '12px' }">
      <ArtTableHeader :loading="loading" v-model:columns="columnChecks" @refresh="loadBanners">
        <template #left>
          <ElButton type="primary" v-auth="'content:banner:create'" @click="openEditor()">
            新增轮播
          </ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="banners"
        :columns="columns"
        :pagination="pagination"
        @pagination:current-change="handleCurrentChange"
        @pagination:size-change="handleSizeChange"
      />
    </ElCard>

    <ElDrawer
      v-model="editorVisible"
      :title="currentBannerId ? '编辑轮播' : '新增轮播'"
      size="720px"
      destroy-on-close
    >
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="92px">
        <ElFormItem label="标题" prop="title">
          <ElInput v-model="formData.title" maxlength="128" placeholder="请输入标题（选填）" />
        </ElFormItem>
        <ElFormItem label="副标题" prop="subtitle">
          <ElInput v-model="formData.subtitle" maxlength="255" placeholder="请输入副标题（选填）" />
        </ElFormItem>
        <ElFormItem label="轮播图" prop="imageFileId">
          <div class="asset-field">
            <AssetPicker
              :model-value="{ fileId: formData.imageFileId, url: formData.imageUrl }"
              media-kind="IMAGE"
              compact
              @change="handleBannerImageChange"
            />
            <div class="image-guidance">
              <ArtSvgIcon icon="ri:information-line" />
              <span>
                建议使用 1:1 方图，推荐 1500 × 1500 px（最低 750 × 750 px），支持
                JPG、PNG、WebP、SVG，建议不超过 2 MB
              </span>
            </div>
          </div>
        </ElFormItem>
        <ElRow :gutter="16">
          <ElCol :xs="24" :md="12">
            <ElFormItem label="跳转类型" prop="jumpType">
              <ElSelect
                v-model="formData.jumpType"
                placeholder="请选择跳转类型"
                @change="handleJumpTypeChange"
              >
                <ElOption
                  v-for="item in jumpTypeOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </ElSelect>
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="状态" prop="status">
              <ElSwitch
                v-model="formData.status"
                inline-prompt
                active-text="启用"
                inactive-text="禁用"
                active-value="ENABLED"
                inactive-value="DISABLED"
              />
            </ElFormItem>
          </ElCol>
          <ElCol v-if="requiresTargetId" :xs="24" :md="12">
            <ElFormItem label="跳转目标" prop="jumpTargetId">
              <ElCascader
                v-if="formData.jumpType === 'CATEGORY'"
                v-model="formData.jumpTargetId"
                :options="categoryTargetOptions"
                :props="categoryTargetProps"
                filterable
                clearable
                :show-all-levels="true"
                placeholder="请选择商品分类"
                style="width: 100%"
              />
              <ElSelect
                v-else
                v-model="formData.jumpTargetId"
                filterable
                remote
                reserve-keyword
                clearable
                :remote-method="searchJumpTargets"
                :loading="targetLoading"
                :placeholder="targetPlaceholder"
                @visible-change="handleTargetSelectorVisible"
              >
                <ElOption
                  v-for="option in jumpTargetOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                >
                  <div class="target-option">
                    <span>{{ option.label }}</span>
                    <span class="target-option__meta">{{ option.meta }}</span>
                  </div>
                </ElOption>
              </ElSelect>
            </ElFormItem>
          </ElCol>
          <ElCol v-if="requiresPath" :xs="24" :md="12">
            <ElFormItem label="跳转路径" prop="jumpPath">
              <ElInput v-model="formData.jumpPath" placeholder="请输入路径或 URL" />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="排序" prop="sortOrder">
              <ElInputNumber
                v-model="formData.sortOrder"
                :min="0"
                :precision="0"
                controls-position="right"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="生效开始">
              <ElDatePicker
                v-model="formData.startAt"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ssZ"
                placeholder="请选择开始时间"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="生效结束">
              <ElDatePicker
                v-model="formData.endAt"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ssZ"
                placeholder="请选择结束时间"
                style="width: 100%"
              />
            </ElFormItem>
          </ElCol>
        </ElRow>
      </ElForm>

      <template #footer>
        <div class="drawer-footer">
          <ElButton @click="editorVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
        </div>
      </template>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, reactive, ref } from 'vue'
  import { ElImage, ElMessage, ElSwitch, type FormInstance, type FormRules } from 'element-plus'
  import { VueDraggable } from 'vue-draggable-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import { useAuth } from '@/hooks'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import { formatLocalDateTime as formatDateTime, parseApiDateTime } from '@/utils/date-time'
  import {
    createHomeBanner,
    disableHomeBanner,
    enableHomeBanner,
    fetchHomeCategoryOptions,
    fetchHomeBanners,
    fetchHomeProductOptions,
    updateHomeBanner
  } from '@/api/content'
  import { fetchCouponTemplates } from '@/api/coupon'

  defineOptions({ name: 'ContentBanner' })

  const { embedded = false } = defineProps<{
    embedded?: boolean
  }>()
  const emit = defineEmits<{
    changed: []
  }>()
  const { hasAuth } = useAuth()

  interface BannerEditorForm
    extends Omit<Api.Content.BannerForm, 'jumpPath' | 'startAt' | 'endAt'> {
    jumpPath: string
    startAt: string | null
    endAt: string | null
  }

  interface JumpTargetOption {
    value: number
    label: string
    meta: string
  }

  interface CategoryTargetOption {
    [key: string]: unknown
    value: number
    label: string
    children?: CategoryTargetOption[]
  }

  const loading = ref(false)
  const submitting = ref(false)
  const editorVisible = ref(false)
  const banners = ref<Api.Content.BannerItem[]>([])
  const currentBannerId = ref<number | null>(null)
  const formRef = ref<FormInstance>()
  const targetLoading = ref(false)
  const statusUpdatingId = ref<number | null>(null)
  const sorting = ref(false)
  const jumpTargetOptions = ref<JumpTargetOption[]>([])
  const categoryOptions = ref<Api.Content.HomeCategoryOption[]>([])
  let targetSearchSequence = 0
  let bannerOrderSnapshot: Api.Content.BannerItem[] = []

  const pagination = reactive<Api.Common.PaginationParams>({
    current: 1,
    size: 20,
    total: 0
  })

  const searchForm = ref<{
    title?: string
    status?: Api.Content.BannerStatus
  }>({
    title: undefined,
    status: undefined
  })

  const createDefaultForm = (): BannerEditorForm => ({
    title: '',
    subtitle: '',
    imageFileId: null,
    imageUrl: '',
    jumpType: 'NONE',
    jumpTargetId: null,
    jumpPath: '',
    status: 'ENABLED',
    sortOrder: 0,
    startAt: null,
    endAt: null
  })

  const formData = reactive<BannerEditorForm>(createDefaultForm())

  const jumpTypeOptions = [
    { label: '不跳转', value: 'NONE' },
    { label: '商品', value: 'PRODUCT' },
    { label: '分类', value: 'CATEGORY' },
    { label: '优惠券', value: 'COUPON' },
    { label: '小程序路径', value: 'APP_PATH' },
    { label: '外部链接', value: 'URL' }
  ] as const

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '标题',
      key: 'title',
      type: 'input',
      props: {
        clearable: true,
        placeholder: '请输入标题'
      }
    },
    {
      label: '状态',
      key: 'status',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择状态',
        options: [
          { label: '启用', value: 'ENABLED' },
          { label: '禁用', value: 'DISABLED' }
        ]
      }
    }
  ])

  const requiresTargetId = computed(() =>
    ['PRODUCT', 'CATEGORY', 'COUPON'].includes(formData.jumpType)
  )
  const requiresPath = computed(() => ['APP_PATH', 'URL'].includes(formData.jumpType))
  const usesRemoteTarget = computed(() => ['PRODUCT', 'COUPON'].includes(formData.jumpType))
  const targetPlaceholder = computed(() =>
    formData.jumpType === 'PRODUCT' ? '输入商品名称搜索' : '输入优惠券名称搜索'
  )
  const categoryTargetProps = {
    emitPath: false,
    checkStrictly: true,
    expandTrigger: 'hover'
  } as const
  const categoryTargetOptions = computed<CategoryTargetOption[]>(() => {
    const childrenByParent = new Map<number, Api.Content.HomeCategoryOption[]>()
    categoryOptions.value.forEach((option) => {
      const siblings = childrenByParent.get(option.parentId) || []
      siblings.push(option)
      childrenByParent.set(option.parentId, siblings)
    })

    const buildChildren = (parentId: number): CategoryTargetOption[] =>
      (childrenByParent.get(parentId) || []).map((option) => {
        const children = buildChildren(option.id)
        return {
          value: option.id,
          label: option.name,
          children: children.length ? children : undefined
        }
      })

    return buildChildren(0)
  })

  const rules: FormRules<BannerEditorForm> = {
    imageFileId: [
      {
        validator: (_rule, value, callback) => {
          if (!value) {
            callback(new Error('请选择轮播图'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    jumpType: [{ required: true, message: '请选择跳转类型', trigger: 'change' }],
    jumpTargetId: [
      {
        validator: (_rule, value, callback) => {
          if (requiresTargetId.value && !value) {
            callback(new Error('请选择跳转目标'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    jumpPath: [
      {
        validator: (_rule, value, callback) => {
          if (requiresPath.value && !value?.trim()) {
            callback(new Error('请输入跳转路径'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ]
  }

  const formatEffectiveTime = (row: Api.Content.BannerItem) => {
    const start = formatDateTime(row.startAt)
    const end = formatDateTime(row.endAt)
    if (start === '-' && end === '-') return '长期生效'
    return `${start} ~ ${end}`
  }

  const buildJumpText = (row: Api.Content.BannerItem) => {
    switch (row.jumpType) {
      case 'PRODUCT':
      case 'CATEGORY':
      case 'COUPON':
        return `${row.jumpType} / ${row.jumpTargetId || '-'}`
      case 'APP_PATH':
      case 'URL':
        return `${row.jumpType} / ${row.jumpPath || '-'}`
      default:
        return 'NONE'
    }
  }

  const { columns, columnChecks } = useTableColumns<Api.Content.BannerItem>(() => [
    {
      prop: 'imageUrl',
      label: '图片',
      width: 96,
      formatter: (row) =>
        h(ElImage, {
          src: row.imageUrl,
          fit: 'cover',
          previewSrcList: row.imageUrl ? [row.imageUrl] : [],
          previewTeleported: true,
          style: {
            width: '64px',
            height: '40px',
            borderRadius: '6px',
            backgroundColor: 'var(--el-fill-color-light)'
          }
        })
    },
    {
      prop: 'title',
      label: '标题',
      minWidth: 220,
      formatter: (row) =>
        h('div', { class: 'banner-title-cell' }, [
          h('div', { class: 'title' }, row.title || '-'),
          h('div', { class: 'subtitle' }, row.subtitle || '-')
        ])
    },
    {
      prop: 'status',
      label: '状态',
      width: 112,
      formatter: (row) =>
        h(ElSwitch, {
          modelValue: row.status === 'ENABLED',
          inlinePrompt: true,
          activeText: '启用',
          inactiveText: '禁用',
          loading: statusUpdatingId.value === row.id,
          disabled: !hasAuth('content:banner:publish'),
          'onUpdate:modelValue': (enabled: string | number | boolean) =>
            toggleBanner(row, enabled === true)
        })
    },
    {
      prop: 'sortOrder',
      label: '排序',
      width: 90
    },
    {
      prop: 'effectiveTime',
      label: '生效时间',
      minWidth: 220,
      formatter: (row) => formatEffectiveTime(row)
    },
    {
      prop: 'jumpType',
      label: '跳转',
      minWidth: 180,
      formatter: (row) => buildJumpText(row)
    },
    {
      prop: 'updatedAt',
      label: '更新时间',
      width: 180,
      formatter: (row) => formatDateTime(row.updatedAt)
    },
    {
      prop: 'operation',
      label: '操作',
      width: 130,
      fixed: 'right',
      formatter: (row) =>
        h(ArtButtonMore, {
          list: buildActions(),
          onClick: (item: ButtonMoreItem) => handleAction(item, row)
        })
    }
  ])

  const loadBanners = async () => {
    loading.value = true
    try {
      const response = await fetchHomeBanners({
        current: pagination.current,
        size: pagination.size,
        title: searchForm.value.title,
        status: searchForm.value.status
      })
      banners.value = response.records
      pagination.current = response.current
      pagination.size = response.size
      pagination.total = response.total
    } finally {
      loading.value = false
    }
  }

  const toBannerPayload = (
    banner: Api.Content.BannerItem,
    sortOrder: number
  ): Api.Content.BannerForm => ({
    title: banner.title,
    subtitle: banner.subtitle,
    imageFileId: banner.imageFileId,
    imageUrl: banner.imageUrl,
    jumpType: banner.jumpType,
    jumpTargetId: banner.jumpTargetId ?? null,
    jumpPath: banner.jumpPath || '',
    status: banner.status,
    sortOrder,
    startAt: banner.startAt ?? null,
    endAt: banner.endAt ?? null
  })

  const captureBannerOrder = () => {
    bannerOrderSnapshot = [...banners.value]
  }

  const handleBannerReorder = async () => {
    const reordered = banners.value.map((banner, index) => ({ ...banner, sortOrder: index }))
    const changed = reordered.filter(
      (banner, index) => bannerOrderSnapshot[index]?.id !== banner.id || banner.sortOrder !== index
    )
    if (!changed.length) return

    banners.value = reordered
    sorting.value = true
    try {
      await Promise.all(
        changed.map((banner) =>
          updateHomeBanner(banner.id, toBannerPayload(banner, banner.sortOrder), false)
        )
      )
      ElMessage.success('轮播排序已更新')
      await loadBanners()
      emit('changed')
    } catch {
      banners.value = bannerOrderSnapshot
      await loadBanners()
    } finally {
      sorting.value = false
      bannerOrderSnapshot = []
    }
  }

  const ensureCategoryOptions = async () => {
    if (categoryOptions.value.length > 0) return
    categoryOptions.value = await fetchHomeCategoryOptions()
  }

  const searchJumpTargets = async (keyword = '') => {
    if (!usesRemoteTarget.value) return
    const jumpType = formData.jumpType
    const sequence = ++targetSearchSequence
    targetLoading.value = true
    try {
      if (jumpType === 'PRODUCT') {
        const response = await fetchHomeProductOptions({
          keyword: keyword.trim(),
          current: 1,
          size: 50
        })
        if (sequence !== targetSearchSequence || formData.jumpType !== jumpType) return
        jumpTargetOptions.value = response.records.map((option) => ({
          value: option.id,
          label: `${option.title}（${option.categoryName}）`,
          meta: `SPU ${option.id}`
        }))
        return
      }

      const response = await fetchCouponTemplates({
        name: keyword.trim(),
        status: 'ENABLED',
        distributionMode: 'PUBLIC',
        current: 1,
        size: 50
      })
      if (sequence !== targetSearchSequence || formData.jumpType !== jumpType) return
      jumpTargetOptions.value = response.records.map((option) => ({
        value: option.id,
        label: option.name,
        meta: `优惠券 ${option.id}`
      }))
    } finally {
      if (sequence === targetSearchSequence) targetLoading.value = false
    }
  }

  const loadJumpTargets = async (keyword = '') => {
    if (formData.jumpType === 'CATEGORY') {
      await ensureCategoryOptions()
      return
    }
    if (usesRemoteTarget.value) await searchJumpTargets(keyword)
  }

  const handleTargetSelectorVisible = (visible: boolean) => {
    if (visible && jumpTargetOptions.value.length === 0) searchJumpTargets()
  }

  const handleJumpTypeChange = async () => {
    formData.jumpTargetId = null
    formData.jumpPath = ''
    jumpTargetOptions.value = []
    formRef.value?.clearValidate(['jumpTargetId', 'jumpPath'])
    await loadJumpTargets()
  }

  const openEditor = async (banner?: Api.Content.BannerItem) => {
    currentBannerId.value = banner?.id ?? null
    Object.assign(formData, createDefaultForm())
    jumpTargetOptions.value = []

    if (banner) {
      Object.assign(formData, {
        title: banner.title,
        subtitle: banner.subtitle,
        imageFileId: banner.imageFileId,
        imageUrl: banner.imageUrl,
        jumpType: banner.jumpType,
        jumpTargetId: banner.jumpTargetId ?? null,
        jumpPath: banner.jumpPath || '',
        status: banner.status,
        sortOrder: banner.sortOrder,
        startAt: banner.startAt || null,
        endAt: banner.endAt || null
      })
    }

    editorVisible.value = true
    const selectedTargetKeyword =
      banner?.jumpTargetId && usesRemoteTarget.value ? String(banner.jumpTargetId) : ''
    await loadJumpTargets(selectedTargetKeyword)
    requestAnimationFrame(() => formRef.value?.clearValidate())
  }

  const buildActions = (): ButtonMoreItem[] => {
    return [
      {
        key: 'edit',
        label: '编辑',
        icon: 'ri:edit-2-line',
        auth: 'content:banner:update'
      }
    ]
  }

  const handleAction = (item: ButtonMoreItem, row: Api.Content.BannerItem) => {
    switch (item.key) {
      case 'edit':
        openEditor(row)
        break
    }
  }

  const toggleBanner = async (row: Api.Content.BannerItem, enable: boolean) => {
    if ((row.status === 'ENABLED') === enable || statusUpdatingId.value === row.id) return
    statusUpdatingId.value = row.id
    try {
      if (enable) {
        await enableHomeBanner(row.id)
      } else {
        await disableHomeBanner(row.id)
      }
      await loadBanners()
      emit('changed')
    } finally {
      statusUpdatingId.value = null
    }
  }

  const handleBannerImageChange = (value: Api.Common.AssetValue) => {
    formData.imageFileId = value.fileId
    formData.imageUrl = value.url
  }

  const handleSearch = () => {
    pagination.current = 1
    loadBanners()
  }

  const handleReset = () => {
    searchForm.value = {
      title: undefined,
      status: undefined
    }
    pagination.current = 1
    loadBanners()
  }

  const handleCurrentChange = (current: number) => {
    pagination.current = current
    loadBanners()
  }

  const handleSizeChange = (size: number) => {
    pagination.size = size
    pagination.current = 1
    loadBanners()
  }

  const buildPayload = (): Api.Content.BannerForm => ({
    title: formData.title.trim(),
    subtitle: formData.subtitle.trim(),
    imageFileId: formData.imageFileId,
    imageUrl: formData.imageUrl.trim(),
    jumpType: formData.jumpType,
    jumpTargetId: requiresTargetId.value ? (formData.jumpTargetId ?? null) : null,
    jumpPath: requiresPath.value ? formData.jumpPath.trim() : '',
    status: formData.status,
    sortOrder: formData.sortOrder,
    startAt: formData.startAt,
    endAt: formData.endAt
  })

  const handleSubmit = async () => {
    if (!formRef.value) return

    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)

    if (!valid) return
    const startAt = parseApiDateTime(formData.startAt)
    const endAt = parseApiDateTime(formData.endAt)
    if (startAt && endAt && startAt.getTime() >= endAt.getTime()) {
      ElMessage.error('结束时间不能早于开始时间')
      return
    }

    submitting.value = true
    try {
      const payload = buildPayload()
      if (currentBannerId.value) {
        await updateHomeBanner(currentBannerId.value, payload)
      } else {
        await createHomeBanner(payload)
      }
      editorVisible.value = false
      await loadBanners()
      emit('changed')
    } finally {
      submitting.value = false
    }
  }

  onMounted(() => {
    loadBanners()
  })
</script>

<style scoped lang="scss">
  .content-banner.is-embedded {
    height: auto;
    min-height: 0;
  }

  .compact-tile-strip {
    display: grid;
    grid-auto-columns: 142px;
    grid-auto-flow: column;
    gap: 12px;
    padding: 1px 1px 6px;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .compact-add-tile,
  .compact-content-tile {
    width: 142px;
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
    font-size: 28px;
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
      background: rgb(15 23 42 / 34%);
      opacity: 0;
      transition: opacity 0.18s ease;
    }

    &.is-disabled &__disabled-overlay {
      opacity: 1;
    }
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

  .drawer-footer {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
  }

  .target-option {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;

    &__meta {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .banner-title-cell {
    display: grid;
    gap: 6px;

    .title {
      font-weight: 600;
      color: var(--el-text-color-primary);
      word-break: break-word;
    }

    .subtitle {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
</style>
