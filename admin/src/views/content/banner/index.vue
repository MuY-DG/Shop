<template>
  <div class="content-banner art-full-height">
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      :show-expand="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card" :style="{ marginTop: '12px' }">
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

    <ElDrawer v-model="editorVisible" :title="currentBannerId ? '编辑轮播' : '新增轮播'" size="720px" destroy-on-close>
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="92px">
        <ElFormItem label="标题" prop="title">
          <ElInput v-model="formData.title" maxlength="128" placeholder="请输入标题" />
        </ElFormItem>
        <ElFormItem label="副标题" prop="subtitle">
          <ElInput v-model="formData.subtitle" maxlength="255" placeholder="请输入副标题" />
        </ElFormItem>
        <ElFormItem label="轮播图" prop="imageFileId">
          <div class="asset-field">
            <AssetPicker
              :model-value="{ fileId: formData.imageFileId, url: formData.imageUrl }"
              purpose="HOME_BANNER"
              @change="handleBannerImageChange"
            />
            <ElInput v-model="formData.imageUrl" readonly placeholder="已选素材地址" />
          </div>
        </ElFormItem>
        <ElRow :gutter="16">
          <ElCol :xs="24" :md="12">
            <ElFormItem label="跳转类型" prop="jumpType">
              <ElSelect v-model="formData.jumpType" placeholder="请选择跳转类型">
                <ElOption v-for="item in jumpTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
              </ElSelect>
            </ElFormItem>
          </ElCol>
          <ElCol :xs="24" :md="12">
            <ElFormItem label="状态" prop="status">
              <ElSelect v-model="formData.status" placeholder="请选择状态">
                <ElOption label="启用" value="ENABLED" />
                <ElOption label="禁用" value="DISABLED" />
              </ElSelect>
            </ElFormItem>
          </ElCol>
          <ElCol v-if="requiresTargetId" :xs="24" :md="12">
            <ElFormItem label="目标 ID" prop="jumpTargetId">
              <ElInputNumber
                v-model="formData.jumpTargetId"
                :min="1"
                :precision="0"
                controls-position="right"
                style="width: 100%"
              />
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
                value-format="YYYY-MM-DDTHH:mm:ss"
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
                value-format="YYYY-MM-DDTHH:mm:ss"
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
  import { ElImage, ElMessage, ElMessageBox, ElTag, type FormInstance, type FormRules } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import {
    createHomeBanner,
    disableHomeBanner,
    enableHomeBanner,
    fetchHomeBanners,
    updateHomeBanner
  } from '@/api/content'

  defineOptions({ name: 'ContentBanner' })

  interface BannerEditorForm extends Omit<Api.Content.BannerForm, 'jumpPath' | 'startAt' | 'endAt'> {
    jumpPath: string
    startAt: string | null
    endAt: string | null
  }

  const loading = ref(false)
  const submitting = ref(false)
  const editorVisible = ref(false)
  const banners = ref<Api.Content.BannerItem[]>([])
  const currentBannerId = ref<number | null>(null)
  const formRef = ref<FormInstance>()

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

  const rules: FormRules<BannerEditorForm> = {
    title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
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
            callback(new Error('请输入目标 ID'))
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

  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')

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
          h('div', { class: 'title' }, row.title),
          h('div', { class: 'subtitle' }, row.subtitle || '-')
        ])
    },
    {
      prop: 'status',
      label: '状态',
      width: 100,
      formatter: (row) =>
        h(ElTag, { type: row.status === 'ENABLED' ? 'success' : 'info' }, () =>
          row.status === 'ENABLED' ? '启用' : '禁用'
        )
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
          list: buildActions(row),
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

  const openEditor = (banner?: Api.Content.BannerItem) => {
    currentBannerId.value = banner?.id ?? null
    Object.assign(formData, createDefaultForm())

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
    requestAnimationFrame(() => formRef.value?.clearValidate())
  }

  const buildActions = (row: Api.Content.BannerItem): ButtonMoreItem[] => {
    const actions: ButtonMoreItem[] = [
      {
        key: 'edit',
        label: '编辑',
        icon: 'ri:edit-2-line',
        auth: 'content:banner:update'
      }
    ]

    if (row.status === 'ENABLED') {
      actions.push({
        key: 'disable',
        label: '禁用',
        icon: 'ri:pause-circle-line',
        color: '#909399',
        auth: 'content:banner:publish'
      })
    } else {
      actions.push({
        key: 'enable',
        label: '启用',
        icon: 'ri:play-circle-line',
        color: '#67c23a',
        auth: 'content:banner:publish'
      })
    }

    return actions
  }

  const handleAction = (item: ButtonMoreItem, row: Api.Content.BannerItem) => {
    switch (item.key) {
      case 'edit':
        openEditor(row)
        break
      case 'enable':
        toggleBanner(row, true)
        break
      case 'disable':
        toggleBanner(row, false)
        break
    }
  }

  const toggleBanner = async (row: Api.Content.BannerItem, enable: boolean) => {
    const text = enable ? '启用' : '禁用'
    await ElMessageBox.confirm(`确定${text}轮播“${row.title}”吗？`, `${text}确认`, {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })

    if (enable) {
      await enableHomeBanner(row.id)
    } else {
      await disableHomeBanner(row.id)
    }
    await loadBanners()
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
    jumpTargetId: requiresTargetId.value ? formData.jumpTargetId ?? null : null,
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
    if (formData.startAt && formData.endAt && formData.startAt > formData.endAt) {
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
    } finally {
      submitting.value = false
    }
  }

  onMounted(() => {
    loadBanners()
  })
</script>

<style scoped lang="scss">
  .asset-field {
    display: grid;
    gap: 10px;
    width: 100%;
  }

  .drawer-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
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
