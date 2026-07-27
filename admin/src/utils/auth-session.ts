import { fetchLogout } from '@/api/auth'
import { useUserStore } from '@/store/modules/user'

let logoutPromise: Promise<void> | null = null

/**
 * 主动退出时先请求服务端撤销会话；无论网络结果如何都清理本地认证状态。
 */
export function logoutAdminSession(): Promise<void> {
  if (logoutPromise) {
    return logoutPromise
  }

  const userStore = useUserStore()
  logoutPromise = (async () => {
    try {
      if (userStore.accessToken) {
        await fetchLogout()
      }
    } catch (error) {
      console.warn('[Auth] 服务端会话撤销失败，本地登录状态仍会清理', error)
    } finally {
      userStore.logOut()
    }
  })().finally(() => {
    logoutPromise = null
  })

  return logoutPromise
}
