import assert from "node:assert/strict";
import test from "node:test";

import type { AppSessionResponse, AppUserProfile } from "../types/api";
import type {
  createProfilePageDefinition,
  ProfilePageContext
} from "../pages/profile/profile";
import {
  AUTH_STATE_KEY,
  AUTH_STATE_VERSION,
  LEGACY_ACCESS_KEY,
  LEGACY_REFRESH_KEY,
  createSessionManager,
  type SessionManagerDependencies,
  type SessionStorage
} from "../services/session";

const NOW = 1_700_000_000_000;

function profile(overrides: Partial<AppUserProfile> = {}): AppUserProfile {
  return {
    userId: "2075761422822531074",
    nickname: "山茶花用户",
    openidMasked: "o****d",
    phoneAuthorized: true,
    phoneNumberMasked: "138****5678",
    ...overrides
  };
}

function sessionResponse(
  accessToken = "app_new",
  refreshToken = "apr_new",
  user = profile()
): AppSessionResponse {
  return {
    token: accessToken,
    refreshToken,
    expiresIn: 3600,
    user
  };
}

interface FakeStorage extends SessionStorage {
  peek(key: string): unknown;
  has(key: string): boolean;
  operations: string[];
  failVersionedWrite: boolean;
  failSetKeys: Set<string>;
  failRemoveKeys: Set<string>;
}

function clone<T>(value: T): T {
  return value === undefined ? value : structuredClone(value);
}

function fakeStorage(initial: Record<string, unknown> = {}): FakeStorage {
  const values = new Map<string, unknown>(
    Object.entries(initial).map(([key, value]) => [key, clone(value)])
  );

  return {
    operations: [],
    failVersionedWrite: false,
    failSetKeys: new Set<string>(),
    failRemoveKeys: new Set<string>(),
    get(key) {
      this.operations.push(`get:${key}`);
      return clone(values.get(key));
    },
    set(key, value) {
      this.operations.push(`set:${key}`);
      if (
        key === AUTH_STATE_KEY && this.failVersionedWrite ||
        this.failSetKeys.has(key)
      ) {
        throw new Error("storage full");
      }
      values.set(key, clone(value));
    },
    remove(key) {
      this.operations.push(`remove:${key}`);
      if (this.failRemoveKeys.has(key)) {
        throw new Error("remove denied");
      }
      values.delete(key);
    },
    peek(key) {
      return clone(values.get(key));
    },
    has(key) {
      return values.has(key);
    }
  };
}

function fakeDependencies(
  overrides: Partial<SessionManagerDependencies> = {}
): SessionManagerDependencies {
  return {
    storage: fakeStorage(),
    now: () => NOW,
    login: async () => sessionResponse(),
    refresh: async () => sessionResponse("app_refreshed", "apr_refreshed"),
    logout: async () => undefined,
    ...overrides
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

test("migrates legacy string tokens before removing their keys", () => {
  const storage = fakeStorage({
    [LEGACY_ACCESS_KEY]: "app_old",
    [LEGACY_REFRESH_KEY]: "apr_old"
  });
  const manager = createSessionManager(fakeDependencies({ storage }));

  const state = manager.restore();

  assert.equal(state.version, AUTH_STATE_VERSION);
  assert.equal(state.accessToken, "app_old");
  assert.equal(state.refreshToken, "apr_old");
  assert.equal(state.accessExpiresAt, 0);
  assert.equal(state.profile, null);
  assert.deepEqual(storage.peek(AUTH_STATE_KEY), state);
  assert.equal(storage.has(LEGACY_ACCESS_KEY), false);
  assert.equal(storage.has(LEGACY_REFRESH_KEY), false);
  assert.ok(
    storage.operations.indexOf(`set:${AUTH_STATE_KEY}`) <
      storage.operations.indexOf(`remove:${LEGACY_ACCESS_KEY}`)
  );
});

test("ignores malformed versioned and legacy storage without throwing", () => {
  const malformedValues: unknown[] = [
    null,
    "broken",
    42,
    [],
    { version: 1, accessToken: 42 },
    {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: "later",
      profile: null
    }
  ];

  for (const malformed of malformedValues) {
    const storage = fakeStorage({
      [AUTH_STATE_KEY]: malformed,
      [LEGACY_ACCESS_KEY]: { token: "app_leak" },
      [LEGACY_REFRESH_KEY]: 123
    });
    const manager = createSessionManager(fakeDependencies({ storage }));

    assert.doesNotThrow(() => manager.restore());
    assert.equal(manager.getState().accessToken, "");
    assert.equal(manager.getState().refreshToken, "");
    assert.equal(manager.getState().profile, null);
  }
});

test("keeps legacy keys when the versioned migration write fails", () => {
  const storage = fakeStorage({
    [LEGACY_ACCESS_KEY]: "app_old",
    [LEGACY_REFRESH_KEY]: "apr_old"
  });
  storage.failVersionedWrite = true;
  const manager = createSessionManager(fakeDependencies({ storage }));

  assert.doesNotThrow(() => manager.restore());

  assert.equal(storage.has(LEGACY_ACCESS_KEY), true);
  assert.equal(storage.has(LEGACY_REFRESH_KEY), true);
  assert.equal(storage.has(AUTH_STATE_KEY), false);
});

test("coalesces concurrent silent login and ensureSession calls", async () => {
  let loginCalls = 0;
  const loginResult = deferred<AppSessionResponse>();
  const manager = createSessionManager(
    fakeDependencies({
      login: async () => {
        loginCalls += 1;
        return loginResult.promise;
      }
    })
  );

  const calls = [manager.silentLogin(), manager.silentLogin(), manager.ensureSession()];
  assert.equal(loginCalls, 1);
  loginResult.resolve(sessionResponse());
  await Promise.all(calls);

  assert.equal(loginCalls, 1);
  assert.equal(manager.getState().accessToken, "app_new");
});

test("recovery without a token joins an in-flight silent login", async () => {
  let loginCalls = 0;
  const loginResult = deferred<AppSessionResponse>();
  const manager = createSessionManager(
    fakeDependencies({
      login: async () => {
        loginCalls += 1;
        return loginResult.promise;
      }
    })
  );

  const login = manager.silentLogin();
  const recovery = manager.recoverAfterUnauthorized(null);
  loginResult.resolve(sessionResponse("app_login", "apr_login"));
  await Promise.all([login, recovery]);

  assert.equal(loginCalls, 1);
  assert.equal(manager.getState().accessToken, "app_login");
});

test("refreshes migrated tokens instead of trusting their unknown expiry", async () => {
  let refreshCalls = 0;
  let loginCalls = 0;
  const storage = fakeStorage({
    [LEGACY_ACCESS_KEY]: "app_old",
    [LEGACY_REFRESH_KEY]: "apr_old"
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      refresh: async (refreshToken) => {
        refreshCalls += 1;
        assert.equal(refreshToken, "apr_old");
        return sessionResponse("app_rotated", "apr_rotated");
      },
      login: async () => {
        loginCalls += 1;
        return sessionResponse();
      }
    })
  );
  manager.restore();

  await manager.ensureSession();

  assert.equal(refreshCalls, 1);
  assert.equal(loginCalls, 0);
  assert.equal(manager.getState().accessToken, "app_rotated");
});

test("coalesces concurrent ensureSession refresh failures into one fallback login", async () => {
  let refreshCalls = 0;
  let loginCalls = 0;
  const refreshResult = deferred<AppSessionResponse>();
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: 0,
      profile: profile()
    }
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      refresh: async () => {
        refreshCalls += 1;
        return refreshResult.promise;
      },
      login: async () => {
        loginCalls += 1;
        return sessionResponse("app_login", "apr_login");
      }
    })
  );
  manager.restore();

  const calls = [manager.ensureSession(), manager.ensureSession(), manager.ensureSession()];
  assert.equal(refreshCalls, 1);
  refreshResult.reject(new Error("refresh expired"));
  const states = await Promise.all(calls);

  assert.equal(loginCalls, 1);
  assert.deepEqual(
    states.map((state) => state.accessToken),
    ["app_login", "app_login", "app_login"]
  );
});

test("repeated restore does not invalidate an in-flight refresh", async () => {
  const refreshResult = deferred<AppSessionResponse>();
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: 0,
      profile: profile()
    }
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      refresh: async () => refreshResult.promise
    })
  );
  manager.restore();

  const pending = manager.refreshSession();
  manager.restore();
  refreshResult.resolve(sessionResponse("app_rotated", "apr_rotated"));
  await pending;

  assert.equal(manager.getState().accessToken, "app_rotated");
});

test("persists only the masked profile and restores it after restart", async () => {
  const storage = fakeStorage();
  const responseWithUnexpectedFullPhone = sessionResponse(
    "app_profile",
    "apr_profile",
    {
      ...profile(),
      phoneNumber: "13812345678"
    } as AppUserProfile
  );
  const manager = createSessionManager(
    fakeDependencies({ storage, login: async () => responseWithUnexpectedFullPhone })
  );

  await manager.silentLogin();

  const persisted = storage.peek(AUTH_STATE_KEY);
  assert.equal(JSON.stringify(persisted).includes("13812345678"), false);
  assert.equal(JSON.stringify(persisted).includes("phoneNumber\""), false);
  const restarted = createSessionManager(fakeDependencies({ storage }));
  const restored = restarted.restore();
  assert.equal(restored.profile?.phoneNumberMasked, "138****5678");
  assert.deepEqual(restored.profile, profile());
});

test("restores a legacy cached profile without a nickname", () => {
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_legacy_profile",
      refreshToken: "apr_legacy_profile",
      accessExpiresAt: NOW + 60_000,
      profile: {
        userId: "7",
        openidMasked: "o****d",
        phoneAuthorized: false,
        phoneNumberMasked: null
      }
    }
  });
  const manager = createSessionManager(fakeDependencies({ storage }));

  const restored = manager.restore();

  assert.equal(restored.profile?.nickname, "用户7");
});

test("normalizes an omitted backend phoneNumberMasked field to null", async () => {
  const storage = fakeStorage();
  const userWithoutNullableField = {
    userId: "7",
    openidMasked: "o****d",
    phoneAuthorized: false
  } as AppUserProfile;
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      login: async () =>
        sessionResponse("app_without_phone", "apr_without_phone", userWithoutNullableField)
    })
  );

  await manager.silentLogin();

  assert.equal(manager.getState().profile?.phoneNumberMasked, null);
  const persisted = storage.peek(AUTH_STATE_KEY) as {
    profile?: { phoneNumberMasked?: unknown };
  };
  assert.equal(persisted.profile?.phoneNumberMasked, null);
});

test("logout calls the backend once and clears versioned plus legacy keys on failure", async () => {
  let logoutCalls = 0;
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: NOW + 60_000,
      profile: profile()
    },
    [LEGACY_ACCESS_KEY]: "app_legacy",
    [LEGACY_REFRESH_KEY]: "apr_legacy"
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      logout: async (accessToken) => {
        logoutCalls += 1;
        assert.equal(accessToken, "app_old");
        throw new Error("backend unavailable");
      }
    })
  );
  manager.restore();

  await assert.rejects(manager.logout(), /backend unavailable/);

  assert.equal(logoutCalls, 1);
  assert.equal(storage.has(AUTH_STATE_KEY), false);
  assert.equal(storage.has(LEGACY_ACCESS_KEY), false);
  assert.equal(storage.has(LEGACY_REFRESH_KEY), false);
  assert.equal(manager.getState().accessToken, "");
  assert.equal(manager.getState().profile, null);
});

test("logout tombstones tokens when every storage removal fails", async () => {
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_raw",
      refreshToken: "apr_raw",
      accessExpiresAt: NOW + 60_000,
      profile: profile()
    },
    [LEGACY_ACCESS_KEY]: "app_legacy_raw",
    [LEGACY_REFRESH_KEY]: "apr_legacy_raw"
  });
  for (const key of [AUTH_STATE_KEY, LEGACY_ACCESS_KEY, LEGACY_REFRESH_KEY]) {
    storage.failRemoveKeys.add(key);
  }
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      logout: async () => {
        throw new Error("backend unavailable");
      }
    })
  );
  manager.restore();

  await assert.rejects(manager.logout(), /backend unavailable/);

  assert.deepEqual(storage.peek(AUTH_STATE_KEY), {
    version: AUTH_STATE_VERSION,
    accessToken: "",
    refreshToken: "",
    accessExpiresAt: 0,
    profile: null
  });
  assert.equal(storage.peek(LEGACY_ACCESS_KEY), null);
  assert.equal(storage.peek(LEGACY_REFRESH_KEY), null);
  assert.equal(
    JSON.stringify([
      storage.peek(AUTH_STATE_KEY),
      storage.peek(LEGACY_ACCESS_KEY),
      storage.peek(LEGACY_REFRESH_KEY)
    ]).includes("raw"),
    false
  );

  const restarted = createSessionManager(fakeDependencies({ storage }));
  const restored = restarted.restore();
  assert.equal(restored.accessToken, "");
  assert.equal(restored.refreshToken, "");
  assert.equal(restored.profile, null);
});

test("clear removes tokens when every tombstone write fails", () => {
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_raw",
      refreshToken: "apr_raw",
      accessExpiresAt: NOW + 60_000,
      profile: profile()
    }
  });
  const manager = createSessionManager(fakeDependencies({ storage }));
  manager.restore();
  storage.set(LEGACY_ACCESS_KEY, "app_legacy_raw");
  storage.set(LEGACY_REFRESH_KEY, "apr_legacy_raw");
  for (const key of [AUTH_STATE_KEY, LEGACY_ACCESS_KEY, LEGACY_REFRESH_KEY]) {
    storage.failSetKeys.add(key);
  }

  assert.doesNotThrow(() => manager.clear());

  assert.equal(storage.has(AUTH_STATE_KEY), false);
  assert.equal(storage.has(LEGACY_ACCESS_KEY), false);
  assert.equal(storage.has(LEGACY_REFRESH_KEY), false);
  assert.equal(manager.getState().accessToken, "");
});

test("clear reports cleanup failure after memory and flights are detached", async () => {
  let loginCalls = 0;
  const staleLogin = deferred<AppSessionResponse>();
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_raw",
      refreshToken: "apr_raw",
      accessExpiresAt: NOW + 60_000,
      profile: profile()
    }
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      login: async () => {
        loginCalls += 1;
        return loginCalls === 1
          ? staleLogin.promise
          : sessionResponse("app_current", "apr_current");
      }
    })
  );
  manager.restore();
  const staleFlight = manager.silentLogin();
  storage.set(LEGACY_ACCESS_KEY, "app_legacy_raw");
  storage.set(LEGACY_REFRESH_KEY, "apr_legacy_raw");
  for (const key of [AUTH_STATE_KEY, LEGACY_ACCESS_KEY, LEGACY_REFRESH_KEY]) {
    storage.failSetKeys.add(key);
    storage.failRemoveKeys.add(key);
  }

  assert.throws(() => manager.clear(), /会话清理失败/);

  assert.equal(manager.getState().accessToken, "");
  assert.equal(manager.getState().refreshToken, "");
  assert.equal(manager.getState().profile, null);
  storage.failSetKeys.clear();
  storage.failRemoveKeys.clear();
  const currentFlight = manager.silentLogin();
  staleLogin.resolve(sessionResponse("app_stale", "apr_stale"));
  const [, currentState] = await Promise.all([staleFlight, currentFlight]);
  assert.equal(loginCalls, 2);
  assert.equal(currentState.accessToken, "app_current");
  assert.equal(manager.getState().accessToken, "app_current");
});

test("clear prevents a late silent login from resurrecting auth state", async () => {
  const loginResult = deferred<AppSessionResponse>();
  const storage = fakeStorage();
  const manager = createSessionManager(
    fakeDependencies({ storage, login: async () => loginResult.promise })
  );

  const pending = manager.silentLogin();
  manager.clear();
  loginResult.resolve(sessionResponse("app_late", "apr_late"));
  await pending;

  assert.equal(manager.getState().accessToken, "");
  assert.equal(storage.has(AUTH_STATE_KEY), false);
});

test("clear detaches a stale login flight so ensureSession can start a new login", async () => {
  let loginCalls = 0;
  const firstLogin = deferred<AppSessionResponse>();
  const manager = createSessionManager(
    fakeDependencies({
      login: async () => {
        loginCalls += 1;
        return loginCalls === 1
          ? firstLogin.promise
          : sessionResponse("app_current", "apr_current");
      }
    })
  );

  const stale = manager.silentLogin();
  manager.clear();
  const current = manager.ensureSession();
  firstLogin.resolve(sessionResponse("app_stale", "apr_stale"));
  const [, currentState] = await Promise.all([stale, current]);

  assert.equal(loginCalls, 2);
  assert.equal(currentState.accessToken, "app_current");
  assert.equal(manager.getState().accessToken, "app_current");
});

test("clear detaches a hanging recovery before a new session recovers", async () => {
  let refreshCalls = 0;
  const staleRefresh = deferred<AppSessionResponse>();
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: NOW + 60_000,
      profile: profile()
    }
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      refresh: async () => {
        refreshCalls += 1;
        return refreshCalls === 1
          ? staleRefresh.promise
          : sessionResponse("app_recovered", "apr_recovered");
      },
      login: async () => sessionResponse("app_current", "apr_current")
    })
  );
  manager.restore();

  const staleRecovery = manager.recoverAfterUnauthorized("app_old");
  manager.clear();
  await manager.silentLogin();
  const currentRecovery = manager.recoverAfterUnauthorized("app_current");
  staleRefresh.resolve(sessionResponse("app_stale", "apr_stale"));
  await Promise.all([staleRecovery, currentRecovery]);

  assert.equal(refreshCalls, 2);
  assert.equal(manager.getState().accessToken, "app_recovered");
});

test("a stale failed token does not join recovery for the current session", async () => {
  const staleRefresh = deferred<AppSessionResponse>();
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: NOW + 60_000,
      profile: profile()
    }
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      refresh: async () => staleRefresh.promise,
      login: async () => sessionResponse("app_current", "apr_current")
    })
  );
  manager.restore();

  const currentRecovery = manager.recoverAfterUnauthorized("app_old");
  await manager.silentLogin();
  const staleTokenRecovery = manager.recoverAfterUnauthorized("app_old");
  const joinedCurrentRecovery = staleTokenRecovery === currentRecovery;
  staleRefresh.resolve(sessionResponse("app_stale", "apr_stale"));
  await Promise.all([currentRecovery, staleTokenRecovery]);

  assert.equal(joinedCurrentRecovery, false);
  assert.equal(manager.getState().accessToken, "app_current");
});

test("logout prevents a late refresh from resurrecting auth state", async () => {
  const refreshResult = deferred<AppSessionResponse>();
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: 0,
      profile: profile()
    }
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      refresh: async () => refreshResult.promise,
      logout: async () => undefined
    })
  );
  manager.restore();

  const pending = manager.refreshSession();
  await manager.logout();
  refreshResult.resolve(sessionResponse("app_late", "apr_late"));
  await pending;

  assert.equal(manager.getState().accessToken, "");
  assert.equal(storage.has(AUTH_STATE_KEY), false);
});

test("logout detaches a hanging recovery before a new session recovers", async () => {
  let refreshCalls = 0;
  const staleRefresh = deferred<AppSessionResponse>();
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: NOW + 60_000,
      profile: profile()
    }
  });
  const manager = createSessionManager(
    fakeDependencies({
      storage,
      refresh: async () => {
        refreshCalls += 1;
        return refreshCalls === 1
          ? staleRefresh.promise
          : sessionResponse("app_recovered", "apr_recovered");
      },
      login: async () => sessionResponse("app_current", "apr_current"),
      logout: async () => undefined
    })
  );
  manager.restore();

  const staleRecovery = manager.recoverAfterUnauthorized("app_old");
  await manager.logout();
  await manager.silentLogin();
  const currentRecovery = manager.recoverAfterUnauthorized("app_current");
  staleRefresh.resolve(sessionResponse("app_stale", "apr_stale"));
  await Promise.all([staleRecovery, currentRecovery]);

  assert.equal(refreshCalls, 2);
  assert.equal(manager.getState().accessToken, "app_recovered");
});

test("profile page keeps cached masked data on non-auth refresh failure", async () => {
  const module = await loadProfileModule();
  const cachedProfile = profile();
  const state = {
    version: 1 as const,
    accessToken: "app_cached",
    refreshToken: "apr_cached",
    accessExpiresAt: NOW + 60_000,
    profile: cachedProfile
  };
  const definition = module.createProfilePageDefinition({
    restoreSession: () => state,
    ensureSession: async () => state,
    getCurrentUser: async () => {
      throw new Error("network unavailable");
    },
    updateCurrentUserProfile: async (nickname) => profile({ nickname }),
    updateProfile: () => undefined,
    getSessionState: () => state,
    authorizePhone: async () => cachedProfile
  });
  const page = pageHarness(definition);

  await definition.onShow.call(page);

  assert.equal(page.data.isLoggedIn, true);
  assert.equal(page.data.loginStatus, "已登录：o****d");
  assert.equal(page.data.phoneStatus, "已授权：138****5678");
  assert.equal(page.data.phoneButtonText, "更换手机号");
  assert.equal(page.data.profileWarning, "资料刷新失败，请稍后重试");
});

test("profile page exposes the order center entry", async () => {
  const module = await loadProfileModule();
  const definition = module.createProfilePageDefinition({
    restoreSession: () => ({
      version: 1,
      accessToken: "",
      refreshToken: "",
      accessExpiresAt: 0,
      profile: null
    }),
    ensureSession: async () => ({
      version: 1,
      accessToken: "",
      refreshToken: "",
      accessExpiresAt: 0,
      profile: null
    }),
    getCurrentUser: async () => profile(),
    updateCurrentUserProfile: async (nickname) => profile({ nickname }),
    updateProfile: () => undefined,
    getSessionState: () => ({
      version: 1,
      accessToken: "",
      refreshToken: "",
      accessExpiresAt: 0,
      profile: null
    }),
    authorizePhone: async () => profile()
  });

  assert.deepEqual(definition.data.actionItems[0], {
    title: "我的订单",
    path: "/pages/order/list/list"
  });
  assert.deepEqual(definition.data.actionItems[1], {
    title: "在线客服",
    path: "/pages/customer-service/chat/chat"
  });
});

test("profile page renders logged out only after auth state is fully cleared", async () => {
  const module = await loadProfileModule();
  const emptyState = {
    version: 1 as const,
    accessToken: "",
    refreshToken: "",
    accessExpiresAt: 0,
    profile: null
  };
  const definition = module.createProfilePageDefinition({
    restoreSession: () => emptyState,
    ensureSession: async () => {
      throw new Error("authentication required");
    },
    getCurrentUser: async () => profile(),
    updateCurrentUserProfile: async (nickname) => profile({ nickname }),
    updateProfile: () => undefined,
    getSessionState: () => emptyState,
    authorizePhone: async () => profile()
  });
  const page = pageHarness(definition);

  await definition.onShow.call(page);

  assert.equal(page.data.isLoggedIn, false);
  assert.equal(page.data.loginStatus, "未登录");
  assert.equal(page.data.phoneStatus, "手机号未授权");
  assert.equal(page.data.phoneButtonText, "授权手机号");
});

test("profile page does not render logged out when auth remains but no profile is cached", async () => {
  const module = await loadProfileModule();
  const authenticatedWithoutProfile = {
    version: 1 as const,
    accessToken: "app_cached",
    refreshToken: "apr_cached",
    accessExpiresAt: NOW + 60_000,
    profile: null
  };
  const definition = module.createProfilePageDefinition({
    restoreSession: () => authenticatedWithoutProfile,
    ensureSession: async () => authenticatedWithoutProfile,
    getCurrentUser: async () => {
      throw new Error("service unavailable");
    },
    updateCurrentUserProfile: async (nickname) => profile({ nickname }),
    updateProfile: () => undefined,
    getSessionState: () => authenticatedWithoutProfile,
    authorizePhone: async () => profile()
  });
  const page = pageHarness(definition);

  await definition.onShow.call(page);

  assert.equal(page.data.isLoggedIn, true);
  assert.equal(page.data.loginStatus, "已登录");
  assert.equal(page.data.profileWarning, "资料刷新失败，请稍后重试");
});

test("profile page maps phone cancellation and capability failures to non-blocking messages", async () => {
  const module = await loadProfileModule();
  const state = {
    version: 1 as const,
    accessToken: "app_cached",
    refreshToken: "apr_cached",
    accessExpiresAt: NOW + 60_000,
    profile: profile({ phoneAuthorized: false, phoneNumberMasked: null })
  };
  const definition = module.createProfilePageDefinition({
    restoreSession: () => state,
    ensureSession: async () => state,
    getCurrentUser: async () => state.profile,
    updateCurrentUserProfile: async (nickname) => profile({ nickname }),
    updateProfile: () => undefined,
    getSessionState: () => state,
    authorizePhone: async () => {
      throw new Error("stable_token capability unavailable");
    }
  });
  const page = pageHarness(definition);
  page.data.isLoggedIn = true;

  await definition.onGetPhoneNumber.call(page, { detail: { errno: 1400001 } });
  assert.equal(page.data.phoneStatus, "已取消手机号授权");
  await definition.onGetPhoneNumber.call(page, { detail: {} });
  assert.equal(page.data.phoneStatus, "未获取到手机号授权信息");
  await definition.onGetPhoneNumber.call(page, { detail: { code: "phone-code" } });
  assert.equal(page.data.phoneStatus, "手机号快速验证暂不可用，请稍后重试");
  assert.equal(page.data.isLoggedIn, true);
});

test("successful phone authorization clears an earlier profile warning", async () => {
  const module = await loadProfileModule();
  const unauthorizedProfile = profile({
    phoneAuthorized: false,
    phoneNumberMasked: null
  });
  const authorizedProfile = profile();
  const state = {
    version: 1 as const,
    accessToken: "app_cached",
    refreshToken: "apr_cached",
    accessExpiresAt: NOW + 60_000,
    profile: unauthorizedProfile
  };
  let persistedProfile: AppUserProfile | null = null;
  const definition = module.createProfilePageDefinition({
    restoreSession: () => state,
    ensureSession: async () => state,
    getCurrentUser: async () => unauthorizedProfile,
    updateCurrentUserProfile: async (nickname) => profile({ nickname }),
    updateProfile: (nextProfile) => {
      persistedProfile = nextProfile;
    },
    getSessionState: () => state,
    authorizePhone: async () => authorizedProfile
  });
  const page = pageHarness(definition);
  page.data.isLoggedIn = true;
  page.data.profileWarning = "资料刷新失败，请稍后重试";
  page.data.phoneStatus = "手机号授权失败，请稍后重试";

  await definition.onGetPhoneNumber.call(page, {
    detail: { code: "phone-code" }
  });

  assert.deepEqual(persistedProfile, authorizedProfile);
  assert.equal(page.data.profileWarning, "");
  assert.equal(page.data.phoneStatus, "已授权：138****5678");
  assert.equal(page.data.phoneButtonText, "更换手机号");
  assert.equal(page.data.phoneAuthorizing, false);
});

test("profile page validates and saves a trimmed nickname", async () => {
  const module = await loadProfileModule();
  const currentProfile = profile();
  const state = {
    version: 1 as const,
    accessToken: "app_cached",
    refreshToken: "apr_cached",
    accessExpiresAt: NOW + 60_000,
    profile: currentProfile
  };
  let submittedNickname = "";
  const persistedProfiles: AppUserProfile[] = [];
  const definition = module.createProfilePageDefinition({
    restoreSession: () => state,
    ensureSession: async () => state,
    getCurrentUser: async () => currentProfile,
    updateCurrentUserProfile: async (nickname) => {
      submittedNickname = nickname;
      return profile({ nickname });
    },
    updateProfile: (nextProfile) => {
      persistedProfiles.push(nextProfile);
    },
    getSessionState: () => state,
    authorizePhone: async () => currentProfile
  });
  const page = pageHarness(definition);
  page.data.isLoggedIn = true;

  definition.onStartNicknameEdit.call(page);
  definition.onNicknameInput.call(page, { detail: { value: " 新名称 " } });
  await definition.onSaveNickname.call(page);

  assert.equal(submittedNickname, "新名称");
  assert.equal(persistedProfiles[0]?.nickname, "新名称");
  assert.equal(page.data.nickname, "新名称");
  assert.equal(page.data.nicknameEditing, false);
  assert.equal(page.data.nicknameSaving, false);
  assert.equal(page.data.nicknameMessage, "用户名称已保存");
});

type ProfileDefinition = ReturnType<typeof createProfilePageDefinition>;
type ProfilePageHarness = ProfileDefinition & ProfilePageContext;

function pageHarness(definition: ProfileDefinition): ProfilePageHarness {
  const page = {
    ...definition,
    data: structuredClone(definition.data),
    setData(values: Partial<ProfilePageContext["data"]>) {
      Object.assign(this.data, values);
    }
  } as ProfilePageHarness;
  return page;
}

let profileModulePromise:
  | Promise<typeof import("../pages/profile/profile")>
  | undefined;

function loadProfileModule(): Promise<typeof import("../pages/profile/profile")> {
  if (!profileModulePromise) {
    const globals = globalThis as unknown as Record<string, unknown>;
    globals.Page = () => undefined;
    profileModulePromise = import("../pages/profile/profile");
  }
  return profileModulePromise;
}
