<template>
  <div class="art-full-height">
    <RoleSearch
      v-show="showSearchBar"
      v-model="searchForm"
      @search="handleSearch"
      @reset="resetSearchParams"
    />

    <ElCard class="art-table-card" :style="{ marginTop: showSearchBar ? '12px' : '0' }">
      <ArtTableHeader
        v-model:columns="columnChecks"
        v-model:showSearchBar="showSearchBar"
        :loading="loading"
        @refresh="refreshData"
      >
        <template #left>
          <ElButton v-auth="'system:role:create'" type="primary" @click="showDialog('add')">
            新增角色
          </ElButton>
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

    <RoleEditDialog
      v-model="dialogVisible"
      :dialog-type="dialogType"
      :role-data="currentRoleData"
      @success="refreshData"
    />

    <RolePermissionDialog
      v-model="permissionDialog"
      :role-data="currentRoleData"
      @success="refreshData"
    />
  </div>
</template>

<script setup lang="ts">
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { deleteAdminRole, fetchGetRoleList } from '@/api/system-manage'
  import RoleSearch from './modules/role-search.vue'
  import RoleEditDialog from './modules/role-edit-dialog.vue'
  import RolePermissionDialog from './modules/role-permission-dialog.vue'
  import { ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'Role' })

  type RoleListItem = Api.SystemManage.RoleListItem
  type RoleSearchFormParams = Api.SystemManage.RoleSearchParams & { daterange?: string[] }

  const searchForm = ref<RoleSearchFormParams>({
    name: undefined,
    code: undefined,
    enabled: undefined,
    daterange: undefined
  })
  const showSearchBar = ref(false)
  const dialogVisible = ref(false)
  const permissionDialog = ref(false)
  const currentRoleData = ref<RoleListItem>()
  const dialogType = ref<'add' | 'edit'>('add')

  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')

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
      apiFn: fetchGetRoleList,
      apiParams: { current: 1, size: 20 },
      excludeParams: ['daterange'],
      columnsFactory: () => [
        { prop: 'id', label: '角色ID', width: 100 },
        { prop: 'name', label: '角色名称', minWidth: 140 },
        { prop: 'code', label: '角色编码', minWidth: 140 },
        { prop: 'description', label: '角色描述', minWidth: 180, showOverflowTooltip: true },
        {
          prop: 'enabled',
          label: '状态',
          width: 100,
          formatter: (row: RoleListItem) =>
            h(ElTag, { type: row.enabled ? 'success' : 'warning' }, () =>
              row.enabled ? '启用' : '禁用'
            )
        },
        {
          prop: 'createdAt',
          label: '创建时间',
          width: 180,
          formatter: (row: RoleListItem) => formatDateTime(row.createdAt)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 90,
          fixed: 'right',
          formatter: (row: RoleListItem) =>
            h(ArtButtonMore, {
              list: [
                {
                  key: 'permission',
                  label: '授权配置',
                  icon: 'ri:shield-keyhole-line',
                  auth: 'system:role:assign'
                },
                {
                  key: 'edit',
                  label: '编辑角色',
                  icon: 'ri:edit-2-line',
                  auth: 'system:role:update'
                },
                {
                  key: 'delete',
                  label: '删除角色',
                  icon: 'ri:delete-bin-4-line',
                  color: '#f56c6c',
                  auth: 'system:role:delete'
                }
              ],
              onClick: (item: ButtonMoreItem) => buttonMoreClick(item, row)
            })
        }
      ]
    }
  })

  const showDialog = (type: 'add' | 'edit', row?: RoleListItem) => {
    dialogType.value = type
    currentRoleData.value = row
    dialogVisible.value = true
  }

  const handleSearch = (params: RoleSearchFormParams) => {
    const { daterange, ...filters } = params
    const [startTime, endTime] = Array.isArray(daterange) ? daterange : [null, null]
    replaceSearchParams({ ...filters, startTime, endTime })
    getData()
  }

  const buttonMoreClick = (item: ButtonMoreItem, row: RoleListItem) => {
    if (item.key === 'permission') {
      currentRoleData.value = row
      permissionDialog.value = true
    } else if (item.key === 'edit') {
      showDialog('edit', row)
    } else if (item.key === 'delete') {
      deleteRole(row)
    }
  }

  const deleteRole = async (row: RoleListItem) => {
    try {
      await ElMessageBox.confirm(`确定删除角色“${row.name}”吗？`, '删除确认', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await deleteAdminRole(row.id)
      ElMessage.success('角色已删除')
      await refreshData()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    }
  }
</script>
