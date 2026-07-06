import type { AppLoginResponse, PhoneAuthorizeResponse } from "../types/api";
import { request } from "../utils/request";

const APP_TOKEN_KEY = "shop_app_token";
const APP_REFRESH_TOKEN_KEY = "shop_app_refresh_token";

function wxLogin(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (result) => {
        if (result.code) {
          resolve(result.code);
          return;
        }
        reject(new Error("微信登录失败"));
      },
      fail: (error) => {
        reject(new Error(error.errMsg));
      }
    });
  });
}

export function restoreStoredToken(): string {
  return wx.getStorageSync(APP_TOKEN_KEY) || "";
}

export async function silentLogin(): Promise<AppLoginResponse> {
  const code = await wxLogin();
  const response = await request<AppLoginResponse>({
    url: "/app/auth/login",
    method: "POST",
    auth: false,
    data: { code }
  });

  wx.setStorageSync(APP_TOKEN_KEY, response.token);
  wx.setStorageSync(APP_REFRESH_TOKEN_KEY, response.refreshToken);

  const app = getApp<{
    globalData: {
      apiBaseUrl: string;
      token: string;
    };
  }>();
  app.globalData.token = response.token;

  return response;
}

export function authorizePhone(code: string): Promise<PhoneAuthorizeResponse> {
  return request<PhoneAuthorizeResponse>({
    url: "/app/auth/phone",
    method: "POST",
    data: { code }
  });
}
