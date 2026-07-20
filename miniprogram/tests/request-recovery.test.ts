import assert from "node:assert/strict";
import test from "node:test";

import type {
  ApiResponse,
  AppSessionResponse,
  AppUserProfile,
  StorageAssetUploadResponse
} from "../types/api";
import { rawRequest, type RawHttpResult } from "../utils/http";
import { withAuthRecovery } from "../utils/request";
import {
  createEvidenceUploader,
  uploadEvidenceFile
} from "../services/storage";
import {
  AUTH_STATE_KEY,
  createSessionManager,
  type SessionManager,
  type SessionStorage
} from "../services/session";

const NOW = 1_700_000_000_000;

function profile(): AppUserProfile {
  return {
    userId: "2075761422822531074",
    nickname: "山茶花用户",
    openidMasked: "o****d",
    phoneAuthorized: true,
    phoneNumberMasked: "138****5678"
  };
}

function sessionResponse(accessToken: string, refreshToken: string): AppSessionResponse {
  return {
    token: accessToken,
    refreshToken,
    expiresIn: 3600,
    user: profile()
  };
}

interface FakeStorage extends SessionStorage {
  peek(key: string): unknown;
}

function fakeStorage(initial: Record<string, unknown> = {}): FakeStorage {
  const values = new Map(Object.entries(initial));
  return {
    get: (key) => structuredClone(values.get(key)),
    set: (key, value) => values.set(key, structuredClone(value)),
    remove: (key) => values.delete(key),
    peek: (key) => structuredClone(values.get(key))
  };
}

function authenticatedStorage(
  accessToken = "app_old",
  refreshToken = "apr_old"
): FakeStorage {
  return fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken,
      refreshToken,
      accessExpiresAt: NOW + 60_000,
      profile: profile()
    }
  });
}

function createAuthenticatedManager(options: {
  storage?: FakeStorage;
  refresh?: (refreshToken: string) => Promise<AppSessionResponse>;
  login?: () => Promise<AppSessionResponse>;
} = {}): SessionManager {
  const manager = createSessionManager({
    storage: options.storage ?? authenticatedStorage(),
    now: () => NOW,
    refresh:
      options.refresh ??
      (async () => sessionResponse("app_rotated", "apr_rotated")),
    login:
      options.login ??
      (async () => sessionResponse("app_login", "apr_login")),
    logout: async () => undefined
  });
  manager.restore();
  return manager;
}

function response<T = never>(
  statusCode: number,
  authTokenUsed: string | null,
  data: T | null = null
): RawHttpResult<T> {
  const body: ApiResponse<T> =
    statusCode >= 200 && statusCode < 300
      ? { code: 200, msg: "ok", data: data as T }
      : { code: 100001, msg: "authentication required", data: data as T };
  return { statusCode, body, authTokenUsed };
}

async function exerciseRecovery(options: {
  originalStatuses: number[];
  refreshResult?: AppSessionResponse;
  refreshError?: Error;
  loginResult?: AppSessionResponse;
}) {
  let refreshCalls = 0;
  let loginCalls = 0;
  let originalCalls = 0;
  const manager = createAuthenticatedManager({
    refresh: async () => {
      refreshCalls += 1;
      if (options.refreshError) {
        throw options.refreshError;
      }
      return options.refreshResult ?? sessionResponse("app_rotated", "apr_rotated");
    },
    login: async () => {
      loginCalls += 1;
      return options.loginResult ?? sessionResponse("app_login", "apr_login");
    }
  });
  const statuses = [...options.originalStatuses];

  const result = await withAuthRecovery(
    async (authToken) => {
      originalCalls += 1;
      const status = statuses.shift();
      assert.notEqual(status, undefined, "request attempted more than configured twice");
      return response(status as number, authToken, "result");
    },
    {},
    manager
  );

  return { result, manager, refreshCalls, loginCalls, originalCalls };
}

test("refreshes once and retries the original request once", async () => {
  const result = await exerciseRecovery({
    originalStatuses: [401, 200],
    refreshResult: sessionResponse("app_rotated", "apr_rotated")
  });

  assert.equal(result.refreshCalls, 1);
  assert.equal(result.loginCalls, 0);
  assert.equal(result.originalCalls, 2);
  assert.equal(result.result.statusCode, 200);
  assert.equal(result.manager.getState().accessToken, "app_rotated");
});

test("falls back to one silent login without a retry loop", async () => {
  const result = await exerciseRecovery({
    originalStatuses: [401, 401],
    refreshError: new Error("expired"),
    loginResult: sessionResponse("app_login", "apr_login")
  });

  assert.equal(result.refreshCalls, 1);
  assert.equal(result.loginCalls, 1);
  assert.equal(result.originalCalls, 2);
  assert.equal(result.result.statusCode, 401);
  assert.equal(result.manager.getState().accessToken, "");
});

test("coalesces concurrent and staggered 401 responses into one recovery", async () => {
  let refreshCalls = 0;
  const refreshObserved = deferred<void>();
  const manager = createAuthenticatedManager({
    refresh: async () => {
      refreshCalls += 1;
      refreshObserved.resolve();
      return sessionResponse("app_rotated", "apr_rotated");
    }
  });
  const firstAttempts = Array.from({ length: 4 }, () => deferred<RawHttpResult<string>>());
  const callCounts = [0, 0, 0, 0];
  const firstTokens: Array<string | null> = [];

  const calls = callCounts.map((_, index) =>
    withAuthRecovery(
      async (authToken) => {
        callCounts[index] += 1;
        if (callCounts[index] === 1) {
          firstTokens[index] = authToken;
          return firstAttempts[index].promise;
        }
        return response(200, authToken, `result-${index}`);
      },
      {},
      manager
    )
  );
  assert.deepEqual(callCounts, [1, 1, 1, 1]);
  firstAttempts[0].resolve(response(401, firstTokens[0]));
  await refreshObserved.promise;
  firstAttempts[1].resolve(response(401, firstTokens[1]));
  await Promise.resolve();
  firstAttempts[2].resolve(response(401, firstTokens[2]));
  firstAttempts[3].resolve(response(401, firstTokens[3]));
  await Promise.all(calls);

  assert.equal(refreshCalls, 1);
  assert.deepEqual(firstTokens, ["app_old", "app_old", "app_old", "app_old"]);
  assert.deepEqual(callCounts, [2, 2, 2, 2]);
});

test("shares one fallback silent login across concurrent refresh failures", async () => {
  let refreshCalls = 0;
  let loginCalls = 0;
  const refreshResult = deferred<AppSessionResponse>();
  const manager = createAuthenticatedManager({
    refresh: async () => {
      refreshCalls += 1;
      return refreshResult.promise;
    },
    login: async () => {
      loginCalls += 1;
      return sessionResponse("app_login", "apr_login");
    }
  });
  const callCounts = [0, 0, 0];
  const calls = callCounts.map((_, index) =>
    withAuthRecovery(
      async (authToken) => {
        callCounts[index] += 1;
        return response(callCounts[index] === 1 ? 401 : 200, authToken, `result-${index}`);
      },
      {},
      manager
    )
  );
  await Promise.resolve();
  refreshResult.reject(new Error("refresh expired"));
  await Promise.all(calls);

  assert.equal(refreshCalls, 1);
  assert.equal(loginCalls, 1);
  assert.deepEqual(callCounts, [2, 2, 2]);
});

test("ensureSession and unauthorized recovery await the same fallback login", async () => {
  let refreshCalls = 0;
  let loginCalls = 0;
  const refreshResult = deferred<AppSessionResponse>();
  const loginResult = deferred<AppSessionResponse>();
  const loginStarted = deferred<void>();
  const storage = fakeStorage({
    [AUTH_STATE_KEY]: {
      version: 1,
      accessToken: "app_old",
      refreshToken: "apr_old",
      accessExpiresAt: 0,
      profile: profile()
    }
  });
  const manager = createAuthenticatedManager({
    storage,
    refresh: async () => {
      refreshCalls += 1;
      return refreshResult.promise;
    },
    login: async () => {
      loginCalls += 1;
      loginStarted.resolve();
      return loginResult.promise;
    }
  });
  const requestTokens: Array<string | null> = [];
  let requestCalls = 0;

  const ensured = manager.ensureSession();
  const recoveredRequest = withAuthRecovery(
    async (authToken) => {
      requestCalls += 1;
      requestTokens.push(authToken);
      return response(requestCalls === 1 ? 401 : 200, authToken, "ok");
    },
    {},
    manager
  );
  refreshResult.reject(new Error("refresh expired"));
  await loginStarted.promise;
  await new Promise<void>((resolve) => setImmediate(resolve));
  const retriedBeforeLoginResolved = requestCalls > 1;
  loginResult.resolve(sessionResponse("app_login", "apr_login"));
  const [ensuredState, requestResult] = await Promise.all([
    ensured,
    recoveredRequest
  ]);

  assert.equal(refreshCalls, 1);
  assert.equal(loginCalls, 1);
  assert.equal(retriedBeforeLoginResolved, false);
  assert.equal(ensuredState.accessToken, "app_login");
  assert.equal(requestResult.statusCode, 200);
  assert.deepEqual(requestTokens, ["app_old", "app_login"]);
});

test("late request second 401 cannot clear a newer session", async () => {
  let loginCalls = 0;
  const secondAttempt = deferred<RawHttpResult<string>>();
  const secondStarted = deferred<string | null>();
  const manager = createAuthenticatedManager({
    login: async () => {
      loginCalls += 1;
      return sessionResponse("app_newer", "apr_newer");
    }
  });
  let calls = 0;

  const pending = withAuthRecovery(
    async (authToken) => {
      calls += 1;
      if (calls === 1) {
        return response(401, authToken);
      }
      secondStarted.resolve(authToken);
      return secondAttempt.promise;
    },
    {},
    manager
  );
  const secondToken = await secondStarted.promise;
  assert.equal(secondToken, "app_rotated");
  await manager.silentLogin();
  secondAttempt.resolve(response(401, secondToken));
  const result = await pending;

  assert.equal(result.statusCode, 401);
  assert.equal(loginCalls, 1);
  assert.equal(manager.getState().accessToken, "app_newer");
});

test("auth-disabled and recovery-disabled calls never recurse", async () => {
  let refreshCalls = 0;
  const manager = createAuthenticatedManager({
    refresh: async () => {
      refreshCalls += 1;
      return sessionResponse("app_rotated", "apr_rotated");
    }
  });
  let calls = 0;
  const send = async (authToken: string | null) => {
    calls += 1;
    return response(401, authToken);
  };

  await withAuthRecovery(send, { auth: false }, manager);
  await withAuthRecovery(send, { recoverAuth: false }, manager);

  assert.equal(calls, 2);
  assert.equal(refreshCalls, 0);
});

test("rawRequest preserves an error envelope whose null data field is omitted", async () => {
  const globals = globalThis as unknown as Record<string, unknown>;
  const previousWx = globals.wx;
  const previousGetApp = globals.getApp;
  globals.getApp = () => ({ globalData: { apiBaseUrl: "https://example.test" } });
  globals.wx = {
    request(options: {
      success(response: { statusCode: number; data: unknown }): void;
    }) {
      options.success({
        statusCode: 412,
        data: {
          code: 100412,
          msg: "手机号快速验证能力暂不可用"
        }
      });
    }
  };

  try {
    const result = await rawRequest<never>({
      url: "/app/auth/phone",
      method: "POST",
      authToken: "app_old"
    });

    assert.equal(result.statusCode, 412);
    assert.equal(result.body?.code, 100412);
    assert.equal(result.body?.msg, "手机号快速验证能力暂不可用");
    assert.equal(result.body?.data, null);
  } finally {
    if (previousWx === undefined) {
      delete globals.wx;
    } else {
      globals.wx = previousWx;
    }
    if (previousGetApp === undefined) {
      delete globals.getApp;
    } else {
      globals.getApp = previousGetApp;
    }
  }
});

test("upload preserves an error envelope whose null data field is omitted", async () => {
  const globals = globalThis as unknown as Record<string, unknown>;
  const previousWx = globals.wx;
  const previousGetApp = globals.getApp;
  globals.getApp = () => ({ globalData: { apiBaseUrl: "https://example.test" } });
  globals.wx = {
    uploadFile(options: {
      url: string;
      formData?: Record<string, unknown>;
      success(response: { statusCode: number; data: string }): void;
    }) {
      assert.equal(
        options.url,
        "https://example.test/app/orders/42/after-sale-evidence"
      );
      assert.equal("formData" in options, false);
      options.success({
        statusCode: 412,
        data: JSON.stringify({
          code: 100412,
          msg: "上传能力暂不可用"
        })
      });
    }
  };

  try {
    await assert.rejects(
      uploadEvidenceFile("/tmp/evidence.jpg", 42),
      /上传能力暂不可用/
    );
  } finally {
    if (previousWx === undefined) {
      delete globals.wx;
    } else {
      globals.wx = previousWx;
    }
    if (previousGetApp === undefined) {
      delete globals.getApp;
    } else {
      globals.getApp = previousGetApp;
    }
  }
});

function uploadResponse(): StorageAssetUploadResponse {
  return {
    id: 99,
    scope: "ATTACHMENT",
    mediaKind: "IMAGE",
    folderId: null,
    visibility: "PRIVATE",
    provider: "local",
    originalFilename: "evidence.jpg",
    contentType: "image/jpeg",
    extension: "jpg",
    sizeBytes: 1024,
    status: "ACTIVE",
    uploadedByType: "APP",
    uploadedById: "7",
    url: null,
    publicUrl: null,
    createdAt: "2026-07-10T00:00:00Z",
    updatedAt: "2026-07-10T00:00:00Z",
    deletedAt: null
  };
}

test("uploadEvidenceFile shares one recovery and retries one upload once", async () => {
  let refreshCalls = 0;
  let uploadCalls = 0;
  const tokens: Array<string | null> = [];
  const manager = createAuthenticatedManager({
    refresh: async () => {
      refreshCalls += 1;
      return sessionResponse("app_rotated", "apr_rotated");
    }
  });
  const uploadEvidenceFile = createEvidenceUploader({
    session: manager,
    upload: async (_filePath, orderId, authToken) => {
      uploadCalls += 1;
      tokens.push(authToken);
      assert.equal(orderId, 42);
      return response(uploadCalls === 1 ? 401 : 200, authToken, uploadResponse());
    }
  });

  const uploaded = await uploadEvidenceFile("/tmp/evidence.jpg", 42);

  assert.equal(uploaded.id, 99);
  assert.equal(refreshCalls, 1);
  assert.equal(uploadCalls, 2);
  assert.deepEqual(tokens, ["app_old", "app_rotated"]);
});

test("uploadEvidenceFile accepts the backend string uploadedById contract", async () => {
  const manager = createAuthenticatedManager();
  const backendResponse = {
    ...uploadResponse(),
    uploadedById: "7"
  };
  const uploadEvidenceFile = createEvidenceUploader({
    session: manager,
    upload: async (_filePath, _orderId, authToken) =>
      response(200, authToken, backendResponse)
  });

  const uploaded = await uploadEvidenceFile("/tmp/evidence.jpg", 42);

  assert.equal(uploaded.uploadedById, "7");
});

test("uploadEvidenceFile rejects assets outside the after-sale attachment contract", async () => {
  const manager = createAuthenticatedManager();
  const invalidResponses: StorageAssetUploadResponse[] = [
    { ...uploadResponse(), scope: "LIBRARY" },
    { ...uploadResponse(), mediaKind: "VIDEO" },
    { ...uploadResponse(), visibility: "PUBLIC" },
    { ...uploadResponse(), uploadedByType: "ADMIN" }
  ];

  for (const invalidResponse of invalidResponses) {
    const uploadEvidenceFile = createEvidenceUploader({
      session: manager,
      upload: async (_filePath, _orderId, authToken) =>
        response(200, authToken, invalidResponse)
    });

    await assert.rejects(
      uploadEvidenceFile("/tmp/evidence.jpg", 42),
      /上传响应格式错误/
    );
  }
});

test("uploadEvidenceFile stops after a second 401", async () => {
  let uploadCalls = 0;
  const manager = createAuthenticatedManager();
  const uploadEvidenceFile = createEvidenceUploader({
    session: manager,
    upload: async (_filePath, _orderId, authToken) => {
      uploadCalls += 1;
      return response(401, authToken);
    }
  });

  await assert.rejects(
    uploadEvidenceFile("/tmp/evidence.jpg", 42),
    /authentication required/
  );

  assert.equal(uploadCalls, 2);
  assert.equal(manager.getState().accessToken, "");
});

test("late upload second 401 cannot clear a newer session", async () => {
  const secondAttempt = deferred<RawHttpResult<StorageAssetUploadResponse>>();
  const secondStarted = deferred<string | null>();
  const manager = createAuthenticatedManager({
    login: async () => sessionResponse("app_newer", "apr_newer")
  });
  let uploadCalls = 0;
  const uploadEvidenceFile = createEvidenceUploader({
    session: manager,
    upload: async (_filePath, _orderId, authToken) => {
      uploadCalls += 1;
      if (uploadCalls === 1) {
        return response(401, authToken);
      }
      secondStarted.resolve(authToken);
      return secondAttempt.promise;
    }
  });

  const pending = uploadEvidenceFile("/tmp/evidence.jpg", 42);
  const secondToken = await secondStarted.promise;
  assert.equal(secondToken, "app_rotated");
  await manager.silentLogin();
  secondAttempt.resolve(response(401, secondToken));
  await assert.rejects(pending, /authentication required/);

  assert.equal(uploadCalls, 2);
  assert.equal(manager.getState().accessToken, "app_newer");
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
