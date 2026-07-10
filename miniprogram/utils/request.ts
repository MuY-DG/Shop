import type { RequestBody, RequestOptions } from "../types/api";
import {
  rawRequest,
  type RawHttpResult,
  type RawRequestOptions
} from "./http";
import {
  sessionManager,
  type SessionManager
} from "../services/session";

const SUCCESS_CODE = 200;

export interface AuthRecoveryOptions {
  auth?: boolean;
  recoverAuth?: boolean;
}

export async function withAuthRecovery<T>(
  send: (authToken: string | null) => Promise<RawHttpResult<T>>,
  options: AuthRecoveryOptions = {},
  session: SessionManager = sessionManager
): Promise<RawHttpResult<T>> {
  const firstToken =
    options.auth === false ? null : session.getState().accessToken || null;
  const first = await send(firstToken);
  if (
    first.statusCode !== 401 ||
    options.auth === false ||
    options.recoverAuth === false
  ) {
    return first;
  }

  await session.recoverAfterUnauthorized(first.authTokenUsed);

  const secondToken = session.getState().accessToken || null;
  const second = await send(secondToken);
  if (second.statusCode === 401) {
    session.clearIfCurrent(second.authTokenUsed);
  }
  return second;
}

function unwrap<T>(result: RawHttpResult<T>): T {
  if (
    result.statusCode >= 200 &&
    result.statusCode < 300 &&
    result.body?.code === SUCCESS_CODE
  ) {
    return result.body.data;
  }
  throw new Error(result.body?.msg || "请求失败");
}

export async function request<
  TData,
  TBody extends RequestBody = WechatMiniprogram.IAnyObject
>(options: RequestOptions<TBody>): Promise<TData> {
  const rawOptions: Omit<RawRequestOptions<TBody>, "authToken"> = {
    url: options.url,
    method: options.method,
    data: options.data
  };
  const result = await withAuthRecovery(
    (authToken) =>
      rawRequest<TData, TBody>({
        ...rawOptions,
        authToken: options.auth === false ? null : authToken
      }),
    options,
    sessionManager
  );
  return unwrap(result);
}
