<template>
  <ElDialog
    v-model="visible"
    :title="`配置权限${roleData ? ` - ${roleData.name}` : ''}`"
    width="560px"
    align-center
    destroy-on-close
  >
    <div v-loading="loading" class="permission-tree">
      <ElTree
        ref="treeRef"
        :data="accessTree"
        show-checkbox
        node-key="key"
        default-expand-all
        :props="{ children: 'children', label: 'label' }"
      />
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

  type RoleListItem = Api.SystemManage.RoleListItem

  interface AccessTreeNode {
    key: string
    id: number
    label: string
    kind: 'menu' | 'permission'
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

  const toAccessNode = (route: AppRouteRecord): AccessTreeNode => {
    const menuChildren = (route.children || []).map(toAccessNode)
    const permissionChildren = (route.meta.authList || []).map((permission) => ({
      key: `permission:${permission.id}`,
      id: Number(permission.id),
      label: permission.title || permission.authMark,
      kind: 'permission' as const
    }))
    return {
      key: `menu:${route.id}`,
      id: Number(route.id),
      label: formatMenuTitle(route.meta.title) || String(route.name || route.path),
      kind: 'menu',
      children: [...menuChildren, ...permissionChildren]
    }
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
      accessTree.value = catalog.map(toAccessNode)
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

  const savePermission = async () => {
    if (!props.roleData || !treeRef.value) return
    const checkedNodes = treeRef.value.getCheckedNodes(false, false) as AccessTreeNode[]
    const halfCheckedNodes = treeRef.value.getHalfCheckedNodes() as AccessTreeNode[]
    const menuIds = [...checkedNodes, ...halfCheckedNodes]
      .filter((node) => node.kind === 'menu')
      .map((node) => node.id)
      .filter((id, index, values) => values.indexOf(id) === index)
    const permissionIds = checkedNodes
      .filter((node) => node.kind === 'permission')
      .map((node) => node.id)

    saving.value = true
    try {
      await updateAdminRoleGrants(props.roleData.id, { menuIds, permissionIds })
      ElMessage.success('角色权限保存成功')
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
    overflow: auto;
    padding: 8px 4px;
  }
</style>
