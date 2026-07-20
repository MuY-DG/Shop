export type ApiErrorKind =
  | "NETWORK"
  | "PROTOCOL"
  | "AUTH"
  | "RATE_LIMIT"
  | "SERVER"
  | "API"
  | "STORAGE";

export interface ApiErrorOptions {
  kind: ApiErrorKind;
  message: string;
  httpStatus?: number;
  code?: number;
  retryAfterSeconds?: number;
  cause?: unknown;
}

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly httpStatus?: number;
  readonly code?: number;
  readonly retryAfterSeconds?: number;
  readonly cause?: unknown;

  constructor(options: ApiErrorOptions) {
    super(options.message);
    this.name = "ApiError";
    this.kind = options.kind;
    this.httpStatus = options.httpStatus;
    this.code = options.code;
    this.retryAfterSeconds = options.retryAfterSeconds;
    this.cause = options.cause;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}
