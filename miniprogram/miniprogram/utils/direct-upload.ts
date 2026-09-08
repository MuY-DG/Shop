import { ApiError, isApiError } from "./api-error";
import { request } from "./request";

export interface DirectUploadGrant {
  uploadId: string;
  uploadUrl: string;
  formData: Record<string, string>;
  expiresAt: string;
}

export interface DirectUploadInitRequest extends WechatMiniprogram.IAnyObject {
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
}

export interface DirectUploadOptions<T> {
  initUrl: string;
  filePath: string;
  mediaType?: 'image' | 'video';
  completeUrl?: (uploadId: string) => string;
  timeoutMs?: number;
  completeTimeoutMs?: number;
  legacyFallback?: (fallbackFilePath: string) => Promise<T>;
}

interface LocalUploadFile {
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
}

const DIRECT_UPLOAD_FALLBACK_CODES = new Set([
  800009
]);
const DIRECT_UPLOAD_FALLBACK_STATUSES = new Set([
  404,
  405,
  501
]);
const DIRECT_UPLOAD_PROCESSING_FAILED_CODE = 800007;
const DIRECT_UPLOAD_PROCESSING_RETRY_DELAYS_MS = [5_500, 10_500] as const;
const DIRECT_UPLOAD_CANCEL_TIMEOUT_MS = 5_000;
const HTTPS_ROOT_HOSTNAME =
  /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/i;

const IMAGE_CONTENT_TYPES: Record<string, string> = {
  avif: "image/avif",
  bmp: "image/bmp",
  gif: "image/gif",
  heic: "image/heic",
  heif: "image/heif",
  jpeg: "image/jpeg",
  jpg: "image/jpeg",
  png: "image/png",
  tif: "image/tiff",
  tiff: "image/tiff",
  webp: "image/webp"
};
const VIDEO_CONTENT_TYPES: Record<string, string> = { mp4: 'video/mp4', webm: 'video/webm' };
let fallbackFileSequence = 0;

function cleanPath(path: string): string {
  return path.trim().split(/[?#]/, 1)[0] || "";
}

function isHttpsRootOrigin(value: string): boolean {
  const candidate = value.trim();
  const authority = /^https:\/\/([^/?#]+)\/?$/i.exec(candidate)?.[1] || "";
  return HTTPS_ROOT_HOSTNAME.test(authority);
}

function pathFilename(path: string): string {
  const clean = cleanPath(path);
  const filename = clean.slice(clean.lastIndexOf("/") + 1).trim();
  return filename && filename !== "." && filename !== ".."
    ? filename
    : "image";
}

function filenameExtension(filename: string): string {
  const match = /\.([a-z0-9]{1,8})$/i.exec(filename);
  return match?.[1]?.toLowerCase() || "";
}

function getFileSize(filePath: string): Promise<number> {
  return new Promise((resolve, reject) => {
    wx.getFileSystemManager().getFileInfo({
      filePath,
      success: (result) => {
        const size = Number(result.size);
        if (!Number.isSafeInteger(size) || size <= 0) {
          reject(new ApiError({
            kind: "PROTOCOL",
            message: "待上传图片为空或已失效"
          }));
          return;
        }
        resolve(size);
      },
      fail: (cause) => reject(new ApiError({
        kind: "PROTOCOL",
        message: "无法读取待上传图片",
        cause
      }))
    });
  });
}

function getImageType(filePath: string): Promise<string> {
  return new Promise((resolve) => {
    wx.getImageInfo({
      src: filePath,
      success: (result) => resolve(String(result.type || "").toLowerCase()),
      fail: () => resolve("")
    });
  });
}

async function localUploadFile(filePath: string, mediaType: 'image' | 'video' = 'image'): Promise<LocalUploadFile> {
  const normalizedPath = filePath.trim();
  if (!normalizedPath) {
    throw new ApiError({ kind: "PROTOCOL", message: "待上传图片不存在" });
  }
  let originalFilename = pathFilename(normalizedPath);
  const pathExtension = filenameExtension(originalFilename);
  if (mediaType === 'video') {
    const sizeBytes = await getFileSize(normalizedPath);
    const detectedType = VIDEO_CONTENT_TYPES[pathExtension] ? pathExtension : await new Promise<string>((resolve) => {
      wx.getVideoInfo({ src: normalizedPath,
        success: (result) => resolve(String(result.type || '').toLowerCase()), fail: () => resolve('') });
    });
    const contentType = VIDEO_CONTENT_TYPES[detectedType];
    if (!contentType) throw new ApiError({ kind: 'PROTOCOL', message: '请选择 MP4 或 WebM 格式的视频' });
    if (!VIDEO_CONTENT_TYPES[pathExtension]) {
      originalFilename = `${originalFilename.replace(/\.[a-z0-9]{1,8}$/i, '')}.${detectedType}`;
    }
    return { originalFilename, contentType, sizeBytes };
  }
  const [sizeBytes, detectedType] = await Promise.all([
    getFileSize(normalizedPath),
    IMAGE_CONTENT_TYPES[pathExtension]
      ? Promise.resolve("")
      : getImageType(normalizedPath)
  ]);
  const extension = IMAGE_CONTENT_TYPES[pathExtension]
    ? pathExtension
    : detectedType;
  const contentType = IMAGE_CONTENT_TYPES[extension] || "application/octet-stream";
  if (!IMAGE_CONTENT_TYPES[pathExtension] && IMAGE_CONTENT_TYPES[extension]) {
    const safeExtension = extension === "jpeg" ? "jpg" : extension;
    originalFilename = `${originalFilename.replace(/\.[a-z0-9]{1,8}$/i, '')}.${safeExtension}`;
  }
  return { originalFilename, contentType, sizeBytes };
}

async function uploadWithLegacyFilename<T>(
  options: DirectUploadOptions<T>,
  file: LocalUploadFile,
  fallback: (filePath: string) => Promise<T>
): Promise<T> {
  const sourcePath = options.filePath.trim();
  if (pathFilename(sourcePath) === file.originalFilename) {
    return fallback(sourcePath);
  }

  // wx.uploadFile 从本地路径生成 multipart 文件名；仅修正初始化元数据不足以支持兼容上传。
  const directory = wx.env.USER_DATA_PATH.replace(/\/$/, '');
  const extension = filenameExtension(file.originalFilename);
  let copiedPath: string;
  do {
    copiedPath = `${directory}/upload-fallback-${Date.now()}-${++fallbackFileSequence}.${extension}`;
  } while (copiedPath === sourcePath);
  const fs = wx.getFileSystemManager();
  try {
    await new Promise<void>((resolve, reject) => {
      fs.copyFile({ srcPath: sourcePath, destPath: copiedPath,
        success: () => resolve(),
        fail: (cause) => reject(new ApiError({
          kind: 'PROTOCOL', message: '无法准备兼容上传文件，请重试', cause
        })) });
    });
    return await fallback(copiedPath);
  } finally {
    // 只清理本次创建的副本；上传失败时也保留用户原文件供预览或重试。
    await new Promise<void>((resolve) => {
      fs.unlink({ filePath: copiedPath, success: () => resolve(), fail: () => resolve() });
    });
  }
}

function requireGrant(value: DirectUploadGrant): DirectUploadGrant {
  if (
    !value ||
    typeof value.uploadId !== "string" ||
    !value.uploadId.trim() ||
    typeof value.uploadUrl !== "string" ||
    !isHttpsRootOrigin(value.uploadUrl) ||
    !value.formData ||
    typeof value.formData !== "object" ||
    Array.isArray(value.formData)
  ) {
    throw new ApiError({
      kind: "PROTOCOL",
      message: "图片直传凭证格式不正确"
    });
  }
  return {
    uploadId: value.uploadId.trim(),
    uploadUrl: value.uploadUrl.trim(),
    formData: value.formData,
    expiresAt: value.expiresAt
  };
}

function cosErrorMessage(body: string): string {
  const match = /<Message>([^<]+)<\/Message>/i.exec(body);
  return match?.[1]?.trim() || "图片上传到云存储失败，请稍后重试";
}

function uploadToCos(
  grant: DirectUploadGrant,
  filePath: string,
  timeoutMs: number
): Promise<void> {
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: grant.uploadUrl,
      filePath,
      name: "file",
      // COS 表单由业务服务端签名。这里故意不设置 Authorization，
      // 避免把小程序登录凭证泄露给云存储域名。
      formData: grant.formData,
      timeout: timeoutMs,
      enableHttp2: true,
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve();
          return;
        }
        reject(new ApiError({
          kind: "STORAGE",
          message: cosErrorMessage(response.data),
          httpStatus: response.statusCode
        }));
      },
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

async function cancelUploadSession(url: string): Promise<void> {
  try {
    await request<void>({
      url,
      method: "DELETE",
      expectData: false,
      timeoutMs: DIRECT_UPLOAD_CANCEL_TIMEOUT_MS
    });
  } catch {
    // COS 上传错误才是用户需要看到的根因。会话释放是 best-effort，
    // 失败时由服务端过期清理兜底，不能覆盖原始上传错误。
  }
}

export function shouldFallbackToLegacyUpload(error: unknown): boolean {
  return isApiError(error) && (
    (error.code !== undefined && DIRECT_UPLOAD_FALLBACK_CODES.has(error.code)) ||
    (
      error.httpStatus !== undefined &&
      DIRECT_UPLOAD_FALLBACK_STATUSES.has(error.httpStatus)
    )
  );
}

export async function uploadFileDirect<T>(
  options: DirectUploadOptions<T>
): Promise<T> {
  const file = await localUploadFile(options.filePath, options.mediaType);
  let grant: DirectUploadGrant;
  try {
    grant = requireGrant(await request<
      DirectUploadGrant,
      DirectUploadInitRequest
    >({
      url: options.initUrl,
      method: "POST",
      data: file
    }));
  } catch (error) {
    if (options.legacyFallback && shouldFallbackToLegacyUpload(error)) {
      return uploadWithLegacyFilename(options, file, options.legacyFallback);
    }
    throw error;
  }

  const sessionUrl = [
    options.initUrl.replace(/\/$/, ""),
    encodeURIComponent(grant.uploadId)
  ].join("/");
  try {
    await uploadToCos(grant, options.filePath, options.timeoutMs ?? 60_000);
  } catch (error) {
    await cancelUploadSession(sessionUrl);
    throw error;
  }
  const completeUrl = options.completeUrl
    ? options.completeUrl(grant.uploadId)
    : `${sessionUrl}/complete`;
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await request<T>({
        url: completeUrl,
        method: "POST",
        timeoutMs: options.completeTimeoutMs ?? 180_000
      });
    } catch (error) {
      const retryDelay = DIRECT_UPLOAD_PROCESSING_RETRY_DELAYS_MS[attempt];
      if (
        !isApiError(error) ||
        error.code !== DIRECT_UPLOAD_PROCESSING_FAILED_CODE ||
        retryDelay === undefined
      ) {
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, retryDelay));
    }
  }
}
