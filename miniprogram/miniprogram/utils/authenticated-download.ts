import { APP_CONFIG } from "../config/app-config";
import {
  clearSessionIfCurrent,
  ensureSession,
  getSessionState,
  recoverAfterUnauthorized
} from "../services/session";
import { ApiError } from "./api-error";

interface DownloadResult {
  statusCode: number;
  tempFilePath: string;
  authTokenUsed: string | null;
}

function buildUrl(path: string): string {
  const origin = APP_CONFIG.apiBaseUrl.replace(/\/$/, "");
  if (/^https?:\/\//i.test(path)) {
    if (path !== origin && !path.startsWith(`${origin}/`)) {
      throw new ApiError({
        kind: "PROTOCOL",
        message: "禁止向非 API 域名发送登录凭证"
      });
    }
    return path;
  }
  return `${origin}${path.startsWith("/") ? path : `/${path}`}`;
}

function rawDownload(path: string, token: string | null): Promise<DownloadResult> {
  const authTokenUsed = token?.trim() || null;
  const header: Record<string, string> = {};
  if (authTokenUsed) {
    header.Authorization = `Bearer ${authTokenUsed}`;
  }
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url: buildUrl(path),
      header,
      timeout: APP_CONFIG.requestTimeoutMs,
      success: (response) => resolve({
        statusCode: response.statusCode,
        tempFilePath: response.tempFilePath,
        authTokenUsed
      }),
      fail: (cause) => reject(new ApiError({
        kind: "NETWORK",
        message: cause.errMsg.includes("timeout")
          ? "图片加载超时"
          : "图片加载失败",
        cause
      }))
    });
  });
}

function rawExternalDownload(url: string): Promise<DownloadResult> {
  if (!/^https:\/\/[^/\s]+(?:\/|$)/i.test(url)) {
    return Promise.reject(new ApiError({
      kind: "PROTOCOL",
      message: "图片地址无效"
    }));
  }
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url,
      timeout: APP_CONFIG.requestTimeoutMs,
      success: (response) => resolve({
        statusCode: response.statusCode,
        tempFilePath: response.tempFilePath,
        authTokenUsed: null
      }),
      fail: (cause) => reject(new ApiError({
        kind: "NETWORK",
        message: cause.errMsg.includes("timeout")
          ? "图片加载超时"
          : "图片加载失败",
        cause
      }))
    });
  });
}

function requireSuccessfulDownload(result: DownloadResult): string {
  if (
    result.statusCode < 200 ||
    result.statusCode >= 300 ||
    !result.tempFilePath
  ) {
    throw new ApiError({
      kind: result.statusCode === 401
        ? "AUTH"
        : result.statusCode >= 500
          ? "SERVER"
          : "API",
      message: "图片加载失败，请稍后重试",
      httpStatus: result.statusCode
    });
  }
  return result.tempFilePath;
}

export async function downloadExternalFile(url: string): Promise<string> {
  return requireSuccessfulDownload(await rawExternalDownload(url.trim()));
}

export async function downloadAuthenticatedFile(path: string): Promise<string> {
  await ensureSession();
  let result = await rawDownload(path, getSessionState().accessToken || null);
  if (result.statusCode === 401) {
    await recoverAfterUnauthorized(result.authTokenUsed);
    result = await rawDownload(path, getSessionState().accessToken || null);
    if (result.statusCode === 401) {
      clearSessionIfCurrent(result.authTokenUsed);
    }
  }
  return requireSuccessfulDownload(result);
}
