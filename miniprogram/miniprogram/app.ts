import {
  APP_CONFIG,
  assertRuntimeConfig,
  type MiniProgramEnvVersion
} from "./config/app-config";
import {
  onSessionExpired,
  restoreSession
} from "./services/session";
import { replaceWithExpiredSessionLogin } from "./utils/login-navigation";
import { getAppLayoutMetrics } from "./utils/system";

function getEnvVersion(): MiniProgramEnvVersion {
  try {
    return wx.getAccountInfoSync().miniProgram.envVersion;
  } catch {
    return "develop";
  }
}

App<IAppOption>({
  globalData: {
    config: APP_CONFIG,
    layout: getAppLayoutMetrics()
  },
  onLaunch() {
    assertRuntimeConfig(getEnvVersion());
    restoreSession();
    onSessionExpired(() => {
      replaceWithExpiredSessionLogin();
    });
  }
});
