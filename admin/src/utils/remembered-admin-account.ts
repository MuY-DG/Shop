const REMEMBERED_ADMIN_ACCOUNT_KEY = 'shop-admin-remembered-account'

export interface AdminAccountStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export function loadRememberedAdminAccount(storage: AdminAccountStorage = localStorage): string {
  return storage.getItem(REMEMBERED_ADMIN_ACCOUNT_KEY)?.trim() ?? ''
}

export function saveRememberedAdminAccount(
  username: string,
  remember: boolean,
  storage: AdminAccountStorage = localStorage
): void {
  const normalized = username.trim()
  if (remember && normalized) {
    storage.setItem(REMEMBERED_ADMIN_ACCOUNT_KEY, normalized)
    return
  }
  storage.removeItem(REMEMBERED_ADMIN_ACCOUNT_KEY)
}

export const rememberedAdminAccountStorageKey = REMEMBERED_ADMIN_ACCOUNT_KEY
