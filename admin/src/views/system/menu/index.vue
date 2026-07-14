<template>
  <div class="menu-page art-full-height">
    <ElAlert
      title="菜单与权限资源由代码和数据库迁移维护，本页仅用于查看系统当前可授权的资源。"
      type="info"
      show-icon
      :closable="false"
      class="mb-3"
    />

    <ArtSearchBar
      v-model="formFilters"
      :items="formItems"
      :showExpand="false"
      :labelWidth="90"
      @reset="handleReset"
      @search="handleSearch"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader
        v-model:columns="columnChecks"
        :showZebra="false"
        :loading="loading"
        @refresh="loadResourceCatalog"
      >
        <template #left>
          <ElButton v-ripple @click="toggleExpand">
            {{ isExpanded ? '收起全部' : '展开全部' }}
          </ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        ref="tableRef"
        rowKey="resourceKey"
        :loading="loading"
        :columns="columns"
        :data="filteredRows"
        :stripe="false"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
        :default-expand-all="false"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { fetchGetMenuResourceCatalog } from '@/api/system-manage'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import type { AppRouteRecord } from '@/types/router'
  import { formatMenuTitle } from '@/utils/router'

  defineOptions({ name: 'Menus' })

  type ResourceKind = 'directory' | 'page' | 'permission'

  interface ResourceRow extends AppRouteRecord {
    resourceKey: string
    resourceKind: ResourceKind
    permissionMark?: string
    children?: ResourceRow[]
  }

  const loading = ref(false)
  const isExpanded = ref(false)
  const tableRef = ref()
  const catalog = ref<AppRouteRecord[]>([])

  const initialSearchState = {
    name: '',
    address: ''
  }
  const formFilters = reactive({ ...initialSearchState })
  const appliedFilters = reactive({ ...initialSearchState })
  const formItems = computed(() => [
    {
      label: '资源名称',
      key: 'name',
      type: 'input',
      props: { clearable: true, placeholder: '菜单或操作名称' }
    },
    {
      label: '地址或标识',
      key: 'address',
      type: 'input',
      props: { clearable: true, placeholder: '路由、组件或权限标识' }
    }
  ])

  const resourceKindLabel = (kind: ResourceKind) => {
    if (kind === 'directory') return '目录'
    if (kind === 'page') return '页面'
    return '操作权限'
  }

  const resourceKindTag = (kind: ResourceKind): 'info' | 'primary' | 'warning' => {
    if (kind === 'directory') return 'info'
    if (kind === 'page') return 'primary'
    return 'warning'
  }

  const toResourceRow = (route: AppRouteRecord): ResourceRow => {
    const menuChildren = (route.children || []).map(toResourceRow)
    const permissionChildren: ResourceRow[] = (route.meta.authList || []).map((permission) => ({
      id: permission.id,
      name: `Permission_${permission.id}`,
      path: '',
      meta: {
        title: permission.title || permission.authMark,
        authMark: permission.authMark,
        isAuthButton: true
      },
      resourceKey: `permission:${permission.id}`,
      resourceKind: 'permission',
      permissionMark: permission.authMark,
      children: []
    }))
    const isDirectory = menuChildren.length > 0 || route.component === '/index/index'

    return {
      ...route,
      resourceKey: `menu:${route.id}`,
      resourceKind: isDirectory ? 'directory' : 'page',
      children: [...menuChildren, ...permissionChildren]
    }
  }

  const resourceRows = computed(() => catalog.value.map(toResourceRow))

  const filterRows = (rows: ResourceRow[]): ResourceRow[] => {
    const nameQuery = appliedFilters.name.toLowerCase().trim()
    const addressQuery = appliedFilters.address.toLowerCase().trim()

    return rows.flatMap((row) => {
      const title = formatMenuTitle(row.meta.title).toLowerCase()
      const path = String(row.path || '').toLowerCase()
      const component = typeof row.component === 'string' ? row.component.toLowerCase() : ''
      const permissionMark = (row.permissionMark || '').toLowerCase()
      const nameMatches = !nameQuery || title.includes(nameQuery)
      const addressMatches =
        !addressQuery ||
        path.includes(addressQuery) ||
        component.includes(addressQuery) ||
        permissionMark.includes(addressQuery)
      const childMatches = row.children?.length ? filterRows(row.children) : []

      if (nameMatches && addressMatches) {
        return [row]
      }
      if (childMatches.length > 0) {
        return [{ ...row, children: childMatches }]
      }
      return []
    })
  }

  const filteredRows = computed(() => filterRows(resourceRows.value))

  const { columnChecks, columns } = useTableColumns(() => [
    {
      prop: 'meta.title',
      label: '资源名称',
      minWidth: 190,
      formatter: (row: ResourceRow) => formatMenuTitle(row.meta.title)
    },
    {
      prop: 'resourceKind',
      label: '资源类型',
      width: 110,
      formatter: (row: ResourceRow) =>
        h(ElTag, { type: resourceKindTag(row.resourceKind), effect: 'plain' }, () =>
          resourceKindLabel(row.resourceKind)
        )
    },
    {
      prop: 'path',
      label: '路由',
      minWidth: 170,
      formatter: (row: ResourceRow) =>
        row.resourceKind === 'permission' ? '' : String(row.meta.link || row.path || '')
    },
    {
      prop: 'component',
      label: '组件',
      minWidth: 190,
      formatter: (row: ResourceRow) =>
        row.resourceKind !== 'permission' && typeof row.component === 'string' ? row.component : ''
    },
    {
      prop: 'permissionMark',
      label: '权限标识',
      minWidth: 220,
      formatter: (row: ResourceRow) => row.permissionMark || ''
    }
  ])

  const loadResourceCatalog = async () => {
    loading.value = true
    try {
      catalog.value = await fetchGetMenuResourceCatalog()
    } finally {
      loading.value = false
    }
  }

  const handleReset = () => {
    Object.assign(formFilters, initialSearchState)
    Object.assign(appliedFilters, initialSearchState)
  }

  const handleSearch = () => {
    Object.assign(appliedFilters, formFilters)
  }

  const toggleExpand = () => {
    isExpanded.value = !isExpanded.value
    nextTick(() => {
      const processRows = (rows: ResourceRow[]) => {
        rows.forEach((row) => {
          if (row.children?.length) {
            tableRef.value?.elTableRef?.toggleRowExpansion(row, isExpanded.value)
            processRows(row.children)
          }
        })
      }
      processRows(filteredRows.value)
    })
  }

  onMounted(loadResourceCatalog)
</script>
