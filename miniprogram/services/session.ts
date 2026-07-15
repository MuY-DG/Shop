import type {
  AppSessionResponse,
  AppUserProfile,
  RequestBody
} from "../types/api";
import { rawRequest } from "../utils/http";

export const AUTH_STATE_KEY = "shop_app_auth_state_v1";
export const LEGACY_ACCESS_KEY = "shop_app_token";
export const LEGACY_REFRESH_KEY = "shop_app_refresh_token";
export const AUTH_STATE_VERSION = 1 as const;
export const EXPIRY_SKEW_MS = 30_000;

const SUCCESS_CODE = 200;

export interface AuthStateV1 {
  version: typeof AUTH_STATE_VERSION;
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  profile: AppUserProfile | null;
}

export interface SessionStorage {
  get(key: string): unknown;
  set(key: string, value: unknown): void;
  remove(key: string): void;
}

export interface SessionManagerDependencies {
  storage: SessionStorage;
  now: () => number;
  login: () => Promise<AppSessionResponse>;
  refresh: (refreshToken: string) => Promise<AppSessionResponse>;
  logout: (accessToken: string) => Promise<void>;
}

export interface SessionManager {
  restore(): AuthStateV1;
  ensureSession(): Promise<AuthStateV1>;
  silentLogin(): Promise<AuthStateV1>;
  refreshSession(): Promise<AuthStateV1>;
  recoverAfterUnauthorized(failedAccessToken: string | null): Promise<void>;
  clearIfCurrent(tokenUsed: string | null): void;
  updateProfile(profile: AppUserProfile): AuthStateV1;
  logout(): Promise<void>;
  clear(): void;
  getState(): AuthStateV1;
}

function emptyState(): AuthStateV1 {
  return {
    version: AUTH_STATE_VERSION,
    accessToken: "",
    refreshToken: "",
    accessExpiresAt: 0,
    profile: null
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
    typeof value.userId !== "number" ||
    !Number.isFinite(value.userId) ||
    typeof value.openidMasked !== "string" ||
    typeof value.phoneAuthorized !== "boolean" ||
    value.phoneNumberMasked !== undefined &&
    value.phoneNumberMasked !== null &&
    typeof value.phoneNumberMasked !== "string"
  ) {
    return undefined;
  }
  const fallbackNickname = `用户${String(Math.trunc(Math.abs(value.userId))).slice(-6)}`;
  const nickname =
    typeof value.nickname === "string" && value.nickname.trim()
      ? value.nickname.trim()
      : fallbackNickname;
  return {
    userId: value.userId,
    nickname,
    openidMasked: value.openidMasked,
    phoneAuthorized: value.phoneAuthorized,
    phoneNumberMasked:
      typeof value.phoneNumberMasked === "string"
        ? value.phoneNumberMasked
        : null
  };
}

function normalizeStoredState(value: unknown): AuthStateV1 | undefined {
  if (!isRecord(value) || value.version !== AUTH_STATE_VERSION) {
    return undefined;
  }
  if (
    typeof value.accessToken !== "string" ||
    typeof value.refreshToken !== "string" ||
    typeof value.accessExpiresAt !== "number" ||
    !Number.isFinite(value.accessExpiresAt) ||
    value.accessExpiresAt < 0
  ) {
    return undefined;
  }
  let storedProfile: AppUserProfile | null;
  if (value.profile === null) {
    storedProfile = null;
  } else {
    const normalized = normalizeProfile(value.profile);
    if (!normalized) {
      return undefined;
    }
    storedProfile = normalized;
  }
  return {
    version: AUTH_STATE_VERSION,
    accessToken: value.accessToken,
    refreshToken: value.refreshToken,
    accessExpiresAt: value.accessExpiresAt,
    profile: storedProfile
  };
}

function normalizeSessionResponse(value: unknown): AppSessionResponse | undefined {
  if (!isRecord(value)) {
    return undefined;
  }
  const normalizedProfile = normalizeProfile(value.user);
  if (
    typeof value.token !== "string" ||
    value.token.length === 0 ||
    typeof value.refreshToken !== "string" ||
    value.refreshToken.length === 0 ||
    typeof value.expiresIn !== "number" ||
    !Number.isFinite(value.expiresIn) ||
    value.expiresIn <= 0 ||
    !normalizedProfile
  ) {
    return undefined;
  }
  return {
    token: value.token,
    refreshToken: value.refreshToken,
    expiresIn: value.expiresIn,
    user: normalizedProfile
  };
}

function copyState(state: AuthStateV1): AuthStateV1 {
  return {
    version: AUTH_STATE_VERSION,
    accessToken: state.accessToken,
    refreshToken: state.refreshToken,
    accessExpiresAt: state.accessExpiresAt,
    profile: state.profile ? { ...state.profile } : null
  };
}

export function createSessionManager(
  dependencies: SessionManagerDependencies
): SessionManager {
  let state = emptyState();
  let authEpoch = 0;
  let restoreCompleted = false;
  let loginFlight: Promise<AuthStateV1> | null = null;
  let refreshFlight: Promise<AuthStateV1> | null = null;
  let renewalFlight: Promise<AuthStateV1> | null = null;
  let recoveryFlight: Promise<void> | null = null;

  function safeGet(key: string): unknown {
    try {
      return dependencies.storage.get(key);
    } catch {
      return undefined;
    }
  }

  function safeSet(key: string, value: unknown): boolean {
    try {
      dependencies.storage.set(key, value);
      return true;
    } catch {
      return false;
    }
  }

  function safeRemove(key: string): boolean {
    try {
      dependencies.storage.remove(key);
      return true;
    } catch {
      return false;
    }
  }

  function removeLegacyKeys(): void {
    safeRemove(LEGACY_ACCESS_KEY);
    safeRemove(LEGACY_REFRESH_KEY);
  }

  function clearStoredTokens(): void {
    const tombstones: Array<readonly [string, unknown]> = [
      [AUTH_STATE_KEY, emptyState()],
      [LEGACY_ACCESS_KEY, null],
      [LEGACY_REFRESH_KEY, null]
    ];
    const tombstoneWrites = tombstones.map(([key, value]) =>
      safeSet(key, value)
    );
    const failedKeys: string[] = [];
    tombstones.forEach(([key], index) => {
      const removed = safeRemove(key);
      if (!tombstoneWrites[index] && !removed) {
        failedKeys.push(key);
      }
    });
    if (failedKeys.length > 0) {
      throw new Error("会话清理失败");
    }
  }

  function persist(next: AuthStateV1): void {
    if (!safeSet(AUTH_STATE_KEY, next)) {
      throw new Error("会话保存失败");
    }
    removeLegacyKeys();
  }

  function commitSession(
    response: AppSessionResponse,
    startedEpoch: number
  ): AuthStateV1 {
    if (startedEpoch !== authEpoch) {
      return copyState(state);
    }
    const normalized = normalizeSessionResponse(response);
    if (!normalized) {
      throw new Error("登录响应格式错误");
    }
    const next: AuthStateV1 = {
      version: AUTH_STATE_VERSION,
      accessToken: normalized.token,
      refreshToken: normalized.refreshToken,
      accessExpiresAt: dependencies.now() + normalized.expiresIn * 1000,
      profile: normalized.user
    };
    persist(next);
    state = next;
    authEpoch += 1;
    return copyState(state);
  }

  function restore(): AuthStateV1 {
    if (restoreCompleted) {
      return copyState(state);
    }
    restoreCompleted = true;
    const restored = normalizeStoredState(safeGet(AUTH_STATE_KEY));
    if (restored) {
      state = restored;
      authEpoch += 1;
      removeLegacyKeys();
      return copyState(state);
    }

    const legacyAccess = safeGet(LEGACY_ACCESS_KEY);
    const legacyRefresh = safeGet(LEGACY_REFRESH_KEY);
    const accessToken = typeof legacyAccess === "string" ? legacyAccess : "";
    const refreshToken = typeof legacyRefresh === "string" ? legacyRefresh : "";
    if (typeof legacyAccess === "string" || typeof legacyRefresh === "string") {
      const migrated: AuthStateV1 = {
        version: AUTH_STATE_VERSION,
        accessToken,
        refreshToken,
        accessExpiresAt: 0,
        profile: null
      };
      state = migrated;
      authEpoch += 1;
      if (safeSet(AUTH_STATE_KEY, migrated)) {
        removeLegacyKeys();
      }
      return copyState(state);
    }

    state = emptyState();
    authEpoch += 1;
    safeRemove(AUTH_STATE_KEY);
    return copyState(state);
  }

  function silentLogin(): Promise<AuthStateV1> {
    if (loginFlight) {
      return loginFlight;
    }
    const startedEpoch = authEpoch;
    const operation = dependencies
      .login()
      .then((response) => commitSession(response, startedEpoch));
    let flight: Promise<AuthStateV1>;
    flight = operation.finally(() => {
      if (loginFlight === flight) {
        loginFlight = null;
      }
    });
    loginFlight = flight;
    return flight;
  }

  function refreshSession(): Promise<AuthStateV1> {
    if (refreshFlight) {
      return refreshFlight;
    }
    const refreshToken = state.refreshToken;
    if (!refreshToken) {
      return Promise.reject(new Error("刷新凭证不存在"));
    }
    const startedEpoch = authEpoch;
    const operation = dependencies
      .refresh(refreshToken)
      .then((response) => commitSession(response, startedEpoch));
    let flight: Promise<AuthStateV1>;
    flight = operation.finally(() => {
      if (refreshFlight === flight) {
        refreshFlight = null;
      }
    });
    refreshFlight = flight;
    return flight;
  }

  function resetState(detachFlights: boolean): void {
    authEpoch += 1;
    if (detachFlights) {
      loginFlight = null;
      refreshFlight = null;
      renewalFlight = null;
      recoveryFlight = null;
    }
    state = emptyState();
    clearStoredTokens();
  }

  function renewSession(): Promise<AuthStateV1> {
    if (loginFlight) {
      return loginFlight;
    }
    if (renewalFlight) {
      return renewalFlight;
    }
    const startedEpoch = authEpoch;
    const operation = (async () => {
      if (state.refreshToken) {
        try {
          return await refreshSession();
        } catch {
          if (startedEpoch !== authEpoch) {
            return copyState(state);
          }
        }
      }
      resetState(false);
      return silentLogin();
    })();
    let flight: Promise<AuthStateV1>;
    flight = operation.finally(() => {
      if (renewalFlight === flight) {
        renewalFlight = null;
      }
    });
    renewalFlight = flight;
    return flight;
  }

  function ensureSession(): Promise<AuthStateV1> {
    if (loginFlight) {
      return loginFlight;
    }
    if (renewalFlight) {
      return renewalFlight;
    }
    if (
      state.accessToken &&
      state.accessExpiresAt > dependencies.now() + EXPIRY_SKEW_MS
    ) {
      return Promise.resolve(copyState(state));
    }
    return renewSession();
  }

  function recoverAfterUnauthorized(
    failedAccessToken: string | null
  ): Promise<void> {
    if ((state.accessToken || null) !== failedAccessToken) {
      return Promise.resolve();
    }
    if (recoveryFlight) {
      return recoveryFlight;
    }
    const operation = renewSession().then(() => undefined);
    let flight: Promise<void>;
    flight = operation.finally(() => {
      if (recoveryFlight === flight) {
        recoveryFlight = null;
      }
    });
    recoveryFlight = flight;
    return flight;
  }

  function clear(): void {
    resetState(true);
  }

  function clearIfCurrent(tokenUsed: string | null): void {
    if ((state.accessToken || null) === tokenUsed) {
      clear();
    }
  }

  function updateProfile(nextProfile: AppUserProfile): AuthStateV1 {
    const normalized = normalizeProfile(nextProfile);
    if (!normalized) {
      throw new Error("用户资料格式错误");
    }
    if (!state.accessToken) {
      return copyState(state);
    }
    const next = { ...state, profile: normalized };
    persist(next);
    state = next;
    return copyState(state);
  }

  async function logout(): Promise<void> {
    const accessToken = state.accessToken;
    try {
      if (accessToken) {
        await dependencies.logout(accessToken);
      }
    } finally {
      clear();
    }
  }

  return {
    restore,
    ensureSession,
    silentLogin,
    refreshSession,
    recoverAfterUnauthorized,
    clearIfCurrent,
    updateProfile,
    logout,
    clear,
    getState: () => copyState(state)
  };
}

function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (result) => {
        if (result.code) {
          resolve(result.code);
        } else {
          reject(new Error("微信登录失败"));
        }
      },
      fail: (error) => reject(new Error(error.errMsg))
    });
  });
}

async function readSessionResponse(
  url: string,
  data: RequestBody
): Promise<AppSessionResponse> {
  const result = await rawRequest<AppSessionResponse, RequestBody>({
    url,
    method: "POST",
    data,
    authToken: null
  });
  const normalized = normalizeSessionResponse(result.body?.data);
  if (
    result.statusCode < 200 ||
    result.statusCode >= 300 ||
    result.body?.code !== SUCCESS_CODE ||
    !normalized
  ) {
    throw new Error(result.body?.msg || "认证请求失败");
  }
  return normalized;
}

const wxStorage: SessionStorage = {
  get: (key) => wx.getStorageSync(key),
  set: (key, value) => wx.setStorageSync(key, value),
  remove: (key) => wx.removeStorageSync(key)
};

const defaultSessionManager = createSessionManager({
  storage: wxStorage,
  now: () => Date.now(),
  login: async () => {
    const code = await wxLoginCode();
    return readSessionResponse("/app/auth/login", { code });
  },
  refresh: (refreshToken) =>
    readSessionResponse("/app/auth/refresh", { refreshToken }),
  logout: async (accessToken) => {
    const result = await rawRequest<null>({
      url: "/app/auth/logout",
      method: "POST",
      authToken: accessToken
    });
    if (
      result.statusCode < 200 ||
      result.statusCode >= 300 ||
      result.body?.code !== SUCCESS_CODE
    ) {
      throw new Error(result.body?.msg || "退出登录失败");
    }
  }
});

export const restoreSession = (): AuthStateV1 => defaultSessionManager.restore();
export const ensureSession = (): Promise<AuthStateV1> =>
  defaultSessionManager.ensureSession();
export const silentLogin = (): Promise<AuthStateV1> =>
  defaultSessionManager.silentLogin();
export const refreshSession = (): Promise<AuthStateV1> =>
  defaultSessionManager.refreshSession();
export const recoverAfterUnauthorized = (
  failedAccessToken: string | null
): Promise<void> => defaultSessionManager.recoverAfterUnauthorized(failedAccessToken);
export const clearIfCurrent = (tokenUsed: string | null): void =>
  defaultSessionManager.clearIfCurrent(tokenUsed);
export const updateProfile = (profile: AppUserProfile): AuthStateV1 =>
  defaultSessionManager.updateProfile(profile);
export const logoutSession = (): Promise<void> => defaultSessionManager.logout();
export const clearSession = (): void => defaultSessionManager.clear();
export const getSessionState = (): AuthStateV1 => defaultSessionManager.getState();
export const sessionManager = defaultSessionManager;
