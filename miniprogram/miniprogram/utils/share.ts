export const DEFAULT_SHARE_TITLE = "灶香集｜把地道好味分享给你";

/**
 * 开启微信右上角“发送给朋友”和“分享到朋友圈”原生菜单。
 * 页面仍需分别声明 onShareAppMessage 和 onShareTimeline。
 */
export function enableNativeShareMenu(): void {
  wx.showShareMenu({
    menus: ["shareAppMessage", "shareTimeline"]
  });
}
