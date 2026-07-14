export interface RoleGrantSelectionNode {
  id: number
  kind: 'menu' | 'permission'
  ancestorMenuIds: number[]
}

export function buildRoleGrantForm(nodes: RoleGrantSelectionNode[]) {
  const menuIds = new Set<number>()
  const permissionIds = new Set<number>()

  nodes.forEach((node) => {
    if (node.kind === 'menu') {
      menuIds.add(node.id)
    } else {
      permissionIds.add(node.id)
    }
    node.ancestorMenuIds.forEach((menuId) => menuIds.add(menuId))
  })

  return {
    menuIds: [...menuIds].sort((left, right) => left - right),
    permissionIds: [...permissionIds].sort((left, right) => left - right)
  }
}
