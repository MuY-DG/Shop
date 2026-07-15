import type { AppUserProfile } from "../types/api";
import { request } from "../utils/request";
import {
  clearSession,
  ensureSession,
  getSessionState,
  logoutSession,
  restoreSession,
  silentLogin as runSilentLogin,
  updateProfile
} from "./session";

export async function ensureAppLogin(): Promise<void> {
  await ensureSession();
}

export const silentLogin = runSilentLogin;
export const clearAppTokens = clearSession;
export { getSessionState, restoreSession, updateProfile };

export function getCurrentUser(): Promise<AppUserProfile> {
  return request<AppUserProfile>({
    url: "/app/users/me",
    method: "GET"
  });
}

export function updateCurrentUserProfile(nickname: string): Promise<AppUserProfile> {
  return request<AppUserProfile>({
    url: "/app/users/me",
    method: "PUT",
    data: { nickname }
  });
}

export function authorizePhone(code: string): Promise<AppUserProfile> {
  return request<AppUserProfile>({
    url: "/app/auth/phone",
    method: "POST",
    data: { code }
  });
}

export const logout = logoutSession;
