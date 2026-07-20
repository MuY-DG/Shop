import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";

import {
  clearSession,
  clearSessionIfCurrent,
  getSessionState,
  logoutSession,
  recoverAfterUnauthorized,
  refreshSession,
  silentLogin
} from "../miniprogram/services/session";
import type { ApiResponse } from "../miniprogram/types/api";
import type { AppSessionResponse } from "../miniprogram/types/auth";
import { isApiError } from "../miniprogram/utils/api-error";
import { request } from "../miniprogram/utils/request";
import { getHome } from "../miniprogram/services/home";

interface FakeRequestResponse {
  data: unknown;
  statusCode: number;
  header: Record<string, string>;
  cookies: string[];
}

interface FakeRequestCall {
  url: string;
  method?: string;
  data?: unknown;
  header?: Record<string, string>;
  success?: (response: FakeRequestResponse) => void;
  fail?: (error: { errMsg: string }) => void;
}

interface FakeLoginOptions {
  success?: (result: { code: string; errMsg: string }) => void;
  fail?: (error: { errMsg: string }) => void;
}

const storage = new Map<string, unknown>();
const pendingRequests: FakeRequestCall[] = [];
let loginCallCount = 0;

const wxMock = {
  getStorageSync(key: string): unknown {
    return storage.get(key) ?? "";
  },
  setStorageSync(key: string, value: unknown): void {
    storage.set(key, value);
  },
  removeStorageSync(key: string): void {
    storage.delete(key);
  },
  login(options: FakeLoginOptions): void {
    loginCallCount += 1;
    options.success?.({
      code: `wx-code-${loginCallCount}`,
      errMsg: "login:ok"
    });
  },
  request(options: FakeRequestCall): WechatMiniprogram.RequestTask {
    pendingRequests.push(options);
    return {} as WechatMiniprogram.RequestTask;
  }
} as unknown as WechatMiniprogram.Wx;

Object.defineProperty(globalThis, "wx", {
  configurable: true,
  value: wxMock,
  writable: true
});

function sessionResponse(suffix: string): AppSessionResponse {
  return {
    token: `access-${suffix}`,
    refreshToken: `refresh-${suffix}`,
    expiresIn: 3600,
    user: {
      userId: suffix,
      nickname: `用户${suffix}`,
      openidMasked: "openid****",
      phoneAuthorized: false
    }
  };
}

function takeRequest(path: string): FakeRequestCall {
  const index = pendingRequests.findIndex((call) => call.url.endsWith(path));
  assert.notEqual(index, -1, `没有找到请求 ${path}`);
  const [call] = pendingRequests.splice(index, 1);
  assert.ok(call);
  return call;
}

function respond<T>(
  call: FakeRequestCall,
  statusCode: number,
  body: ApiResponse<T>
): void {
  assert.ok(call.success, `请求 ${call.url} 缺少 success 回调`);
  call.success({
    data: body,
    statusCode,
    header: {},
    cookies: []
  });
}

async function flushTasks(): Promise<void> {
  await new Promise<void>((resolve) => setImmediate(resolve));
}

async function establishSession(suffix: string): Promise<void> {
  const login = silentLogin();
  await flushTasks();
  respond(
    takeRequest("/app/auth/login"),
    200,
    { code: 200, msg: "success", data: sessionResponse(suffix) }
  );
  await login;
}

beforeEach(() => {
  clearSession();
  storage.clear();
  pendingRequests.length = 0;
  loginCallCount = 0;
});

test("并发静默登录只交换一次微信 code", async () => {
  const first = silentLogin();
  const second = silentLogin();
  await flushTasks();

  assert.equal(loginCallCount, 1);
  assert.equal(pendingRequests.length, 1);
  respond(
    takeRequest("/app/auth/login"),
    200,
    { code: 200, msg: "success", data: sessionResponse("1") }
  );

  const [firstState, secondState] = await Promise.all([first, second]);
  assert.equal(firstState.accessToken, "access-1");
  assert.equal(secondState.accessToken, "access-1");
});

test("并发刷新只消费一次旋转 refresh token", async () => {
  await establishSession("1");
  const first = refreshSession();
  const second = refreshSession();
  await flushTasks();

  assert.equal(pendingRequests.length, 1);
  const refreshCall = takeRequest("/app/auth/refresh");
  assert.deepEqual(refreshCall.data, { refreshToken: "refresh-1" });
  respond(
    refreshCall,
    200,
    { code: 200, msg: "success", data: sessionResponse("2") }
  );

  const [firstState, secondState] = await Promise.all([first, second]);
  assert.equal(firstState.accessToken, "access-2");
  assert.equal(secondState.accessToken, "access-2");
});

test("主动清理后旧刷新失败不会复活会话", async () => {
  await establishSession("1");
  const refresh = refreshSession();
  await flushTasks();
  const refreshCall = takeRequest("/app/auth/refresh");

  clearSession();
  respond(refreshCall, 401, { code: 100001, msg: "unauthorized" });
  const recovered = await refresh;

  assert.equal(recovered.accessToken, "");
  assert.equal(getSessionState().accessToken, "");
  assert.equal(loginCallCount, 1);
});

test("退出后的旧 401 不会触发重新登录", async () => {
  await establishSession("1");
  clearSession();

  const recovered = await recoverAfterUnauthorized("access-1");
  assert.equal(recovered.accessToken, "");
  assert.equal(loginCallCount, 1);
  assert.equal(pendingRequests.length, 0);
});

test("无 token 的延迟 401 不会取消进行中的新登录", async () => {
  const login = silentLogin();
  await flushTasks();
  const loginCall = takeRequest("/app/auth/login");

  clearSessionIfCurrent(null);
  respond(
    loginCall,
    200,
    { code: 200, msg: "success", data: sessionResponse("2") }
  );
  await login;

  assert.equal(getSessionState().accessToken, "access-2");
});

test("退出立即清理本地态且不覆盖随后建立的新会话", async () => {
  await establishSession("1");
  const logout = logoutSession();
  const logoutCall = takeRequest("/app/auth/logout");
  assert.equal(getSessionState().accessToken, "");

  const newLogin = silentLogin();
  await flushTasks();
  respond(
    takeRequest("/app/auth/login"),
    200,
    { code: 200, msg: "success", data: sessionResponse("2") }
  );
  await newLogin;

  respond(logoutCall, 200, { code: 200, msg: "success" });
  await logout;
  assert.equal(getSessionState().accessToken, "access-2");
});

test("受保护请求的并发 401 共用一次刷新并各自只重试一次", async () => {
  await establishSession("1");
  const first = request<{ ok: boolean }>({ url: "/app/protected" });
  const second = request<{ ok: boolean }>({ url: "/app/protected" });
  await flushTasks();

  const firstCall = takeRequest("/app/protected");
  const secondCall = takeRequest("/app/protected");
  respond(firstCall, 401, { code: 100001, msg: "unauthorized" });
  respond(secondCall, 401, { code: 100001, msg: "unauthorized" });
  await flushTasks();

  const refreshCall = takeRequest("/app/auth/refresh");
  assert.equal(
    pendingRequests.filter((call) => call.url.endsWith("/app/auth/refresh")).length,
    0
  );
  respond(
    refreshCall,
    200,
    { code: 200, msg: "success", data: sessionResponse("2") }
  );
  await flushTasks();

  const firstRetry = takeRequest("/app/protected");
  const secondRetry = takeRequest("/app/protected");
  assert.equal(firstRetry.header?.Authorization, "Bearer access-2");
  assert.equal(secondRetry.header?.Authorization, "Bearer access-2");
  respond(firstRetry, 200, { code: 200, msg: "success", data: { ok: true } });
  respond(secondRetry, 200, { code: 200, msg: "success", data: { ok: true } });

  assert.deepEqual(await Promise.all([first, second]), [{ ok: true }, { ok: true }]);
  assert.equal(pendingRequests.length, 0);
});

test("非 void 成功响应缺少 data 时报告协议错误", async () => {
  const home = getHome();
  respond(takeRequest("/app/home"), 200, { code: 200, msg: "success" });

  await assert.rejects(home, (error: unknown) => {
    assert.ok(isApiError(error));
    assert.equal(error.kind, "PROTOCOL");
    return true;
  });
});

test("首页接口始终使用公开 GET 请求且不携带登录凭证", async () => {
  const publicHome = getHome();
  const publicCall = takeRequest("/app/home");
  assert.equal(publicCall.method, "GET");
  assert.equal(publicCall.header?.Authorization, undefined);
  assert.equal(loginCallCount, 0);
  respond(publicCall, 200, {
    code: 200,
    msg: "success",
    data: {
      schemaVersion: 2,
      banners: [],
      categories: [],
      productSections: []
    }
  });
  await publicHome;

  await establishSession("1");
  const authenticatedHome = getHome();
  const authenticatedCall = takeRequest("/app/home");
  assert.equal(authenticatedCall.header?.Authorization, undefined);
  assert.equal(loginCallCount, 1);
  respond(authenticatedCall, 200, {
    code: 200,
    msg: "success",
    data: {
      schemaVersion: 2,
      banners: [],
      categories: [],
      productSections: []
    }
  });
  await authenticatedHome;
});
