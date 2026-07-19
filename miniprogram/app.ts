import { restoreSession } from "./services/session";
import { flushAnalytics, initializeAnalytics } from "./services/analytics";

App<IAppOption>({
  globalData: {
    apiBaseUrl: "https://pay-dev.muybaby6.icu"
  },
  onLaunch(options) {
    restoreSession();
    initializeAnalytics(String(options.scene ?? ""));
    wx.getSystemInfo({
      success: (info) => {
        this.globalData.systemInfo = info;
      }
    });
  },
  onHide() {
    void flushAnalytics();
  }
});

interface IAppOption {
  globalData: {
    apiBaseUrl: string;
    systemInfo?: WechatMiniprogram.SystemInfo;
  };
}
