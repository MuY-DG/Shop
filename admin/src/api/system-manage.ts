import request from '@/utils/http'
import { AppRouteRecord } from '@/types/router'

// 获取用户列表
export function fetchGetUserList(params: Api.SystemManage.UserSearchParams) {
  return request.get<Api.SystemManage.UserList>({
    url: '/admin/system/users',
    params
  })
}

export function createAdminUser(data: Api.SystemManage.UserCreateForm) {
  return request.post<number>({
    url: '/admin/system/users',
    data
  })
}

export function updateAdminUser(userId: number, data: Api.SystemManage.UserUpdateForm) {
  return request.put<void>({
    url: `/admin/system/users/${userId}`,
    data
  })
}

export function disableAdminUser(userId: number) {
  return request.del<void>({
    url: `/admin/system/users/${userId}`
  })
}

// 获取角色列表
export function fetchGetRoleList(params: Api.SystemManage.RoleSearchParams) {
  return request.get<Api.SystemManage.RoleList>({
    url: '/admin/system/roles',
    params
  })
}

export function createAdminRole(data: Api.SystemManage.RoleForm) {
  return request.post<number>({
    url: '/admin/system/roles',
    data
  })
}

export function updateAdminRole(roleId: number, data: Api.SystemManage.RoleForm) {
  return request.put<void>({
    url: `/admin/system/roles/${roleId}`,
    data
  })
}

export function deleteAdminRole(roleId: number) {
  return request.del<void>({
    url: `/admin/system/roles/${roleId}`
  })
}

export function fetchAdminRoleGrants(roleId: number) {
  return request.get<Api.SystemManage.RoleGrants>({
    url: `/admin/system/roles/${roleId}/grants`
  })
}

export function updateAdminRoleGrants(roleId: number, data: Api.SystemManage.RoleGrantForm) {
  return request.put<void>({
    url: `/admin/system/roles/${roleId}/grants`,
    data
  })
}

// 获取菜单列表
export function fetchGetMenuList() {
  return request.get<AppRouteRecord[]>({
    url: '/admin/system/menus'
  })
}

export function fetchAdminAccessCatalog() {
  return request.get<AppRouteRecord[]>({
    url: '/admin/system/access-catalog'
  })
}

export function fetchGetMenuResourceCatalog() {
  return request.get<AppRouteRecord[]>({
    url: '/admin/system/access-catalog'
  })
}
