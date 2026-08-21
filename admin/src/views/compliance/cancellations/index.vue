<template>
  <div class="account-cancellations-page art-full-height">
    <ElAlert
      title="这里只展示已经完成的账号注销记录，不提供审批、恢复或删除操作。"
      type="info"
      :closable="false"
      show-icon
    />

    <ArtSearchBar
      v-model="searchForm"
      class="search-bar"
      :items="searchItems"
      :show-expand="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <span class="table-hint">账号注销完成记录</span>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      />
    </ElCard>

    <ElDrawer v-model="drawerVisible" title="注销记录详情" size="620px">
      <ElDescriptions v-if="currentRecord" :column="1" border>
        <ElDescriptionsItem label="注销记录 ID">{{ currentRecord.id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="原用户 ID">{{ currentRecord.userId }}</ElDescriptionsItem>
        <ElDescriptionsItem label="完成时间">
          {{ formatDateTime(currentRecord.completedAt) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="身份核验时间">
          {{ formatDateTime(currentRecord.identityVerifiedAt) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="小程序环境">
          {{ environmentLabel(currentRecord.miniProgramEnv) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="注销须知版本">
          {{ currentRecord.noticeVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="注销须知摘要">
          <span class="digest">{{ currentRecord.noticeContentSha256 }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="立即清理">
          <div class="category-list">
            <ElTag v-for="item in currentRecord.deletedDataCategories" :key="item" type="danger">
              {{ item }}
            </ElTag>
          </div>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="按规则保留">
          <div class="category-list">
            <ElTag v-for="item in currentRecord.retainedDataCategories" :key="item" type="info">
              {{ item }}
            </ElTag>
          </div>
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, ref } from 'vue'
  import { ElButton, ElTag } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchAccountCancellations } from '@/api/compliance'
  import { formatLocalDateTime as formatDateTime } from '@/utils/date-time'

  defineOptions({ name: 'AccountCancellations' })

  type Cancellation = Api.Compliance.AccountCancellation

  const searchForm = ref<Api.Compliance.AccountCancellationSearchParams>({
    userId: undefined,
    miniProgramEnv: undefined
  })
  const drawerVisible = ref(false)
  const currentRecord = ref<Cancellation | null>(null)

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '原用户 ID',
      key: 'userId',
      type: 'input',
      props: { clearable: true, placeholder: '请输入完整用户 ID' }
    },
    {
      label: '小程序环境',
      key: 'miniProgramEnv',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '全部环境',
        options: [
          { label: '开发版', value: 'develop' },
          { label: '体验版', value: 'trial' },
          { label: '正式版', value: 'release' }
        ]
      }
    }
  ])

  const environmentLabel = (environment: Cancellation['miniProgramEnv']) =>
    ({ develop: '开发版', trial: '体验版', release: '正式版' })[environment]

  const showDetails = (row: Cancellation) => {
    currentRecord.value = row
    drawerVisible.value = true
  }

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
    refreshData
  } = useTable({
    core: {
      apiFn: fetchAccountCancellations,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: '注销记录 ID', minWidth: 190 },
        { prop: 'userId', label: '原用户 ID', minWidth: 190 },
        {
          prop: 'miniProgramEnv',
          label: '环境',
          width: 100,
          formatter: (row: Cancellation) => environmentLabel(row.miniProgramEnv)
        },
        { prop: 'noticeVersion', label: '须知版本', width: 130 },
        {
          prop: 'completedAt',
          label: '注销完成时间',
          width: 180,
          formatter: (row: Cancellation) => formatDateTime(row.completedAt)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 100,
          fixed: 'right',
          formatter: (row: Cancellation) =>
            h(
              ElButton,
              { link: true, type: 'primary', onClick: () => showDetails(row) },
              () => '查看详情'
            )
        }
      ]
    }
  })

  const handleSearch = (params: Api.Compliance.AccountCancellationSearchParams) => {
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = { userId: undefined, miniProgramEnv: undefined }
    resetSearchParams()
    getData()
  }
</script>

<style scoped lang="scss">
  .search-bar {
    margin-top: 12px;
  }

  .table-hint {
    font-size: 14px;
    color: var(--el-text-color-secondary);
  }

  .digest {
    overflow-wrap: anywhere;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
  }

  .category-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
</style>
