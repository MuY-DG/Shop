<template>
  <div class="user-page art-full-height">
    <UserSearch v-model="searchForm" @search="handleSearch" @reset="resetSearchParams" />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton v-auth="'system:user:create'" type="primary" @click="showDialog('add')">
            新增用户
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

      <UserDialog
        v-model:visible="dialogVisible"
        :type="dialogType"
        :user-data="currentUserData"
        @success="refreshData"
      />
    </ElCard>

    <AdminSessionDrawer
      v-model="sessionDrawerVisible"
      mode="managed"
      :user-id="sessionUser?.id"
      :user-name="sessionUser?.displayName"
      :can-revoke="canRevokeSessions"
      @changed="refreshData"
    />
  </div>
</template>

<script setup lang="ts">
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import AdminSessionDrawer from '@/components/business/admin-session-drawer/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { useAuth } from '@/hooks/core/useAuth'
  import { formatLocalDateTime as formatDateTime } from '@/utils/date-time'
  import { disableAdminUser, fetchGetUserList } from '@/api/system-manage'
  import UserSearch from './modules/user-search.vue'
  import UserDialog from './modules/user-dialog.vue'
  import type { DialogType } from '@/types'
  import { ElAvatar, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'User' })

  type UserListItem = Api.SystemManage.UserListItem

  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const currentUserData = ref<UserListItem>()
  const sessionDrawerVisible = ref(false)
  const sessionUser = ref<UserListItem>()
  const { hasAuth } = useAuth()
  const canViewSessions = computed(
    () => hasAuth('system:user:session:read') || hasAuth('system:user:session:revoke')
  )
  const canRevokeSessions = computed(() => hasAuth('system:user:session:revoke'))
  const searchForm = ref<Api.SystemManage.UserSearchParams>({
    username: undefined,
    email: undefined,
    status: undefined
  })

  const formatSessionLimit = (maxSessions: number) => {
    if (maxSessions === 0) return '不限'
    if (maxSessions === 1) return '单设备'
    return `最多 ${maxSessions} 台`
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
      apiFn: fetchGetUserList,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        { type: 'index', width: 60, label: '序号' },
        {
          prop: 'username',
          label: '用户',
          minWidth: 220,
          formatter: (row: UserListItem) =>
            h('div', { class: 'flex items-center gap-2' }, [
              h(ElAvatar, { size: 36, src: row.avatar }, () => row.displayName.slice(0, 1)),
              h('div', [
                h('p', { class: 'font-medium' }, row.displayName),
                h('p', { class: 'text-xs text-gray-400' }, row.username)
              ])
            ])
        },
        { prop: 'email', label: '邮箱', minWidth: 200 },
        {
          prop: 'roleCodes',
          label: '角色',
          minWidth: 180,
          formatter: (row: UserListItem) =>
            h(
              'div',
              { class: 'flex flex-wrap gap-1' },
              row.roleCodes.map((role) => h(ElTag, { size: 'small' }, () => role))
            )
        },
        {
          prop: 'maxSessions',
          label: '登录限制',
          width: 120,
          formatter: (row: UserListItem) =>
            h(
              ElTag,
              {
                type: row.maxSessions === 1 ? 'warning' : 'info',
                effect: 'plain',
                size: 'small'
              },
              () => formatSessionLimit(row.maxSessions)
            )
        },
        {
          prop: 'status',
          label: '状态',
          width: 100,
          formatter: (row: UserListItem) =>
            h(ElTag, { type: row.status === 'ENABLED' ? 'success' : 'info' }, () =>
              row.status === 'ENABLED' ? '启用' : '停用'
            )
        },
        {
          prop: 'lastLoginAt',
          label: '最后登录',
          width: 180,
          formatter: (row: UserListItem) => formatDateTime(row.lastLoginAt)
        },
        {
          prop: 'createdAt',
          label: '创建时间',
          width: 180,
          formatter: (row: UserListItem) => formatDateTime(row.createdAt)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 90,
          fixed: 'right',
          formatter: (row: UserListItem) =>
            h(ArtButtonMore, {
              list: [
                ...(canViewSessions.value
                  ? [
                      {
                        key: 'sessions',
                        label: '登录设备',
                        icon: 'ri:computer-line'
                      }
                    ]
                  : []),
                {
                  key: 'edit',
                  label: '编辑管理员',
                  icon: 'ri:edit-2-line',
                  auth: 'system:user:update'
                },
                {
                  key: 'disable',
                  label: '停用管理员',
                  icon: 'ri:user-forbid-line',
                  color: '#f56c6c',
                  auth: 'system:user:disable',
                  disabled: row.status === 'DISABLED'
                }
              ],
              onClick: (item: ButtonMoreItem) => {
                if (item.key === 'sessions') showSessionDrawer(row)
                if (item.key === 'edit') showDialog('edit', row)
                if (item.key === 'disable') disableUser(row)
              }
            })
        }
      ]
    }
  })

  const handleSearch = (params: Api.SystemManage.UserSearchParams) => {
    replaceSearchParams(params)
    getData()
  }

  const showDialog = (type: DialogType, row?: UserListItem) => {
    dialogType.value = type
    currentUserData.value = row
    dialogVisible.value = true
  }

  const showSessionDrawer = (row: UserListItem) => {
    sessionUser.value = row
    sessionDrawerVisible.value = true
  }

  const disableUser = async (row: UserListItem) => {
    try {
      await ElMessageBox.confirm(
        `确定停用管理员“${row.displayName}”吗？停用后该管理员的所有登录设备将立即下线。`,
        '停用确认',
        {
          confirmButtonText: '停用并下线',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
      await disableAdminUser(row.id)
      ElMessage.success('管理员已停用')
      if (sessionUser.value?.id === row.id) {
        sessionDrawerVisible.value = false
      }
      await refreshData()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    }
  }
</script>
