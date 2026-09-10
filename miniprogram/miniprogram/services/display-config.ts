import { APP_CONFIG } from "../config/app-config";
import { API_ENDPOINTS } from "../constants/api-endpoints";
import { DisplayConfigStore } from "../features/display-config";
import { request } from "../utils/request";

// 同设备上的开发、体验、正式环境不共享展示名称缓存。
const STORAGE_KEY = `${APP_CONFIG.storageNamespace}:display-name:v1`;
const store = new DisplayConfigStore({
  defaultName: APP_CONFIG.appName,
  readCache: () => wx.getStorageSync(STORAGE_KEY),
  writeCache: (name) => wx.setStorageSync(STORAGE_KEY, name),
  load: () => request<unknown>({
    url: API_ENDPOINTS.displayConfig,
    method: "GET",
    auth: false
  })
});

export const getDisplayName = (): string => store.getDisplayName();
export const refreshDisplayConfig = (force = false): Promise<string> => store.refresh(force);

interface DisplayNamePage {
  setData(data: { displayName: string }): void;
}

const bindings = new WeakMap<DisplayNamePage, () => void>();

export function bindDisplayName(page: DisplayNamePage, onChange?: (name: string) => void): void {
  unbindDisplayName(page);
  bindings.set(page, store.subscribe(onChange || ((displayName) => page.setData({ displayName }))));
  void refreshDisplayConfig();
}

export function unbindDisplayName(page: DisplayNamePage): void {
  bindings.get(page)?.();
  bindings.delete(page);
}
