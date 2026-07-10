import { restoreSession } from "./services/session";

App<IAppOption>({
  globalData: {
    apiBaseUrl: "https://pay-dev.muybaby6.icu"
  },
  onLaunch() {
    restoreSession();
    wx.getSystemInfo({
      success: (info) => {
        this.globalData.systemInfo = info;
      }
    });
  }
});

interface IAppOption {
  globalData: {
    apiBaseUrl: string;
    systemInfo?: WechatMiniprogram.SystemInfo;
  };
}
