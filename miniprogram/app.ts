import { restoreStoredToken } from "./services/auth";

App<IAppOption>({
  globalData: {
    apiBaseUrl: "https://pay-dev.muybaby6.icu",
    token: restoreStoredToken()
  },
  onLaunch() {
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
    token: string;
    systemInfo?: WechatMiniprogram.SystemInfo;
  };
}
