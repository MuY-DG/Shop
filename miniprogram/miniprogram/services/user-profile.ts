import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AppUserAvatarUpdateResponse,
  AppUserProfile,
  UpdateAppUserAvatarRequest,
  UpdateAppUserProfileRequest
} from "../types/auth";
import { request } from "../utils/request";
import { uploadFile } from "../utils/upload";
import { updateSessionUser } from "./session";

export interface SavedAvatar {
  profile: AppUserProfile;
  remainingChanges?: number;
}

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

export async function saveAvatar(
  selectedAvatarUrl: string
): Promise<SavedAvatar> {
  const avatarUrl = selectedAvatarUrl.trim();
  if (/^https:\/\//i.test(avatarUrl)) {
    const response = await request<
      AppUserAvatarUpdateResponse,
      UpdateAppUserAvatarRequest
    >({
      url: API_ENDPOINTS.user.avatar,
      method: "PUT",
      data: { avatarUrl }
    });
    const { remainingChanges, ...profile } = response;
    return {
      profile: updateSessionUser(profile),
      remainingChanges
    };
  }

  const response = await uploadFile<AppUserAvatarUpdateResponse>({
    url: API_ENDPOINTS.user.avatar,
    filePath: avatarUrl,
    name: "file",
    timeoutMs: 30_000
  });
  const { remainingChanges, ...profile } = response;
  return {
    profile: updateSessionUser(profile),
    remainingChanges
  };
}
