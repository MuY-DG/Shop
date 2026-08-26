import { APP_CONFIG } from "../config/app-config";
import type {
  ApiResponse,
  HttpMethod,
  RequestBody
} from "../types/api";
import { ApiError } from "./api-error";

export interface RawRequestOptions<
  TBody extends RequestBody = WechatMiniprogram.IAnyObject
> {
  url: string;
  method?: HttpMethod;
  data?: TBody;
  headers?: Record<string, string>;
  authToken?: string | null;
  timeoutMs?: number;
}

export interface RawHttpResult<T> {
  statusCode: number;
  body: ApiResponse<T> | null;
  headers: Record<string, string>;
  authTokenUsed: string | null;
}

function buildUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  const origin = APP_CONFIG.apiBaseUrl.replace(/\/$/, "");
  return `${origin}${path.startsWith("/") ? path : `/${path}`}`;
}

function isApiUrl(url: string): boolean {
  const origin = APP_CONFIG.apiBaseUrl.replace(/\/$/, "");
  return url === origin || url.startsWith(`${origin}/`);
}

function normalizeEnvelope<T>(value: unknown): ApiResponse<T> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const candidate = value as Partial<ApiResponse<T>>;
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
}

function normalizeHeaders(
  value: WechatMiniprogram.IAnyObject | undefined
): Record<string, string> {
  const headers: Record<string, string> = {};
  if (!value) {
    return headers;
  }
  Object.keys(value).forEach((key) => {
    const headerValue = value[key];
    if (typeof headerValue === "string" || typeof headerValue === "number") {
      headers[key.toLowerCase()] = String(headerValue);
    }
  });
  return headers;
}

export function rawRequest<
  TData,
  TBody extends RequestBody = WechatMiniprogram.IAnyObject
>(options: RawRequestOptions<TBody>): Promise<RawHttpResult<TData>> {
  const authTokenUsed = options.authToken?.trim() || null;
  const requestUrl = buildUrl(options.url);
  if (authTokenUsed && !isApiUrl(requestUrl)) {
    return Promise.reject(new ApiError({
      kind: "PROTOCOL",
      message: "禁止向非 API 域名发送登录凭证"
    }));
  }
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...options.headers
  };
  if (authTokenUsed) {
    headers.Authorization = `Bearer ${authTokenUsed}`;
  }

  return new Promise((resolve, reject) => {
    wx.request<ApiResponse<TData>>({
      url: requestUrl,
      method: options.method ?? "GET",
      data: options.data,
      header: headers,
      timeout: options.timeoutMs ?? APP_CONFIG.requestTimeoutMs,
      enableHttp2: true,
      success: (response) => {
        resolve({
          statusCode: response.statusCode,
          body: normalizeEnvelope<TData>(response.data),
          headers: normalizeHeaders(response.header),
          authTokenUsed
        });
      },
      fail: (error) => {
        reject(new ApiError({
          kind: "NETWORK",
          message: error.errMsg.includes("timeout")
            ? "服务响应有点慢，请稍后再试"
            : "暂时没有连接上服务，请检查网络或稍后再试",
          cause: error
        }));
      }
    });
  });
}
