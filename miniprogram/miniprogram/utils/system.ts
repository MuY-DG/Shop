import type { AppLayoutMetrics } from "../types/app";

const DEFAULT_STATUS_BAR_HEIGHT = 20;
const DEFAULT_NAVIGATION_BAR_HEIGHT = 44;

interface MenuButtonRect {
  width: number;
  height: number;
  left: number;
  right: number;
  top: number;
  bottom: number;
}

export function getAppLayoutMetrics(): AppLayoutMetrics {
  const systemInfo = wx.getSystemInfoSync();
  let menuButton: MenuButtonRect;
  try {
    menuButton = wx.getMenuButtonBoundingClientRect();
  } catch {
    menuButton = {
      width: 87,
      height: 32,
      left: systemInfo.windowWidth - 94,
      right: systemInfo.windowWidth - 7,
      top: DEFAULT_STATUS_BAR_HEIGHT + 6,
      bottom: DEFAULT_STATUS_BAR_HEIGHT + 38
    };
  }

  const statusBarHeight = systemInfo.statusBarHeight ?? DEFAULT_STATUS_BAR_HEIGHT;
  const capsuleGap = Math.max(menuButton.top - statusBarHeight, 0);
  const navigationBarHeight = Math.max(
    menuButton.height + capsuleGap * 2,
    DEFAULT_NAVIGATION_BAR_HEIGHT
  );
  const safeAreaBottom = systemInfo.safeArea
    ? Math.max(systemInfo.screenHeight - systemInfo.safeArea.bottom, 0)
    : 0;

  return {
    statusBarHeight,
    navigationBarHeight,
    menuButtonWidth: menuButton.width,
    menuButtonRight: Math.max(systemInfo.windowWidth - menuButton.right, 0),
    safeAreaBottom,
    windowWidth: systemInfo.windowWidth
  };
}
