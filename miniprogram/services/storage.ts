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

function getUploadUrl(data: Record<string, unknown>): string | null {
  const url = typeof data.url === "string" && data.url.trim() ? data.url.trim() : "";
  if (url) {
    return url;
  }

  const publicUrl = typeof data.publicUrl === "string" && data.publicUrl.trim() ? data.publicUrl.trim() : "";
  return publicUrl || null;
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
  const uploadUrl = getUploadUrl(data);

  return (
    typeof id === "number" &&
    Number.isFinite(id) &&
    typeof responsePurpose === "string" &&
    responsePurpose === purpose &&
    typeof visibility === "string" &&
    visibility.trim().length > 0 &&
    uploadUrl !== null
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
