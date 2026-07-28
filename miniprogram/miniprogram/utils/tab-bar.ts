interface CustomTabBarInstance {
  setData(data: { selected: number }): void;
  setHidden?: (hidden: boolean) => void;
  refreshCartCount?: () => Promise<void> | void;
  setCartCount?: (count: number) => void;
}

interface CustomTabBarHost {
  getTabBar?: () => CustomTabBarInstance | undefined;
}

export function syncCustomTabBar(host: CustomTabBarHost, selected: number): void {
  const tabBar = host.getTabBar?.();
  if (tabBar) {
    tabBar.setData({ selected });
    tabBar.setHidden?.(false);
    void tabBar.refreshCartCount?.();
  }
}

export function setCustomTabBarHidden(
  host: CustomTabBarHost,
  hidden: boolean
): void {
  host.getTabBar?.()?.setHidden?.(hidden);
}

export function setCustomTabBarCartCount(
  host: CustomTabBarHost,
  count: number
): void {
  host.getTabBar?.()?.setCartCount?.(count);
}

export function refreshCustomTabBarCartCount(
  host: CustomTabBarHost
): Promise<void> {
  return Promise.resolve(host.getTabBar?.()?.refreshCartCount?.());
}
