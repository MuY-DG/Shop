import { APP_CONFIG } from "../config/app-config";
import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AppLoginRequest,
  AppSessionResponse,
  AppUserProfile,
  PhoneAuthorizeRequest,
  RefreshTokenRequest
} from "../types/auth";
import type { RawHttpResult } from "../utils/http";
import { ApiError, isApiError } from "../utils/api-error";
import { rawRequest } from "../utils/http";

const SESSION_VERSION = 1 as const;
const SESSION_STORAGE_KEY = `${APP_CONFIG.storageNamespace}:session`;
const EXPIRY_SKEW_MS = 30_000;

export interface SessionState {
  version: typeof SESSION_VERSION;
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  user?: AppUserProfile;
}

export interface PreparedWechatLogin {
  readonly user: AppUserProfile;
}

interface PreparedWechatLoginState {
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  user: AppUserProfile;
  startedEpoch: number;
}

let state = emptySession();
let restored = false;
let authEpoch = 0;
let renewalFlight: Promise<SessionState> | null = null;
const sessionExpiredListeners = new Set<() => void>();
const preparedWechatLogins = new WeakMap<
  PreparedWechatLogin,
  PreparedWechatLoginState
>();

function emptySession(): SessionState {
  return {
    version: SESSION_VERSION,
    accessToken: "",
    refreshToken: "",
    accessExpiresAt: 0
  };
}

function copySession(value: SessionState): SessionState {
  return {
    ...value,
    user: value.user ? { ...value.user } : undefined
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function normalizeProfile(value: unknown): AppUserProfile | undefined {
  if (!isRecord(value)) {
    return undefined;
  }
  if (
    typeof value.userId !== "string" ||
    typeof value.nickname !== "string" ||
    (value.avatarUrl !== undefined && typeof value.avatarUrl !== "string") ||
    typeof value.openidMasked !== "string" ||
    typeof value.phoneAuthorized !== "boolean" ||
    (value.phoneNumberMasked !== undefined &&
      typeof value.phoneNumberMasked !== "string")
  ) {
    return undefined;
  }
  return {
    userId: value.userId,
    nickname: value.nickname,
    avatarUrl: value.avatarUrl,
    openidMasked: value.openidMasked,
    phoneAuthorized: value.phoneAuthorized,
    phoneNumberMasked: value.phoneNumberMasked
  };
}

function normalizeSessionResponse(value: unknown): AppSessionResponse | undefined {
  if (!isRecord(value)) {
    return undefined;
  }
  const user = normalizeProfile(value.user);
  if (
    typeof value.token !== "string" ||
    !value.token ||
    typeof value.refreshToken !== "string" ||
    !value.refreshToken ||
    typeof value.expiresIn !== "number" ||
    !Number.isFinite(value.expiresIn) ||
    value.expiresIn <= 0 ||
    !user
  ) {
    return undefined;
  }
  return {
    token: value.token,
    refreshToken: value.refreshToken,
    expiresIn: value.expiresIn,
    user
  };
}

function normalizeStoredSession(value: unknown): SessionState | undefined {
  if (!isRecord(value) || value.version !== SESSION_VERSION) {
    return undefined;
  }
  const user = value.user === undefined ? undefined : normalizeProfile(value.user);
  if (
    typeof value.accessToken !== "string" ||
    typeof value.refreshToken !== "string" ||
    typeof value.accessExpiresAt !== "number" ||
    !Number.isFinite(value.accessExpiresAt) ||
    (value.user !== undefined && !user)
  ) {
    return undefined;
  }
  return {
    version: SESSION_VERSION,
    accessToken: value.accessToken,
    refreshToken: value.refreshToken,
    accessExpiresAt: value.accessExpiresAt,
    user
  };
}

function persistSession(next: SessionState): void {
  try {
    wx.setStorageSync(SESSION_STORAGE_KEY, next);
  } catch (cause) {
    throw new ApiError({
      kind: "STORAGE",
      message: "登录状态保存失败",
      cause
    });
  }
}

function commitSession(
  response: AppSessionResponse,
  startedEpoch: number
): SessionState {
  if (startedEpoch !== authEpoch) {
    return copySession(state);
  }
  const next: SessionState = {
    version: SESSION_VERSION,
    accessToken: response.token,
    refreshToken: response.refreshToken,
    accessExpiresAt: Date.now() + response.expiresIn * 1000,
    user: response.user
  };
  persistSession(next);
  state = next;
  authEpoch += 1;
  return copySession(state);
}

function removeStoredSession(): void {
  try {
    wx.removeStorageSync(SESSION_STORAGE_KEY);
  } catch {
    // 内存态仍需立即失效；下次恢复会再次校验持久化结构。
  }
}

function clearSessionInternal(detachRenewal = true): void {
  if (detachRenewal) {
    renewalFlight = null;
  }
  state = emptySession();
  authEpoch += 1;
  removeStoredSession();
}

function expireSessionInternal(detachRenewal = true): void {
  const hadSession = Boolean(
    state.accessToken || state.refreshToken || state.user
  );
  clearSessionInternal(detachRenewal);
  if (!hadSession) {
    return;
  }
  sessionExpiredListeners.forEach((listener) => {
    try {
      listener();
    } catch {
      // 会话清理不能被页面跳转或提示失败阻塞。
    }
  });
}

function errorFromResult<T>(result: RawHttpResult<T>): ApiError {
  const status = result.statusCode;
  return new ApiError({
    kind: status === 401 ? "AUTH" : status >= 500 ? "SERVER" : "API",
    message: result.body?.msg || (status === 401 ? "登录状态已失效" : "请求失败"),
    httpStatus: status,
    code: result.body?.code
  });
}

function loginRequiredError(message = "请先登录"): ApiError {
  return new ApiError({
    kind: "AUTH",
    message,
    httpStatus: 401
  });
}

async function unwrapSessionResult(
  result: RawHttpResult<AppSessionResponse>
): Promise<AppSessionResponse> {
  if (
    result.statusCode < 200 ||
    result.statusCode >= 300 ||
    result.body?.code !== APP_CONFIG.apiSuccessCode
  ) {
    throw errorFromResult(result);
  }
  const normalized = normalizeSessionResponse(result.body.data);
  if (!normalized) {
    throw new ApiError({
      kind: "PROTOCOL",
      message: "登录响应格式不正确",
      httpStatus: result.statusCode,
      code: result.body.code
    });
  }
  return normalized;
}

function unwrapProfileResult(
  result: RawHttpResult<AppUserProfile>
): AppUserProfile {
  if (
    result.statusCode < 200 ||
    result.statusCode >= 300 ||
    result.body?.code !== APP_CONFIG.apiSuccessCode
  ) {
    throw errorFromResult(result);
  }
  const profile = normalizeProfile(result.body.data);
  if (!profile) {
    throw new ApiError({
      kind: "PROTOCOL",
      message: "用户信息响应格式不正确",
      httpStatus: result.statusCode,
      code: result.body.code
    });
  }
  return profile;
}

function getLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (result) => {
        if (result.code) {
          resolve(result.code);
          return;
        }
        reject(new ApiError({
          kind: "AUTH",
          message: "微信登录凭证获取失败"
        }));
      },
      fail: (cause) => reject(new ApiError({
        kind: "AUTH",
        message: "微信登录失败，请稍后重试",
        cause
      }))
    });
  });
}

async function exchangeLogin(): Promise<AppSessionResponse> {
  const code = await getLoginCode();
  const result = await rawRequest<AppSessionResponse, AppLoginRequest>({
    url: API_ENDPOINTS.auth.login,
    method: "POST",
    data: { code }
  });
  return unwrapSessionResult(result);
}

async function exchangeRefresh(refreshToken: string): Promise<AppSessionResponse> {
  const result = await rawRequest<AppSessionResponse, RefreshTokenRequest>({
    url: API_ENDPOINTS.auth.refresh,
    method: "POST",
    data: { refreshToken }
  });
  return unwrapSessionResult(result);
}

function preparedWechatLoginState(
  prepared: PreparedWechatLogin
): PreparedWechatLoginState {
  const pending = preparedWechatLogins.get(prepared);
  if (!pending) {
    throw loginRequiredError("登录准备状态已失效，请重试");
  }
  return pending;
}

async function revokeAccessToken(accessToken: string): Promise<void> {
  const result = await rawRequest<void>({
    url: API_ENDPOINTS.auth.logout,
    method: "POST",
    authToken: accessToken
  });
  if (
    result.statusCode < 200 ||
    result.statusCode >= 300 ||
    result.body?.code !== APP_CONFIG.apiSuccessCode
  ) {
    throw errorFromResult(result);
  }
}

async function performRenewal(preferRefresh: boolean): Promise<SessionState> {
  let startedEpoch = authEpoch;
  if (preferRefresh) {
    if (!state.refreshToken) {
      throw loginRequiredError();
    }
    try {
      const refreshed = await exchangeRefresh(state.refreshToken);
      return commitSession(refreshed, startedEpoch);
    } catch (error) {
      if (startedEpoch !== authEpoch) {
        return copySession(state);
      }
      if (!isApiError(error) || error.kind !== "AUTH") {
        throw error;
      }
      expireSessionInternal(false);
      throw loginRequiredError("登录状态已失效，请重新登录");
    }
  }
  const loggedIn = await exchangeLogin();
  return commitSession(loggedIn, startedEpoch);
}

function renewSession(preferRefresh: boolean): Promise<SessionState> {
  if (renewalFlight) {
    return renewalFlight;
  }
  let currentFlight: Promise<SessionState>;
  currentFlight = performRenewal(preferRefresh).finally(() => {
    if (renewalFlight === currentFlight) {
      renewalFlight = null;
    }
  });
  renewalFlight = currentFlight;
  return currentFlight;
}

export function restoreSession(): SessionState {
  if (restored) {
    return copySession(state);
  }
  restored = true;
  try {
    const stored = normalizeStoredSession(wx.getStorageSync(SESSION_STORAGE_KEY));
    if (stored) {
      state = stored;
      authEpoch += 1;
      return copySession(state);
    }
  } catch {
    // 存储不可用时降级为空会话，受保护请求仍可重新登录。
  }
  clearSessionInternal();
  return copySession(state);
}

export function getSessionState(): SessionState {
  return restored ? copySession(state) : restoreSession();
}

export function clearSession(): void {
  clearSessionInternal();
}

export function clearSessionIfCurrent(accessToken: string | null): void {
  if (
    accessToken &&
    !renewalFlight &&
    state.accessToken === accessToken
  ) {
    expireSessionInternal();
  }
}

export function onSessionExpired(listener: () => void): () => void {
  sessionExpiredListeners.add(listener);
  return () => {
    sessionExpiredListeners.delete(listener);
  };
}

export async function ensureSession(): Promise<SessionState> {
  const current = getSessionState();
  if (
    current.accessToken &&
    current.accessExpiresAt > Date.now() + EXPIRY_SKEW_MS
  ) {
    return current;
  }
  if (current.refreshToken) {
    return renewSession(true);
  }
  if (current.accessToken || current.user) {
    expireSessionInternal();
    throw loginRequiredError("登录状态已失效，请重新登录");
  }
  throw loginRequiredError();
}

export function loginWithWechat(): Promise<SessionState> {
  restoreSession();
  return renewSession(false);
}

export async function prepareWechatLogin(): Promise<PreparedWechatLogin> {
  restoreSession();
  const startedEpoch = authEpoch;
  const response = await exchangeLogin();
  const prepared: PreparedWechatLogin = {
    user: { ...response.user }
  };
  preparedWechatLogins.set(prepared, {
    accessToken: response.token,
    refreshToken: response.refreshToken,
    accessExpiresAt: Date.now() + response.expiresIn * 1000,
    user: { ...response.user },
    startedEpoch
  });
  return prepared;
}

export function commitPreparedWechatLogin(
  prepared: PreparedWechatLogin
): SessionState {
  const pending = preparedWechatLoginState(prepared);
  if (pending.startedEpoch !== authEpoch) {
    throw loginRequiredError("登录状态已变化，请重试");
  }
  if (pending.accessExpiresAt <= Date.now() + EXPIRY_SKEW_MS) {
    throw loginRequiredError("登录准备状态已失效，请重试");
  }
  const next: SessionState = {
    version: SESSION_VERSION,
    accessToken: pending.accessToken,
    refreshToken: pending.refreshToken,
    accessExpiresAt: pending.accessExpiresAt,
    user: { ...pending.user }
  };
  persistSession(next);
  state = next;
  authEpoch += 1;
  preparedWechatLogins.delete(prepared);
  return copySession(state);
}

export async function authorizePreparedWechatPhoneNumber(
  prepared: PreparedWechatLogin,
  code: string
): Promise<AppUserProfile> {
  const normalizedCode = code.trim();
  if (!normalizedCode) {
    throw new ApiError({
      kind: "AUTH",
      message: "未获得手机号授权，请重试"
    });
  }

  const pending = preparedWechatLoginState(prepared);
  if (pending.startedEpoch !== authEpoch) {
    throw loginRequiredError("登录状态已变化，请重试");
  }
  if (pending.user.phoneAuthorized) {
    return { ...pending.user };
  }
  const result = await rawRequest<AppUserProfile, PhoneAuthorizeRequest>({
    url: API_ENDPOINTS.auth.phone,
    method: "POST",
    data: { code: normalizedCode },
    authToken: pending.accessToken
  });
  const user = unwrapProfileResult(result);
  if (
    pending.startedEpoch !== authEpoch ||
    preparedWechatLogins.get(prepared) !== pending
  ) {
    throw loginRequiredError("登录状态已变化，请重试");
  }
  pending.user = user;
  return { ...user };
}

export async function discardPreparedWechatLogin(
  prepared: PreparedWechatLogin
): Promise<void> {
  const pending = preparedWechatLogins.get(prepared);
  if (!pending) {
    return;
  }
  preparedWechatLogins.delete(prepared);
  await revokeAccessToken(pending.accessToken);
}

export function refreshSession(): Promise<SessionState> {
  restoreSession();
  if (!state.refreshToken) {
    return Promise.reject(loginRequiredError());
  }
  return renewSession(true);
}

export async function recoverAfterUnauthorized(
  failedAccessToken: string | null
): Promise<SessionState> {
  const current = getSessionState();
  if ((current.accessToken || null) !== failedAccessToken) {
    return current;
  }
  if (!current.refreshToken) {
    expireSessionInternal();
    throw loginRequiredError("登录状态已失效，请重新登录");
  }
  return renewSession(true);
}

export async function authorizePhoneNumber(code: string): Promise<AppUserProfile> {
  const normalizedCode = code.trim();
  if (!normalizedCode) {
    throw new ApiError({
      kind: "AUTH",
      message: "未获得手机号授权，请重试"
    });
  }

  const current = await ensureSession();
  if (current.user?.phoneAuthorized) {
    return { ...current.user };
  }
  const result = await rawRequest<AppUserProfile, PhoneAuthorizeRequest>({
    url: API_ENDPOINTS.auth.phone,
    method: "POST",
    data: { code: normalizedCode },
    authToken: current.accessToken
  });
  if (result.statusCode === 401) {
    clearSessionIfCurrent(result.authTokenUsed);
  }
  const user = unwrapProfileResult(result);
  if (state.accessToken !== current.accessToken) {
    throw loginRequiredError("登录状态已变化，请重新登录");
  }
  return updateSessionUser(user);
}

export function updateSessionUser(value: AppUserProfile): AppUserProfile {
  const user = normalizeProfile(value);
  const current = getSessionState();
  if (!user) {
    throw new ApiError({
      kind: "PROTOCOL",
      message: "用户信息响应格式不正确"
    });
  }
  if (!current.accessToken && !current.refreshToken) {
    throw loginRequiredError();
  }
  const next: SessionState = {
    ...state,
    user
  };
  persistSession(next);
  state = next;
  authEpoch += 1;
  return { ...user };
}

export async function logoutSession(): Promise<void> {
  const current = getSessionState();
  clearSessionInternal();
  if (!current.accessToken) {
    return;
  }
  await revokeAccessToken(current.accessToken);
}
