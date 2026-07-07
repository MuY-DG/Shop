import type {
  PageResult,
  ProductCategory,
  ProductDetail,
  ProductListItem
} from "../types/api";
import { request } from "../utils/request";

export function getProductCategories(): Promise<ProductCategory[]> {
  return request<ProductCategory[]>({
    url: "/app/product/categories",
    auth: false
  });
}

export function getProductList(params: {
  current: number;
  size: number;
  categoryId?: number;
  keyword?: string;
}): Promise<PageResult<ProductListItem>> {
  const query = [
    `current=${params.current}`,
    `size=${params.size}`,
    params.categoryId ? `categoryId=${params.categoryId}` : "",
    params.keyword ? `keyword=${encodeURIComponent(params.keyword)}` : ""
  ].filter(Boolean).join("&");

  return request<PageResult<ProductListItem>>({
    url: `/app/product/spus?${query}`,
    auth: false
  });
}

export function getProductDetail(spuId: number): Promise<ProductDetail> {
  return request<ProductDetail>({
    url: `/app/product/spus/${spuId}`,
    auth: false
  });
}

export function formatPrice(priceCent: number): string {
  return `¥${(priceCent / 100).toFixed(2)}`;
}
