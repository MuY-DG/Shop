<template>
  <ElDialog
    v-model="visible"
    :title="`授权配置${roleData ? ` - ${roleData.name}` : ''}`"
    width="680px"
    align-center
    destroy-on-close
  >
    <ElAlert
      title="页面访问决定导航和路由；操作权限同时控制前端按钮和后端 API。勾选页面不会自动授予页面下的所有操作。"
      type="info"
      show-icon
      :closable="false"
      class="mb-3"
    />

    <div v-loading="loading" class="permission-tree">
      <ElTree
        ref="treeRef"
        :data="accessTree"
        show-checkbox
        node-key="key"
        default-expand-all
        check-strictly
        :props="{ children: 'children', label: 'label' }"
        @check="handleTreeCheck"
      >
        <template #default="{ data }">
          <div class="permission-node">
            <ElTag size="small" effect="plain" :type="resourceTagType(data.resourceKind)">
              {{ resourceKindLabel(data.resourceKind) }}
            </ElTag>
            <span>{{ data.label }}</span>
            <code v-if="data.authMark" class="permission-mark">{{ data.authMark }}</code>
          </div>
        </template>
      </ElTree>
    </div>

    <template #footer>
      <ElButton @click="toggleSelectAll">{{ allSelected ? '取消全选' : '全部选择' }}</ElButton>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="saving" @click="savePermission">保存</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { AppRouteRecord } from '@/types/router'
  import { formatMenuTitle } from '@/utils/router'
  import {
    fetchAdminAccessCatalog,
    fetchAdminRoleGrants,
    updateAdminRoleGrants
  } from '@/api/system-manage'
  import { ElMessage } from 'element-plus'
  import { buildRoleGrantForm, type RoleGrantSelectionNode } from './role-grant-selection'

  type RoleListItem = Api.SystemManage.RoleListItem

  interface AccessTreeNode extends RoleGrantSelectionNode {
    key: string
    label: string
    resourceKind: 'directory' | 'page' | 'permission'
    authMark?: string
    children?: AccessTreeNode[]
  }

  const props = withDefaults(
    defineProps<{
      modelValue: boolean
      roleData?: RoleListItem
    }>(),
    { modelValue: false, roleData: undefined }
  )
  const emit = defineEmits<{
    (e: 'update:modelValue', value: boolean): void
    (e: 'success'): void
  }>()

  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })
  const treeRef = ref()
  const accessTree = ref<AccessTreeNode[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const allSelected = ref(false)

  const toAccessNode = (route: AppRouteRecord, ancestorMenuIds: number[] = []): AccessTreeNode => {
    const routeId = Number(route.id)
    const menuChildren = (route.children || []).map((child) =>
      toAccessNode(child, [...ancestorMenuIds, routeId])
    )
    const permissionChildren = (route.meta.authList || []).map((permission) => ({
      key: `permission:${permission.id}`,
      id: Number(permission.id),
      label: permission.title || permission.authMark,
      kind: 'permission' as const,
      resourceKind: 'permission' as const,
      authMark: permission.authMark,
      ancestorMenuIds: [...ancestorMenuIds, routeId]
    }))
    return {
      key: `menu:${route.id}`,
      id: routeId,
      label: formatMenuTitle(route.meta.title) || String(route.name || route.path),
      kind: 'menu',
      resourceKind:
        menuChildren.length > 0 || route.component === '/index/index' ? 'directory' : 'page',
      ancestorMenuIds,
      children: [...menuChildren, ...permissionChildren]
    }
  }

  const resourceKindLabel = (kind: AccessTreeNode['resourceKind']) => {
    if (kind === 'directory') return '目录'
    if (kind === 'page') return '页面'
    return '操作'
  }

  const resourceTagType = (
    kind: AccessTreeNode['resourceKind']
  ): 'info' | 'primary' | 'warning' => {
    if (kind === 'directory') return 'info'
    if (kind === 'page') return 'primary'
    return 'warning'
  }

  const flattenKeys = (nodes: AccessTreeNode[]): string[] =>
    nodes.flatMap((node) => [node.key, ...flattenKeys(node.children || [])])

  const loadPermission = async () => {
    if (!props.roleData) return
    loading.value = true
    try {
      const [catalog, grants] = await Promise.all([
        fetchAdminAccessCatalog(),
        fetchAdminRoleGrants(props.roleData.id)
      ])
      accessTree.value = catalog.map((route) => toAccessNode(route))
      const checkedKeys = [
        ...grants.menuIds.map((id) => `menu:${id}`),
        ...grants.permissionIds.map((id) => `permission:${id}`)
      ]
      await nextTick()
      treeRef.value?.setCheckedKeys(checkedKeys)
      allSelected.value = checkedKeys.length === flattenKeys(accessTree.value).length
    } finally {
      loading.value = false
    }
  }

  watch(
    () => props.modelValue,
    (open) => {
      if (open) loadPermission()
    }
  )

  const toggleSelectAll = () => {
    if (!treeRef.value) return
    treeRef.value.setCheckedKeys(allSelected.value ? [] : flattenKeys(accessTree.value))
    allSelected.value = !allSelected.value
  }

  const handleTreeCheck = (node: AccessTreeNode, state: { checkedKeys: string[] }) => {
    if (!treeRef.value || !state.checkedKeys.includes(node.key)) {
      allSelected.value = state.checkedKeys.length === flattenKeys(accessTree.value).length
      return
    }
    const ancestorKeys = node.ancestorMenuIds.map((id) => `menu:${id}`)
    const checkedKeys = [...new Set([...state.checkedKeys, ...ancestorKeys])]
    treeRef.value.setCheckedKeys(checkedKeys)
    allSelected.value = checkedKeys.length === flattenKeys(accessTree.value).length
  }

  const savePermission = async () => {
    if (!props.roleData || !treeRef.value) return
    const checkedNodes = treeRef.value.getCheckedNodes(false, false) as AccessTreeNode[]
    const grantForm = buildRoleGrantForm(checkedNodes)

    saving.value = true
    try {
      await updateAdminRoleGrants(props.roleData.id, grantForm)
      ElMessage.success('角色授权保存成功')
      visible.value = false
      emit('success')
    } finally {
      saving.value = false
    }
  }
</script>

<style scoped>
  .permission-tree {
    min-height: 320px;
    max-height: 60vh;
    padding: 8px 4px;
    overflow: auto;
  }

  .permission-node {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .permission-mark {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
</style>
