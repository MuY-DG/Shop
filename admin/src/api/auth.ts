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
