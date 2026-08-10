import request from '@/utils/http'

/**
 * 登录
 * @param params 登录参数
 * @returns 登录响应
 */
export function fetchLogin(params: Api.Auth.LoginParams) {
  return request.post<Api.Auth.LoginResponse>({
    url: '/admin/auth/login',
    data: params
    // showSuccessMessage: true // 显示成功消息
    // showErrorMessage: false // 不显示错误消息
  })
}

/** 查询管理员自助注册是否开放。失败时调用方必须按关闭处理。 */
export function fetchAdminRegistrationAvailability() {
  return request.get<Api.Auth.RegistrationAvailability>({
    url: '/admin/auth/registration',
    showErrorMessage: false
  })
}

/** 注册一个仅绑定游客角色的后台账号。 */
export function registerGuestAdmin(params: Api.Auth.RegistrationParams) {
  return request.post<number>({
    url: '/admin/auth/register',
    data: params
  })
}

/**
 * 获取用户信息
 * @returns 用户信息
 */
export function fetchGetUserInfo() {
  return request.get<Api.Auth.UserInfo>({
    url: '/admin/auth/current-user'
    // 自定义请求头
    // headers: {
    //   'X-Custom-Header': 'your-custom-value'
    // }
  })
}

/** 更新当前管理员的真实基础资料。 */
export function updateAdminProfile(params: Api.Auth.ProfileUpdateParams) {
  return request.put<Api.Auth.UserInfo>({
    url: '/admin/auth/profile',
    data: params
  })
}

/** 校验当前密码后修改密码；成功会使全部管理员会话失效。 */
export function changeAdminPassword(params: Api.Auth.PasswordChangeParams) {
  return request.put<void>({
    url: '/admin/auth/password',
    data: params
  })
}

/**
 * 主动退出并撤销当前管理员会话
 */
export function fetchLogout() {
  return request.post<void>({
    url: '/admin/auth/logout',
    showErrorMessage: false
  })
}

/** 获取当前管理员账号的全部登录设备。 */
export function fetchMyAdminSessions() {
  return request.get<Api.Auth.AdminSession[]>({
    url: '/admin/auth/sessions'
  })
}

/** 下线当前管理员账号的指定设备。 */
export function revokeMyAdminSession(sessionId: string) {
  return request.del<void>({
    url: `/admin/auth/sessions/${encodeURIComponent(sessionId)}`
  })
}

/** 退出当前管理员账号的所有设备。 */
export function logoutAllAdminSessions() {
  return request.post<void>({
    url: '/admin/auth/logout-all'
  })
}
