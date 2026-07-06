import { restoreStoredToken } from "./services/auth";

App<IAppOption>({
  globalData: {
    apiBaseUrl: "http://localhost:8080",
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
