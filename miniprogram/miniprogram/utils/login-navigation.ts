import { buildLoginUrl, LOGIN_ROUTE } from "../features/login";

interface PageRouteSnapshot {
  route?: string;
  options?: Record<string, unknown>;
}

let navigationPending = false;

function currentPage(): PageRouteSnapshot | undefined {
  if (typeof getCurrentPages !== "function") {
    return undefined;
  }
  const pages = getCurrentPages() as unknown as PageRouteSnapshot[];
  return pages[pages.length - 1];
}

function currentPageUrl(page: PageRouteSnapshot | undefined): string {
  const route = page?.route?.trim();
  if (!route) {
    return "";
  }
  const path = route.startsWith("/") ? route : `/${route}`;
  const query = Object.entries(page?.options ?? {})
    .filter(([, value]) => typeof value === "string" || typeof value === "number")
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join("&");
  return query ? `${path}?${query}` : path;
}

export function openLoginPage(redirect?: string): boolean {
  const page = currentPage();
  const currentRoute = page?.route ? `/${page.route.replace(/^\//, "")}` : "";
  if (currentRoute === LOGIN_ROUTE || navigationPending) {
    return false;
  }

  navigationPending = true;
  wx.navigateTo({
    url: buildLoginUrl(redirect || currentPageUrl(page)),
    complete: () => {
      setTimeout(() => {
        navigationPending = false;
      }, 300);
    }
  });
  return true;
}
