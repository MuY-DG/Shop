import type { AppUserProfile } from "../types/auth";

export const LOGIN_ROUTE = "/pages/auth/login/login";

const TAB_ROUTES = new Set([
  "/pages/index/index",
  "/pages/category/category",
  "/pages/cart/cart",
  "/pages/profile/profile"
]);

export function sanitizeLoginRedirect(value: unknown): string {
  if (typeof value !== "string") {
    return "";
  }
  let candidate = value.trim();
  try {
    candidate = decodeURIComponent(candidate);
  } catch {
    return "";
  }
  if (
    !candidate.startsWith("/pages/") ||
    candidate.startsWith("//") ||
    candidate.split("?")[0] === LOGIN_ROUTE
  ) {
    return "";
  }
  return candidate;
}

export function buildLoginUrl(redirect?: string): string {
  const safeRedirect = sanitizeLoginRedirect(redirect);
  return safeRedirect
    ? `${LOGIN_ROUTE}?redirect=${encodeURIComponent(safeRedirect)}`
    : LOGIN_ROUTE;
}

export function isTabRoute(url: string): boolean {
  return TAB_ROUTES.has(url.split("?")[0]);
}

export function needsPhoneAuthorization(user?: AppUserProfile): boolean {
  return Boolean(user && !user.phoneAuthorized);
}
