import { APP_CONFIG } from "../config/app-config";
import {
  clearSessionIfCurrent,
  ensureSession,
  getSessionState,
  recoverAfterUnauthorized
} from "../services/session";
import type { ApiResponse } from "../types/api";
import { ApiError } from "./api-error";

interface RawUploadResult<T> {
  statusCode: number;
  body: ApiResponse<T> | null;
  authTokenUsed: string | null;
}

export interface UploadFileOptions {
  url: string;
  filePath: string;
  name?: string;
  timeoutMs?: number;
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

function parseEnvelope<T>(value: string): ApiResponse<T> | null {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return null;
    }
    const candidate = parsed as Partial<ApiResponse<T>>;
    if (typeof candidate.code !== "number" || typeof candidate.msg !== "string") {
      return null;
    }
    const envelope: ApiResponse<T> = {
      code: candidate.code,
      msg: candidate.msg
    };
    if ("data" in candidate && candidate.data !== undefined) {
      envelope.data = candidate.data;
    }
    return envelope;
  } catch {
    return null;
  }
}

function rawUpload<T>(
  options: UploadFileOptions,
  authToken: string | null
): Promise<RawUploadResult<T>> {
  const authTokenUsed = authToken?.trim() || null;
  const header: Record<string, string> = {};
  if (authTokenUsed) {
    header.Authorization = `Bearer ${authTokenUsed}`;
  }

  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: buildUrl(options.url),
      filePath: options.filePath,
      name: options.name || "file",
      header,
      timeout: options.timeoutMs ?? APP_CONFIG.requestTimeoutMs,
      enableHttp2: true,
      success: (response) => resolve({
        statusCode: response.statusCode,
        body: parseEnvelope<T>(response.data),
        authTokenUsed
      }),
      fail: (cause) => reject(new ApiError({
        kind: "NETWORK",
        message: cause.errMsg.includes("timeout")
          ? "图片上传超时，请稍后重试"
          : "图片上传失败，请检查网络",
        cause
      }))
    });
  });
}

function uploadError<T>(result: RawUploadResult<T>): ApiError {
  const status = result.statusCode;
  return new ApiError({
    kind: status === 401
      ? "AUTH"
      : status === 429
        ? "RATE_LIMIT"
        : status >= 500
          ? "SERVER"
          : "API",
    message: result.body?.msg || (
      status === 401 ? "登录状态已失效" : "图片上传失败，请稍后重试"
    ),
    httpStatus: status,
    code: result.body?.code
  });
}

export async function uploadFile<T>(options: UploadFileOptions): Promise<T> {
  if (!options.filePath.trim()) {
    throw new ApiError({ kind: "PROTOCOL", message: "待上传图片不存在" });
  }
  await ensureSession();
  let result = await rawUpload<T>(options, getSessionState().accessToken || null);
  if (result.statusCode === 401) {
    await recoverAfterUnauthorized(result.authTokenUsed);
    result = await rawUpload<T>(options, getSessionState().accessToken || null);
    if (result.statusCode === 401) {
      clearSessionIfCurrent(result.authTokenUsed);
    }
  }
  if (
    result.statusCode < 200 ||
    result.statusCode >= 300 ||
    result.body?.code !== APP_CONFIG.apiSuccessCode
  ) {
    throw uploadError(result);
  }
  if (!("data" in result.body) || result.body.data === undefined) {
    throw new ApiError({
      kind: "PROTOCOL",
      message: "图片上传响应格式不正确",
      httpStatus: result.statusCode,
      code: result.body.code
    });
  }
  return result.body.data;
}
