export interface ApiResponse<T> {
  code: number;
  msg: string;
  // 后端启用 NON_NULL，失败或 void 响应中 data 字段会被省略。
  data?: T;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";

export type RequestBody =
  | string
  | ArrayBuffer
  | WechatMiniprogram.IAnyObject;

export interface RequestOptions<
  TBody extends RequestBody = WechatMiniprogram.IAnyObject
> {
  url: string;
  method?: HttpMethod;
  data?: TBody;
  headers?: Record<string, string>;
  auth?: boolean;
  recoverAuth?: boolean;
  expectData?: boolean;
  timeoutMs?: number;
}
