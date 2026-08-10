import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";

import {
  authorizePreparedWechatPhoneNumber,
  authorizePhoneNumber,
  clearSession,
  clearSessionIfCurrent,
  commitPreparedWechatLogin,
  discardPreparedWechatLogin,
  getSessionState,
  loginWithWechat,
  logoutSession,
  onSessionExpired,
  prepareWechatLogin,
  recoverAfterUnauthorized,
  refreshSession
} from "../miniprogram/services/session";
import type { ApiResponse } from "../miniprogram/types/api";
import type { AppSessionResponse } from "../miniprogram/types/auth";
import type { PrivacyPolicyConsent } from "../miniprogram/types/compliance";
import { isApiError } from "../miniprogram/utils/api-error";
import { request } from "../miniprogram/utils/request";
import { uploadFile as uploadAuthenticatedFile } from "../miniprogram/utils/upload";
import { getHome } from "../miniprogram/services/home";
import { getCurrentLegalDocument } from "../miniprogram/services/compliance";
import {
  saveAvatar,
  updateMyProfile
} from "../miniprogram/services/user-profile";
import { uploadAfterSaleEvidence } from "../miniprogram/services/after-sale";
import { uploadCustomerServiceImage } from "../miniprogram/services/customer-service";

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
  timeout?: number;
  enableHttp2?: boolean;
  success?: (response: FakeRequestResponse) => void;
  fail?: (error: { errMsg: string }) => void;
}

interface FakeLoginOptions {
  success?: (result: { code: string; errMsg: string }) => void;
  fail?: (error: { errMsg: string }) => void;
}

interface FakeUploadResponse {
  data: string;
  statusCode: number;
  header: Record<string, string>;
}

interface FakeUploadCall {
  url: string;
  filePath: string;
  name: string;
  header?: Record<string, string>;
  formData?: Record<string, string>;
  enableHttp2?: boolean;
  success?: (response: FakeUploadResponse) => void;
  fail?: (error: { errMsg: string }) => void;
}

interface FakeGetFileInfoOptions {
  success?: (result: { size: number }) => void;
  fail?: (error: { errMsg: string }) => void;
}

interface FakeGetImageInfoOptions {
  success?: (result: { type: string }) => void;
  fail?: (error: { errMsg: string }) => void;
}

const storage = new Map<string, unknown>();
const pendingRequests: FakeRequestCall[] = [];
const pendingUploads: FakeUploadCall[] = [];
let loginCallCount = 0;

const TEST_PRIVACY_CONSENT: PrivacyPolicyConsent = {
  privacyPolicyVersion: "2026.08.09",
  privacyPolicyAccepted: true,
  miniProgramEnv: "develop"
};

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
  },
  uploadFile(options: FakeUploadCall): WechatMiniprogram.UploadTask {
    pendingUploads.push(options);
    return {} as WechatMiniprogram.UploadTask;
  },
  getFileSystemManager(): WechatMiniprogram.FileSystemManager {
    return {
      getFileInfo(options: FakeGetFileInfoOptions): void {
        options.success?.({ size: 1024 });
      }
    } as unknown as WechatMiniprogram.FileSystemManager;
  },
  getImageInfo(options: FakeGetImageInfoOptions): void {
    options.success?.({ type: "png" });
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

function takeUpload(path: string): FakeUploadCall {
  const index = pendingUploads.findIndex((call) => call.url.endsWith(path));
  assert.notEqual(index, -1, `没有找到上传请求 ${path}`);
  const [call] = pendingUploads.splice(index, 1);
  assert.ok(call);
  return call;
}

function respond<T>(
  call: FakeRequestCall,
  statusCode: number,
  body: ApiResponse<T>,
  header: Record<string, string> = {}
): void {
  assert.ok(call.success, `请求 ${call.url} 缺少 success 回调`);
  call.success({
    data: body,
    statusCode,
    header,
    cookies: []
  });
}

function respondUpload<T>(
  call: FakeUploadCall,
  statusCode: number,
  body: ApiResponse<T>,
  header: Record<string, string> = {}
): void {
  assert.ok(call.success, `上传请求 ${call.url} 缺少 success 回调`);
  call.success({
    data: JSON.stringify(body),
    statusCode,
    header
  });
}

function respondRawUpload(
  call: FakeUploadCall,
  statusCode: number,
  data = ""
): void {
  assert.ok(call.success, `上传请求 ${call.url} 缺少 success 回调`);
  call.success({
    data,
    statusCode,
    header: {}
  });
}

function directUploadGrant(uploadId: string) {
  return {
    uploadId,
    uploadUrl: "https://uploads.storage.example",
    formData: {
      key: `staging/${uploadId}.png`,
      policy: "signed-policy"
    },
    expiresAt: "2099-01-01T00:00:00Z"
  };
}

async function flushTasks(): Promise<void> {
  await new Promise<void>((resolve) => setImmediate(resolve));
}

async function establishSession(suffix: string): Promise<void> {
  const login = loginWithWechat(TEST_PRIVACY_CONSENT);
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
  pendingUploads.length = 0;
  loginCallCount = 0;
});

test("并发主动登录只交换一次微信 code", async () => {
  const first = loginWithWechat(TEST_PRIVACY_CONSENT);
  const second = loginWithWechat(TEST_PRIVACY_CONSENT);
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

test("登录预加载不会提交会话，用户确认后才持久化", async () => {
  const preparing = prepareWechatLogin(TEST_PRIVACY_CONSENT);
  await flushTasks();
  respond(
    takeRequest("/app/auth/login"),
    200,
    { code: 200, msg: "success", data: sessionResponse("prepared") }
  );
  const prepared = await preparing;

  assert.equal(prepared.user.userId, "prepared");
  assert.equal(getSessionState().accessToken, "");
  assert.equal(storage.size, 0);

  const committed = commitPreparedWechatLogin(prepared);
  assert.equal(committed.accessToken, "access-prepared");
  assert.equal(getSessionState().accessToken, "access-prepared");
  assert.equal(storage.size, 1);
});

test("登录预加载携带用户同意的政策修订与小程序环境", async () => {
  const preparing = prepareWechatLogin({
    privacyPolicyVersion: "2026.08.09",
    privacyPolicyAccepted: true,
    miniProgramEnv: "develop"
  });
  await flushTasks();
  const loginCall = takeRequest("/app/auth/login");
  assert.deepEqual(loginCall.data, {
    code: "wx-code-1",
    privacyPolicyVersion: "2026.08.09",
    privacyPolicyAccepted: true,
    miniProgramEnv: "develop"
  });
  respond(
    loginCall,
    200,
    { code: 200, msg: "success", data: sessionResponse("consented") }
  );
  const prepared = await preparing;
  assert.equal(prepared.user.userId, "consented");
  assert.equal(getSessionState().accessToken, "");
});

test("政策确认信息无效时不会调用 wx.login", async () => {
  await assert.rejects(
    prepareWechatLogin({
      privacyPolicyVersion: " ",
      privacyPolicyAccepted: true,
      miniProgramEnv: "develop"
    }),
    (error: unknown) => isApiError(error) && error.kind === "PROTOCOL"
  );
  assert.equal(loginCallCount, 0);
  assert.equal(pendingRequests.length, 0);
});

test("当前隐私政策匿名加载且拒绝非已发布修订", async () => {
  const loading = getCurrentLegalDocument("PRIVACY_POLICY");
  await flushTasks();
  const policyCall = takeRequest(
    "/app/compliance/documents/PRIVACY_POLICY/current"
  );
  assert.equal(policyCall.header?.Authorization, undefined);
  respond(policyCall, 200, {
    code: 200,
    msg: "success",
    data: {
      id: "1001",
      documentType: "PRIVACY_POLICY",
      version: "2026.08.09",
      title: "MuYbaby个人信息保护政策",
      content: "我们依法保护个人信息。",
      contentSha256: "a".repeat(64),
      status: "PUBLISHED"
    }
  });
  assert.equal((await loading).version, "2026.08.09");

  const rejecting = getCurrentLegalDocument("PRIVACY_POLICY");
  await flushTasks();
  const draftCall = takeRequest(
    "/app/compliance/documents/PRIVACY_POLICY/current"
  );
  respond(draftCall, 200, {
    code: 200,
    msg: "success",
    data: {
      id: "1002",
      documentType: "PRIVACY_POLICY",
      version: "draft",
      title: "草稿",
      content: "尚未发布",
      contentSha256: "b".repeat(64),
      status: "DRAFT"
    }
  });
  await assert.rejects(rejecting, (error: unknown) => (
    isApiError(error) && error.kind === "PROTOCOL"
  ));
});

test("未确认的登录可以撤销且不会改变全局会话", async () => {
  const preparing = prepareWechatLogin(TEST_PRIVACY_CONSENT);
  await flushTasks();
  respond(
    takeRequest("/app/auth/login"),
    200,
    { code: 200, msg: "success", data: sessionResponse("discarded") }
  );
  const prepared = await preparing;

  const discarding = discardPreparedWechatLogin(prepared);
  const logoutCall = takeRequest("/app/auth/logout");
  assert.equal(logoutCall.header?.Authorization, "Bearer access-discarded");
  assert.equal(getSessionState().accessToken, "");
  respond(logoutCall, 200, { code: 200, msg: "success" });
  await discarding;

  assert.throws(() => commitPreparedWechatLogin(prepared));
  assert.equal(storage.size, 0);
});

test("新用户手机号授权在待确认会话中完成，授权后再提交", async () => {
  const preparing = prepareWechatLogin(TEST_PRIVACY_CONSENT);
  await flushTasks();
  respond(
    takeRequest("/app/auth/login"),
    200,
    { code: 200, msg: "success", data: sessionResponse("phone") }
  );
  const prepared = await preparing;

  const authorizing = authorizePreparedWechatPhoneNumber(prepared, "phone-code");
  await flushTasks();
  const phoneCall = takeRequest("/app/auth/phone");
  assert.equal(phoneCall.header?.Authorization, "Bearer access-phone");
  respond(phoneCall, 200, {
    code: 200,
    msg: "success",
    data: {
      ...sessionResponse("phone").user,
      phoneAuthorized: true,
      phoneNumberMasked: "138****5678"
    }
  });
  await authorizing;

  assert.equal(getSessionState().accessToken, "");
  const committed = commitPreparedWechatLogin(prepared);
  assert.equal(committed.user?.phoneAuthorized, true);
  assert.equal(getSessionState().user?.phoneNumberMasked, "138****5678");
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
  const login = loginWithWechat(TEST_PRIVACY_CONSENT);
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

  const newLogin = loginWithWechat(TEST_PRIVACY_CONSENT);
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

test("未登录的受保护请求要求用户主动登录且不会调用 wx.login", async () => {
  await assert.rejects(
    request<{ ok: boolean }>({ url: "/app/protected" }),
    (error: unknown) => {
      assert.ok(isApiError(error));
      assert.equal(error.kind, "AUTH");
      assert.equal(error.message, "请先登录");
      return true;
    }
  );

  assert.equal(loginCallCount, 0);
  assert.equal(pendingRequests.length, 0);
});

test("刷新令牌失效后不再静默调用 wx.login", async () => {
  await establishSession("1");
  let expirationCount = 0;
  const stopListening = onSessionExpired(() => {
    expirationCount += 1;
  });
  const refresh = refreshSession();
  await flushTasks();
  respond(
    takeRequest("/app/auth/refresh"),
    401,
    { code: 100001, msg: "unauthorized" }
  );

  await assert.rejects(refresh, (error: unknown) => {
    assert.ok(isApiError(error));
    assert.equal(error.kind, "AUTH");
    assert.equal(error.message, "登录状态已失效，请重新登录");
    return true;
  });
  assert.equal(loginCallCount, 1);
  assert.equal(getSessionState().accessToken, "");
  assert.equal(pendingRequests.length, 0);
  assert.equal(expirationCount, 1);
  stopListening();
});

test("受保护请求和刷新同时 401 时只通知一次会话失效", async () => {
  await establishSession("1");
  let expirationCount = 0;
  const stopListening = onSessionExpired(() => {
    expirationCount += 1;
  });
  const protectedRequest = request<{ ok: boolean }>({
    url: "/app/protected"
  });
  await flushTasks();

  respond(
    takeRequest("/app/protected"),
    401,
    { code: 100001, msg: "unauthorized" }
  );
  await flushTasks();
  respond(
    takeRequest("/app/auth/refresh"),
    401,
    { code: 100001, msg: "unauthorized" }
  );

  await assert.rejects(protectedRequest, (error: unknown) => {
    assert.ok(isApiError(error));
    assert.equal(error.kind, "AUTH");
    return true;
  });
  assert.equal(getSessionState().accessToken, "");
  assert.equal(getSessionState().refreshToken, "");
  assert.equal(expirationCount, 1);
  stopListening();
});

test("手机号授权更新当前用户且不会重新登录", async () => {
  await establishSession("1");
  const authorization = authorizePhoneNumber("phone-code");
  await flushTasks();
  const phoneCall = takeRequest("/app/auth/phone");
  assert.deepEqual(phoneCall.data, { code: "phone-code" });
  assert.equal(phoneCall.header?.Authorization, "Bearer access-1");
  respond(phoneCall, 200, {
    code: 200,
    msg: "success",
    data: {
      userId: "1",
      nickname: "用户1",
      openidMasked: "openid****",
      phoneAuthorized: true,
      phoneNumberMasked: "138****5678"
    }
  });

  const profile = await authorization;
  assert.equal(profile.phoneAuthorized, true);
  assert.equal(getSessionState().user?.phoneNumberMasked, "138****5678");
  assert.equal(loginCallCount, 1);
});

test("微信昵称和头像更新会同步到当前会话", async () => {
  await establishSession("1");
  const nicknameUpdate = updateMyProfile("灶香集会员");
  await flushTasks();
  const profileCall = takeRequest("/app/users/me");
  assert.equal(profileCall.method, "PUT");
  assert.deepEqual(profileCall.data, { nickname: "灶香集会员" });
  respond(profileCall, 200, {
    code: 200,
    msg: "success",
    data: {
      userId: "1",
      nickname: "灶香集会员",
      openidMasked: "openid****",
      phoneAuthorized: true,
      phoneNumberMasked: "138****5678"
    }
  });
  await nicknameUpdate;

  const wechatAvatarUrl = "https://thirdwx.qlogo.cn/mmopen/vi_32/avatar/132";
  const remoteAvatarUpdate = saveAvatar(wechatAvatarUrl);
  await flushTasks();
  const remoteAvatarCall = takeRequest("/app/users/me/avatar");
  assert.equal(remoteAvatarCall.method, "PUT");
  assert.deepEqual(remoteAvatarCall.data, { avatarUrl: wechatAvatarUrl });
  respond(
    remoteAvatarCall,
    200,
    {
      code: 200,
      msg: "success",
      data: {
        userId: "1",
        nickname: "灶香集会员",
        avatarUrl: wechatAvatarUrl,
        openidMasked: "openid****",
        phoneAuthorized: true,
        phoneNumberMasked: "138****5678",
        remainingChanges: 2
      }
    }
  );
  const remoteAvatarResult = await remoteAvatarUpdate;
  const profile = remoteAvatarResult.profile;
  assert.equal(profile.avatarUrl, wechatAvatarUrl);
  assert.equal(remoteAvatarResult.remainingChanges, 2);
  assert.equal(getSessionState().user?.nickname, "灶香集会员");
  assert.equal(getSessionState().user?.avatarUrl, profile.avatarUrl);

  const avatarUpdate = saveAvatar("/tmp/avatar.png");
  await flushTasks();
  const avatarInitCall = takeRequest("/app/users/me/avatar/upload-sessions");
  assert.equal(avatarInitCall.method, "POST");
  assert.deepEqual(avatarInitCall.data, {
    originalFilename: "avatar.png",
    contentType: "image/png",
    sizeBytes: 1024
  });
  respond(
    avatarInitCall,
    200,
    {
      code: 200,
      msg: "success",
      data: {
        uploadId: "avatar-upload-1",
        uploadUrl: "https://uploads.storage.example",
        formData: {
          key: "staging/avatar-upload-1.png",
          policy: "signed-policy"
        },
        expiresAt: "2099-01-01T00:00:00Z"
      }
    }
  );
  await flushTasks();

  const avatarCosCall = takeUpload(
    "uploads.storage.example"
  );
  assert.equal(avatarCosCall.filePath, "/tmp/avatar.png");
  assert.equal(avatarCosCall.name, "file");
  assert.equal(avatarCosCall.enableHttp2, true);
  assert.equal(avatarCosCall.header?.Authorization, undefined);
  assert.deepEqual(avatarCosCall.formData, {
    key: "staging/avatar-upload-1.png",
    policy: "signed-policy"
  });
  respondRawUpload(avatarCosCall, 204);
  await flushTasks();

  const avatarCompleteCall = takeRequest(
    "/app/users/me/avatar/upload-sessions/avatar-upload-1/complete"
  );
  assert.equal(avatarCompleteCall.timeout, 180_000);
  respond(
    avatarCompleteCall,
    200,
    {
      code: 200,
      msg: "success",
      data: {
        userId: "1",
        nickname: "灶香集会员",
        avatarUrl: "https://oss.example.test/avatar.png",
        openidMasked: "openid****",
        phoneAuthorized: true,
        phoneNumberMasked: "138****5678",
        remainingChanges: 1
      }
    }
  );

  const uploadedAvatarResult = await avatarUpdate;
  const uploadedProfile = uploadedAvatarResult.profile;
  assert.equal(uploadedProfile.avatarUrl, "https://oss.example.test/avatar.png");
  assert.equal(uploadedAvatarResult.remainingChanges, 1);
  assert.equal(getSessionState().user?.avatarUrl, uploadedProfile.avatarUrl);
  assert.equal(pendingRequests.length, 0);
  assert.equal(pendingUploads.length, 0);
  assert.equal(loginCallCount, 1);
});

test("头像文件上传达到每日限制时保留服务端提示", async () => {
  await establishSession("1");
  const avatarUpdate = saveAvatar("/tmp/avatar.png");
  await flushTasks();
  const avatarInitCall = takeRequest("/app/users/me/avatar/upload-sessions");
  respond(
    avatarInitCall,
    429,
    {
      code: 100104,
      msg: "每天最多修改 3 次头像，请明天再试"
    }
  );

  await assert.rejects(
    avatarUpdate,
    (error: unknown) => isApiError(error) &&
      error.kind === "RATE_LIMIT" &&
      error.code === 100104 &&
      /每天最多修改 3 次头像/.test(error.message)
  );
});

test("小程序只接受合法的动态 HTTPS 根域名直传凭证", async () => {
  await establishSession("1");

  for (const uploadUrl of [
    "http://uploads.storage.example",
    "https://user:password@uploads.storage.example",
    "https://uploads.storage.example:443",
    "https://uploads.storage.example:8443",
    "https://uploads.storage.example/path",
    "https://uploads.storage.example?redirect=1",
    "https://uploads.storage.example#fragment",
    "https://localhost",
    "https://127.0.0.1",
    "https://bad_host.storage.example"
  ]) {
    const avatarUpdate = saveAvatar("/tmp/avatar.png");
    await flushTasks();
    respond(
      takeRequest("/app/users/me/avatar/upload-sessions"),
      200,
      {
        code: 200,
        msg: "success",
        data: {
          ...directUploadGrant("invalid-origin"),
          uploadUrl
        }
      }
    );

    await assert.rejects(
      avatarUpdate,
      (error: unknown) => isApiError(error) &&
        error.kind === "PROTOCOL" &&
        /直传凭证格式不正确/.test(error.message)
    );
    assert.equal(pendingUploads.length, 0);
  }
});

test("头像 COS 上传中止后释放会话且取消失败不覆盖原错误", async () => {
  await establishSession("1");
  const avatarUpdate = saveAvatar("/tmp/avatar.png");
  await flushTasks();
  respond(
    takeRequest("/app/users/me/avatar/upload-sessions"),
    200,
    {
      code: 200,
      msg: "success",
      data: directUploadGrant("avatar-cancel-1")
    }
  );
  await flushTasks();

  const cosUpload = takeUpload(
    "uploads.storage.example"
  );
  assert.ok(cosUpload.fail);
  cosUpload.fail({ errMsg: "uploadFile:fail abort" });
  await flushTasks();

  const cancelCall = takeRequest(
    "/app/users/me/avatar/upload-sessions/avatar-cancel-1"
  );
  assert.equal(cancelCall.method, "DELETE");
  assert.equal(cancelCall.timeout, 5_000);
  respond(cancelCall, 500, { code: 500, msg: "会话释放暂时失败" });

  await assert.rejects(avatarUpdate, (error: unknown) => {
    assert.ok(isApiError(error));
    assert.equal(error.kind, "NETWORK");
    assert.match(error.message, /图片上传失败/);
    return true;
  });
  assert.equal(pendingRequests.length, 0);
  assert.equal(pendingUploads.length, 0);
});

test("进入头像 complete 后失败不会取消已上传的 COS 会话", async () => {
  await establishSession("1");
  const avatarUpdate = saveAvatar("/tmp/avatar.png");
  await flushTasks();
  respond(
    takeRequest("/app/users/me/avatar/upload-sessions"),
    200,
    {
      code: 200,
      msg: "success",
      data: {
        ...directUploadGrant("avatar-complete-failed-1"),
        uploadUrl: "https://bucket-1250000000.cos.ap-guangzhou.myqcloud.com"
      }
    }
  );
  await flushTasks();
  respondRawUpload(takeUpload(
    "bucket-1250000000.cos.ap-guangzhou.myqcloud.com"
  ), 204);
  await flushTasks();

  const completeCall = takeRequest(
    "/app/users/me/avatar/upload-sessions/avatar-complete-failed-1/complete"
  );
  respond(completeCall, 500, { code: 500, msg: "处理失败" });

  await assert.rejects(
    avatarUpdate,
    (error: unknown) => isApiError(error) && error.kind === "SERVER"
  );
  assert.equal(pendingRequests.length, 0);
  assert.equal(pendingUploads.length, 0);
});

test("售后和客服 COS 上传失败后释放各自的直传会话", async () => {
  await establishSession("1");

  const evidenceUpload = uploadAfterSaleEvidence(42, "/tmp/evidence.png");
  await flushTasks();
  respond(
    takeRequest("/app/orders/42/after-sale-evidence/upload-sessions"),
    200,
    {
      code: 200,
      msg: "success",
      data: directUploadGrant("evidence-cancel-1")
    }
  );
  await flushTasks();
  respondRawUpload(takeUpload(
    "uploads.storage.example"
  ), 403, "<Error><Message>Access denied</Message></Error>");
  await flushTasks();

  const evidenceCancel = takeRequest(
    "/app/orders/42/after-sale-evidence/upload-sessions/evidence-cancel-1"
  );
  assert.equal(evidenceCancel.method, "DELETE");
  respond(evidenceCancel, 200, { code: 200, msg: "success" });
  await assert.rejects(evidenceUpload, (error: unknown) => {
    assert.ok(isApiError(error));
    assert.equal(error.kind, "STORAGE");
    assert.equal(error.httpStatus, 403);
    assert.equal(error.message, "Access denied");
    return true;
  });

  const chatUpload = uploadCustomerServiceImage("/tmp/chat.png");
  await flushTasks();
  respond(
    takeRequest("/app/customer-service/images/upload-sessions"),
    200,
    {
      code: 200,
      msg: "success",
      data: directUploadGrant("chat-cancel-1")
    }
  );
  await flushTasks();
  const chatCosUpload = takeUpload(
    "uploads.storage.example"
  );
  assert.ok(chatCosUpload.fail);
  chatCosUpload.fail({ errMsg: "uploadFile:fail network" });
  await flushTasks();

  const chatCancel = takeRequest(
    "/app/customer-service/images/upload-sessions/chat-cancel-1"
  );
  assert.equal(chatCancel.method, "DELETE");
  respond(chatCancel, 200, { code: 200, msg: "success" });
  await assert.rejects(
    chatUpload,
    (error: unknown) => isApiError(error) && error.kind === "NETWORK"
  );

  assert.equal(pendingRequests.length, 0);
  assert.equal(pendingUploads.length, 0);
});

test("后端明确声明直传不可用时才回退业务上传接口", async () => {
  await establishSession("1");
  const avatarUpdate = saveAvatar("/tmp/avatar.png");
  await flushTasks();
  const avatarInitCall = takeRequest("/app/users/me/avatar/upload-sessions");
  respond(avatarInitCall, 409, {
    code: 800009,
    msg: "该文件暂不支持 COS 直传，请使用兼容上传方式"
  });
  await flushTasks();

  const legacyUpload = takeUpload("/app/users/me/avatar");
  assert.equal(legacyUpload.header?.Authorization, "Bearer access-1");
  assert.equal(legacyUpload.enableHttp2, true);
  respondUpload(legacyUpload, 200, {
    code: 200,
    msg: "success",
    data: {
      userId: "1",
      nickname: "灶香集会员",
      avatarUrl: "https://oss.example.test/avatar-fallback.png",
      openidMasked: "openid****",
      phoneAuthorized: true,
      phoneNumberMasked: "138****5678",
      remainingChanges: 1
    }
  });

  const result = await avatarUpdate;
  assert.equal(result.profile.avatarUrl, "https://oss.example.test/avatar-fallback.png");
  assert.equal(pendingUploads.length, 0);
});

test("图片校验失败不会回退并重复占用业务服务器带宽", async () => {
  await establishSession("1");
  const avatarUpdate = saveAvatar("/tmp/avatar.png");
  await flushTasks();
  const avatarInitCall = takeRequest("/app/users/me/avatar/upload-sessions");
  respond(avatarInitCall, 422, {
    code: 800002,
    msg: "图片格式或尺寸不符合要求"
  });

  await assert.rejects(
    avatarUpdate,
    (error: unknown) => isApiError(error) && error.code === 800002
  );
  assert.equal(pendingUploads.length, 0);
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

test("售后凭证上传在 401 后刷新会话并只重试一次", async () => {
  await establishSession("1");
  const upload = uploadAuthenticatedFile<{ id: number }>({
    url: "/app/orders/101/after-sale-evidence",
    filePath: "/tmp/evidence.png"
  });
  await flushTasks();

  const firstUpload = takeUpload("/app/orders/101/after-sale-evidence");
  assert.equal(firstUpload.header?.Authorization, "Bearer access-1");
  respondUpload(firstUpload, 401, { code: 100001, msg: "unauthorized" });
  await flushTasks();

  const refreshCall = takeRequest("/app/auth/refresh");
  respond(refreshCall, 200, {
    code: 200,
    msg: "success",
    data: sessionResponse("2")
  });
  await flushTasks();

  const retryUpload = takeUpload("/app/orders/101/after-sale-evidence");
  assert.equal(retryUpload.header?.Authorization, "Bearer access-2");
  respondUpload(retryUpload, 200, {
    code: 200,
    msg: "success",
    data: { id: 801 }
  });

  assert.deepEqual(await upload, { id: 801 });
  assert.equal(pendingUploads.length, 0);
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
  assert.equal(publicCall.enableHttp2, true);
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
