const ADMIN_DEVICE_ID_STORAGE_KEY = 'shop-admin-device-id'
const DEVICE_ID_PATTERN = /^[a-zA-Z0-9_-]{16,128}$/

let memoryDeviceId = ''

function createDeviceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  const randomPart = Math.random().toString(36).slice(2)
  return `${Date.now().toString(36)}-${randomPart}-${Math.random().toString(36).slice(2)}`
}

/**
 * 返回当前浏览器资料的稳定设备标识。
 *
 * 该标识独立于登录状态，退出登录时不能删除；清理浏览器站点数据后会重新生成。
 */
export function getOrCreateAdminDeviceId(): string {
  if (memoryDeviceId) {
    return memoryDeviceId
  }

  if (typeof window === 'undefined') {
    memoryDeviceId = createDeviceId()
    return memoryDeviceId
  }

  try {
    const stored = window.localStorage.getItem(ADMIN_DEVICE_ID_STORAGE_KEY)
    if (stored && DEVICE_ID_PATTERN.test(stored)) {
      memoryDeviceId = stored
      return memoryDeviceId
    }

    memoryDeviceId = createDeviceId()
    window.localStorage.setItem(ADMIN_DEVICE_ID_STORAGE_KEY, memoryDeviceId)
    return memoryDeviceId
  } catch {
    // localStorage 被浏览器禁用时，至少在当前页面生命周期内保持稳定。
    memoryDeviceId = createDeviceId()
    return memoryDeviceId
  }
}
