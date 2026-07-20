import { APP_CONFIG } from "../config/app-config";
import {
  clearSessionIfCurrent,
  ensureSession,
  getSessionState,
  recoverAfterUnauthorized
} from "../services/session";
import type { RequestBody, RequestOptions } from "../types/api";
import { ApiError } from "./api-error";
import {
  rawRequest,
  type RawHttpResult,
  type RawRequestOptions
} from "./http";

function retryAfterSeconds(headers: Record<string, string>): number | undefined {
  const value = Number(headers["retry-after"]);
  return Number.isFinite(value) && value >= 0 ? value : undefined;
}

function errorFromResult<T>(result: RawHttpResult<T>): ApiError {
  const status = result.statusCode;
  let kind: "AUTH" | "RATE_LIMIT" | "SERVER" | "API" | "PROTOCOL";
  if (status === 401) {
    kind = "AUTH";
  } else if (status === 429) {
    kind = "RATE_LIMIT";
  } else if (status >= 500) {
    kind = "SERVER";
  } else if (!result.body) {
    kind = "PROTOCOL";
  } else {
    kind = "API";
  }

  return new ApiError({
    kind,
    message: result.body?.msg || (
      status === 401
        ? "登录状态已失效"
        : status >= 500
          ? "服务暂时不可用，请稍后重试"
          : "请求失败"
    ),
    httpStatus: status,
    code: result.body?.code,
    retryAfterSeconds: retryAfterSeconds(result.headers)
  });
}

function unwrap<T>(result: RawHttpResult<T>, expectData: boolean): T {
  if (
    result.statusCode >= 200 &&
    result.statusCode < 300 &&
    result.body?.code === APP_CONFIG.apiSuccessCode
  ) {
    if (expectData && !("data" in result.body)) {
      throw new ApiError({
        kind: "PROTOCOL",
        message: "响应缺少 data 字段",
        httpStatus: result.statusCode,
        code: result.body.code
      });
    }
    return result.body.data as T;
  }
  throw errorFromResult(result);
}

export async function request<
  TData,
  TBody extends RequestBody = WechatMiniprogram.IAnyObject
>(options: RequestOptions<TBody>): Promise<TData> {
  const requiresAuth = options.auth !== false;
  if (requiresAuth) {
    await ensureSession();
  }

  const rawOptions: Omit<RawRequestOptions<TBody>, "authToken"> = {
    url: options.url,
    method: options.method,
    data: options.data,
    headers: options.headers,
    timeoutMs: options.timeoutMs
  };
  const firstToken = requiresAuth ? getSessionState().accessToken || null : null;
  let result = await rawRequest<TData, TBody>({
    ...rawOptions,
    authToken: firstToken
  });

  if (
    result.statusCode === 401 &&
    requiresAuth &&
    options.recoverAuth !== false
  ) {
    await recoverAfterUnauthorized(result.authTokenUsed);
    const recoveredToken = getSessionState().accessToken || null;
    result = await rawRequest<TData, TBody>({
      ...rawOptions,
      authToken: recoveredToken
    });
    if (result.statusCode === 401) {
      clearSessionIfCurrent(result.authTokenUsed);
    }
  }

  return unwrap(result, options.expectData !== false);
}
