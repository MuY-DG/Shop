import type {
  ApiResponse,
  EvidenceUploadPurpose,
  StorageFileUploadResponse
} from "../types/api";
import type { RawHttpResult } from "../utils/http";
import { withAuthRecovery } from "../utils/request";
import {
  sessionManager,
  type SessionManager
} from "./session";

const SUCCESS_CODE = 200;

function getApiBaseUrl(): string {
  return getApp<{
    globalData: {
      apiBaseUrl: string;
    };
  }>().globalData.apiBaseUrl;
}

function parseUploadEnvelope(
  rawData: unknown
): ApiResponse<StorageFileUploadResponse> | null {
  if (typeof rawData !== "string") {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(rawData);
    if (!parsed || typeof parsed !== "object") {
      return null;
    }
    const candidate = parsed as Partial<ApiResponse<StorageFileUploadResponse>>;
    if (
      typeof candidate.code !== "number" ||
      typeof candidate.msg !== "string"
    ) {
      return null;
    }
    return {
      code: candidate.code,
      msg: candidate.msg,
      data: ("data" in candidate ? candidate.data : null) as StorageFileUploadResponse
    };
  } catch {
    return null;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function isPositiveFiniteNumber(value: unknown): value is number {
  return isFiniteNumber(value) && value > 0;
}

function isOptionalPositiveFiniteNumber(value: unknown): boolean {
  return value === undefined || value === null || isPositiveFiniteNumber(value);
}

function isOptionalPositiveStringId(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    (typeof value === "string" && /^[1-9]\d*$/.test(value))
  );
}

function isStorageFileUploadResponse(
  data: unknown,
  purpose: EvidenceUploadPurpose
): data is StorageFileUploadResponse {
  if (!isRecord(data)) {
    return false;
  }
  return (
    isPositiveFiniteNumber(data.id) &&
    data.purpose === purpose &&
    data.visibility === "PRIVATE" &&
    data.uploadedByType === "APP" &&
    isNonEmptyString(data.provider) &&
    isNonEmptyString(data.originalFilename) &&
    isNonEmptyString(data.contentType) &&
    isNonEmptyString(data.extension) &&
    data.status === "ACTIVE" &&
    isNonEmptyString(data.createdAt) &&
    isNonEmptyString(data.updatedAt) &&
    isPositiveFiniteNumber(data.sizeBytes) &&
    isOptionalPositiveStringId(data.uploadedById) &&
    isOptionalPositiveFiniteNumber(data.assetCategoryId) &&
    isOptionalPositiveFiniteNumber(data.width) &&
    isOptionalPositiveFiniteNumber(data.height) &&
    (!("url" in data) || typeof data.url === "string" || data.url === null) &&
    (!("publicUrl" in data) ||
      typeof data.publicUrl === "string" ||
      data.publicUrl === null)
  );
}

export interface EvidenceUploaderDependencies {
  session: SessionManager;
  upload(
    filePath: string,
    purpose: EvidenceUploadPurpose,
    authToken: string | null
  ): Promise<RawHttpResult<StorageFileUploadResponse>>;
}

export function createEvidenceUploader(
  dependencies: EvidenceUploaderDependencies
): (
  filePath: string,
  purpose: EvidenceUploadPurpose
) => Promise<StorageFileUploadResponse> {
  return async (filePath, purpose) => {
    const result = await withAuthRecovery(
      (authToken) => dependencies.upload(filePath, purpose, authToken),
      {},
      dependencies.session
    );
    if (result.statusCode < 200 || result.statusCode >= 300) {
      throw new Error(result.body?.msg || "上传失败");
    }
    if (result.body?.code !== SUCCESS_CODE || !result.body.data) {
      throw new Error(result.body?.msg || "上传响应格式错误");
    }
    if (!isStorageFileUploadResponse(result.body.data, purpose)) {
      throw new Error("上传响应格式错误");
    }
    return result.body.data;
  };
}

function rawUploadEvidenceFile(
  filePath: string,
  purpose: EvidenceUploadPurpose,
  authToken: string | null
): Promise<RawHttpResult<StorageFileUploadResponse>> {
  const authTokenUsed = authToken || null;
  const header: Record<string, string> = {};
  if (authTokenUsed) {
    header.Authorization = `Bearer ${authTokenUsed}`;
  }

  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${getApiBaseUrl()}/app/files/upload`,
      filePath,
      name: "file",
      formData: { purpose },
      header,
      success: (response) => {
        resolve({
          statusCode: response.statusCode,
          body: parseUploadEnvelope(response.data),
          authTokenUsed
        });
      },
      fail: (error) => reject(new Error(error.errMsg))
    });
  });
}

export const uploadEvidenceFile = createEvidenceUploader({
  session: sessionManager,
  upload: rawUploadEvidenceFile
});

export type StorageUploadEnvelope = ApiResponse<StorageFileUploadResponse>;
