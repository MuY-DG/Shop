import {
  APP_CONFIG,
  assertRuntimeConfig,
  type MiniProgramEnvVersion
} from "./config/app-config";
import { restoreSession } from "./services/session";
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
  }
});
