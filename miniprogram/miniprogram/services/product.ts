import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  ProductCategory,
  ProductDetail,
  ProductListQuery,
  ProductListResult
} from "../types/product";
import { request } from "../utils/request";

export function getProductCategories(): Promise<ProductCategory[]> {
  return request<ProductCategory[]>({
    url: API_ENDPOINTS.product.categories,
    method: "GET",
    auth: false
  });
}

export function getProductList(query: ProductListQuery): Promise<ProductListResult> {
  const data: WechatMiniprogram.IAnyObject = {
    current: query.current,
    size: query.size
  };
  if (query.categoryId !== undefined) {
    data.categoryId = query.categoryId;
  }
  if (query.keyword) {
    data.keyword = query.keyword;
  }
  return request<ProductListResult>({
    url: API_ENDPOINTS.product.list,
    method: "GET",
    data,
    auth: false
  });
}

export function getProductDetail(spuId: number): Promise<ProductDetail> {
  return request<ProductDetail>({
    url: API_ENDPOINTS.product.detail(spuId),
    method: "GET",
    auth: false
  });
}
