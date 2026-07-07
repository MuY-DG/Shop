export interface ApiResponse<T> {
  code: number;
  msg: string;
  data: T;
}

export interface AppUserSummary {
  userId: number;
  openidMasked: string;
  phoneAuthorized: boolean;
}

export interface AppLoginResponse {
  token: string;
  refreshToken: string;
  expiresIn: number;
  user: AppUserSummary;
}

export interface PhoneAuthorizeResponse {
  phoneAuthorized: boolean;
  phoneNumberMasked: string;
}

export type RequestBody = string | WechatMiniprogram.IAnyObject | ArrayBuffer;

export interface RequestOptions<TBody extends RequestBody = WechatMiniprogram.IAnyObject> {
  url: string;
  method?: "GET" | "POST" | "PUT" | "DELETE";
  data?: TBody;
  auth?: boolean;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

export interface ProductCategory {
  id: number;
  parentId: number;
  name: string;
  icon: string;
  sortOrder: number;
  status: "ENABLED" | "DISABLED";
}

export interface ProductListItem {
  id: number;
  categoryId: number;
  title: string;
  subtitle: string;
  mainImage: string;
  sellingPoints: string[];
  minPriceCent: number;
  maxPriceCent: number;
  totalStock: number;
}

export interface ProductImage {
  id: number;
  url: string;
  sortOrder: number;
}

export interface ProductSku {
  id: number;
  skuCode: string;
  specJson: string;
  specText: string;
  priceCent: number;
  originalPriceCent: number;
  stockAvailable: number;
  weightGram: number;
  image: string;
  status: "ENABLED" | "DISABLED";
}

export interface ProductDetail {
  id: number;
  categoryId: number;
  categoryName: string;
  title: string;
  subtitle: string;
  mainImage: string;
  sellingPoints: string[];
  detailHtml: string;
  images: ProductImage[];
  skus: ProductSku[];
}
