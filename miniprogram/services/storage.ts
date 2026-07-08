import type { ApiResponse, EvidenceUploadPurpose, StorageFileUploadResponse } from "../types/api";

const SUCCESS_CODE = 200;

function getUploadApp() {
  return getApp<{
    globalData: {
      apiBaseUrl: string;
      token: string;
    };
  }>();
}

function parseUploadEnvelope(rawData: string): ApiResponse<StorageFileUploadResponse> {
  let parsed: unknown;

  try {
    parsed = JSON.parse(rawData);
  } catch (error) {
    throw new Error("上传响应格式错误");
  }

  if (!parsed || typeof parsed !== "object") {
    throw new Error("上传响应格式错误");
  }

  return parsed as ApiResponse<StorageFileUploadResponse>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isStorageFileUploadResponse(
  data: unknown,
  purpose: EvidenceUploadPurpose
): data is StorageFileUploadResponse {
  if (!isRecord(data)) {
    return false;
  }

  const id = data.id;
  const responsePurpose = data.purpose;
  const visibility = data.visibility;

  return (
    typeof id === "number" &&
    Number.isFinite(id) &&
    responsePurpose === purpose &&
    isNonEmptyString(visibility) &&
    isNonEmptyString(data.provider) &&
    isNonEmptyString(data.originalFilename) &&
    isNonEmptyString(data.contentType) &&
    isNonEmptyString(data.extension) &&
    isNonEmptyString(data.status) &&
    isNonEmptyString(data.createdAt) &&
    isNonEmptyString(data.updatedAt) &&
    typeof data.sizeBytes === "number" &&
    Number.isFinite(data.sizeBytes) &&
    (!("url" in data) || typeof data.url === "string" || data.url === null) &&
    (!("publicUrl" in data) || typeof data.publicUrl === "string" || data.publicUrl === null)
  );
}

export function uploadEvidenceFile(
  filePath: string,
  purpose: EvidenceUploadPurpose
): Promise<StorageFileUploadResponse> {
  const app = getUploadApp();
  const header: Record<string, string> = {};

  if (app.globalData.token) {
    header.Authorization = `Bearer ${app.globalData.token}`;
  }

  return new Promise<StorageFileUploadResponse>((resolve, reject) => {
    wx.uploadFile({
      url: `${app.globalData.apiBaseUrl}/app/files/upload`,
      filePath,
      name: "file",
      formData: { purpose },
      header,
      success: (response) => {
        if (typeof response.data !== "string") {
          reject(new Error("上传响应格式错误"));
          return;
        }

        let body: StorageUploadEnvelope;
        try {
          body = parseUploadEnvelope(response.data);
        } catch (error) {
          reject(error instanceof Error ? error : new Error("上传响应格式错误"));
          return;
        }

        if (response.statusCode < 200 || response.statusCode >= 300) {
          reject(new Error(body.msg || "上传失败"));
          return;
        }

        if (body.code !== SUCCESS_CODE || !body.data) {
          reject(new Error(body.msg || "上传失败"));
          return;
        }

        if (!isStorageFileUploadResponse(body.data, purpose)) {
          reject(new Error("上传响应格式错误"));
          return;
        }

        resolve(body.data);
      },
      fail: (error) => {
        reject(new Error(error.errMsg));
      }
    });
  });
}

export type StorageUploadEnvelope = ApiResponse<StorageFileUploadResponse>;
