interface CustomTabBarInstance {
  setData(data: { selected: number }): void;
}

interface CustomTabBarHost {
  getTabBar?: () => CustomTabBarInstance | undefined;
}

export function syncCustomTabBar(host: CustomTabBarHost, selected: number): void {
  const tabBar = host.getTabBar?.();
  if (tabBar) {
    tabBar.setData({ selected });
  }
}
