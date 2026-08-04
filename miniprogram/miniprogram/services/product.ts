import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  ProductCategory,
  ProductDetail,
  ProductFilterGroup,
  ProductListQuery,
  ProductListResult
} from "../types/product";
import type {
  ProductReview,
  ProductReviewCreateRequest,
  ProductReviewEligibility,
  ProductReviewPage,
  ProductReviewImageUploadResponse,
  ProductReviewUpdateRequest
} from "../types/product-engagement";
import type { PageResult } from "../types/api";
import { request } from "../utils/request";
import { uploadFileDirect } from "../utils/direct-upload";
import { uploadFile } from "../utils/upload";

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
  if (query.sort) {
    data.sort = query.sort;
  }
  const parameterFilters = Object.entries(query.parameterFilters ?? {})
    .filter(([parameterCode, optionCode]) =>
      /^[A-Za-z0-9_-]{1,64}$/.test(parameterCode) &&
      /^[A-Za-z0-9_-]{1,64}$/.test(optionCode)
    )
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([parameterCode, optionCode]) =>
      `${parameterCode.toUpperCase()}:${optionCode.toUpperCase()}`
    );
  if (parameterFilters.length) {
    data.parameterFilters = parameterFilters.join(",");
  }
  return request<ProductListResult>({
    url: API_ENDPOINTS.product.list,
    method: "GET",
    data,
    auth: false
  });
}

export function getProductFilterFacets(query: {
  categoryId?: number;
  keyword?: string;
}): Promise<ProductFilterGroup[]> {
  const data: WechatMiniprogram.IAnyObject = {};
  if (query.categoryId !== undefined) {
    data.categoryId = query.categoryId;
  }
  if (query.keyword) {
    data.keyword = query.keyword;
  }
  return request<ProductFilterGroup[]>({
    url: API_ENDPOINTS.product.filterFacets,
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

export function getProductReviews(
  spuId: number,
  current = 1,
  size = 10
): Promise<ProductReviewPage> {
  return request<ProductReviewPage>({
    url: API_ENDPOINTS.product.reviews(spuId),
    method: "GET",
    data: { current, size },
    auth: false
  });
}

export function getProductReviewEligibility(
  spuId: number
): Promise<ProductReviewEligibility> {
  return request<ProductReviewEligibility>({
    url: API_ENDPOINTS.product.reviewEligibility(spuId),
    method: "GET"
  });
}

export function createProductReview(
  spuId: number,
  data: ProductReviewCreateRequest
): Promise<ProductReview> {
  return request<ProductReview, ProductReviewCreateRequest>({
    url: API_ENDPOINTS.product.reviews(spuId),
    method: "POST",
    data
  });
}

export function uploadProductReviewImage(
  orderItemId: number,
  filePath: string
): Promise<ProductReviewImageUploadResponse> {
  return uploadFileDirect<ProductReviewImageUploadResponse>({
    initUrl: API_ENDPOINTS.product.reviewImageUploads(orderItemId),
    filePath,
    timeoutMs: 60_000,
    legacyFallback: () => uploadFile<ProductReviewImageUploadResponse>({
      url: API_ENDPOINTS.product.reviewImage(orderItemId),
      filePath,
      name: "file",
      timeoutMs: 30_000
    })
  });
}

export function updateProductReview(
  reviewId: number,
  data: ProductReviewUpdateRequest
): Promise<ProductReview> {
  return request<ProductReview, ProductReviewUpdateRequest>({
    url: API_ENDPOINTS.product.review(reviewId),
    method: "PUT",
    data
  });
}

export function deleteProductReview(reviewId: number): Promise<void> {
  return request<void>({
    url: API_ENDPOINTS.product.review(reviewId),
    method: "DELETE",
    expectData: false
  });
}

export function getMyProductReviews(
  current = 1,
  size = 10
): Promise<PageResult<ProductReview>> {
  return request<PageResult<ProductReview>>({
    url: API_ENDPOINTS.product.myReviews,
    method: "GET",
    data: { current, size }
  });
}
