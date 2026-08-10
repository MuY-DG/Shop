import {
  APP_CONFIG,
  APP_ENV_VERSION,
  assertRuntimeConfig
} from "./config/app-config";
import {
  onSessionExpired,
  restoreSession
} from "./services/session";
import { handleWechatReceiptAppShow } from "./features/wechat-order-receipt";
import { replaceWithExpiredSessionLogin } from "./utils/login-navigation";
import { getAppLayoutMetrics } from "./utils/system";

App<IAppOption>({
  globalData: {
    config: APP_CONFIG,
    layout: getAppLayoutMetrics()
  },
  onLaunch() {
    assertRuntimeConfig(APP_ENV_VERSION, APP_CONFIG);
    restoreSession();
    onSessionExpired(() => {
      replaceWithExpiredSessionLogin();
    });
  },
  onShow(options) {
    handleWechatReceiptAppShow(options);
  }
});
