import type { HomeBanner } from "../types/api";
import { request } from "../utils/request";

export function getHomeBanners(): Promise<HomeBanner[]> {
  return request<HomeBanner[]>({
    url: "/app/home/banners",
    auth: false
  });
}
