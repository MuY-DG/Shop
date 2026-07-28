import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AppUserProfile,
  UpdateAppUserAvatarRequest,
  UpdateAppUserProfileRequest
} from "../types/auth";
import { request } from "../utils/request";
import { uploadFile } from "../utils/upload";
import { updateSessionUser } from "./session";

export async function getMyProfile(): Promise<AppUserProfile> {
  const profile = await request<AppUserProfile>({
    url: API_ENDPOINTS.user.me,
    method: "GET"
  });
  return updateSessionUser(profile);
}

export async function updateMyProfile(
  nickname: string
): Promise<AppUserProfile> {
  const profile = await request<AppUserProfile, UpdateAppUserProfileRequest>({
    url: API_ENDPOINTS.user.me,
    method: "PUT",
    data: { nickname }
  });
  return updateSessionUser(profile);
}

function isRemoteWechatAvatarUrl(avatarUrl: string): boolean {
  return /^https:\/\//i.test(avatarUrl);
}

export async function saveWechatAvatar(
  selectedAvatarUrl: string
): Promise<AppUserProfile> {
  const avatarUrl = selectedAvatarUrl.trim();
  if (isRemoteWechatAvatarUrl(avatarUrl)) {
    const profile = await request<AppUserProfile, UpdateAppUserAvatarRequest>({
      url: API_ENDPOINTS.user.avatar,
      method: "PUT",
      data: { avatarUrl }
    });
    return updateSessionUser(profile);
  }

  const profile = await uploadFile<AppUserProfile>({
    url: API_ENDPOINTS.user.avatar,
    filePath: avatarUrl,
    name: "file",
    timeoutMs: 30_000
  });
  return updateSessionUser(profile);
}
