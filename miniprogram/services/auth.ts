import type { AppLoginResponse, PhoneAuthorizeResponse } from "../types/api";
import { request } from "../utils/request";

const APP_TOKEN_KEY = "shop_app_token";
const APP_REFRESH_TOKEN_KEY = "shop_app_refresh_token";

function getAppTokenState() {
  return getApp<{
    globalData: {
      apiBaseUrl: string;
      token: string;
    };
  }>();
}

function setAppToken(token: string): void {
  getAppTokenState().globalData.token = token;
}

function persistAppTokens(token: string, refreshToken: string): void {
  try {
    wx.setStorageSync(APP_TOKEN_KEY, token);
    wx.setStorageSync(APP_REFRESH_TOKEN_KEY, refreshToken);
    setAppToken(token);
  } catch (error) {
    clearAppTokens();
    throw new Error("Failed to persist app tokens");
  }
}

export function clearAppTokens(): void {
  try {
    wx.removeStorageSync(APP_TOKEN_KEY);
  } catch (error) {}

  try {
    wx.removeStorageSync(APP_REFRESH_TOKEN_KEY);
  } catch (error) {}

  setAppToken("");
}

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
  try {
    const token = wx.getStorageSync(APP_TOKEN_KEY);
    return typeof token === "string" ? token : "";
  } catch (error) {
    return "";
  }
}

export async function silentLogin(): Promise<AppLoginResponse> {
  const code = await wxLogin();
  const response = await request<AppLoginResponse>({
    url: "/app/auth/login",
    method: "POST",
    auth: false,
    data: { code }
  });

  persistAppTokens(response.token, response.refreshToken);

  return response;
}

export async function ensureAppLogin(): Promise<void> {
  const app = getAppTokenState();
  if (app.globalData.token) {
    return;
  }

  await silentLogin();
}

export function authorizePhone(code: string): Promise<PhoneAuthorizeResponse> {
  return request<PhoneAuthorizeResponse>({
    url: "/app/auth/phone",
    method: "POST",
    data: { code }
  });
}
