import type {
  AddressResponse,
  CheckoutQuery,
  OrderPreviewRequest,
  OrderSubmitRequest,
  ProductSku
} from "../types/api";

const PREVIEW_PATH = "/pages/order/preview/preview";

type QueryInput = string | Record<string, string | undefined>;

export type ProductCommand =
  | { type: "ERROR"; message: string }
  | { type: "ADD_TO_CART"; payload: { skuId: number; quantity: number } }
  | { type: "DIRECT_BUY"; url: string };

export interface LatestRequestTracker {
  begin(): number;
  isLatest(token: number): boolean;
}

function invalidCheckoutQuery(): never {
  throw new Error("结算参数无效");
}

function decodePart(value: string): string {
  try {
    return decodeURIComponent(value.replace(/\+/g, " "));
  } catch {
    return invalidCheckoutQuery();
  }
}

function parseQueryString(input: string): Record<string, string | undefined> {
  const queryStart = input.indexOf("?");
  const rawQuery = (queryStart >= 0 ? input.slice(queryStart + 1) : input)
    .split("#", 1)[0];
  const result = Object.create(null) as Record<string, string | undefined>;
  if (!rawQuery) {
    return result;
  }
  rawQuery.split("&").forEach((part) => {
    if (!part) {
      return;
    }
    const separator = part.indexOf("=");
    const rawKey = separator >= 0 ? part.slice(0, separator) : part;
    const rawValue = separator >= 0 ? part.slice(separator + 1) : "";
    const key = decodePart(rawKey);
    if (Object.prototype.hasOwnProperty.call(result, key)) {
      invalidCheckoutQuery();
    }
    result[key] = decodePart(rawValue);
  });
  return result;
}

function positiveInteger(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) {
    return null;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function parseCartItemIds(value: string | undefined): number[] | null {
  if (!value) {
    return null;
  }
  const parsed = value.split(",").map(positiveInteger);
  if (parsed.some((item) => item === null)) {
    return null;
  }
  return Array.from(new Set(parsed as number[]));
}

function hasOnlyKeys(
  query: Record<string, string | undefined>,
  allowedKeys: readonly string[]
): boolean {
  const allowed = new Set(allowedKeys);
  return Object.keys(query).every((key) => allowed.has(key));
}

export function createLatestRequestTracker(): LatestRequestTracker {
  let latestToken = 0;
  return {
    begin() {
      latestToken += 1;
      return latestToken;
    },
    isLatest(token) {
      return token === latestToken;
    }
  };
}

export function clampQuantity(quantity: number, stock: number): number {
  const normalizedStock = Number.isFinite(stock) ? Math.floor(stock) : 0;
  const upperBound = Math.min(999, Math.max(1, normalizedStock));
  const normalizedQuantity = Number.isFinite(quantity) ? Math.floor(quantity) : 1;
  return Math.min(upperBound, Math.max(1, normalizedQuantity));
}

export function directSelection(skuId: number, quantity: number): CheckoutQuery {
  if (!Number.isSafeInteger(skuId) || skuId <= 0) {
    return invalidCheckoutQuery();
  }
  const normalizedQuantity = clampQuantity(quantity, 999);
  if (normalizedQuantity !== quantity) {
    return invalidCheckoutQuery();
  }
  return { source: "DIRECT", skuId, quantity };
}

export function cartSelection(cartItemIds: number[]): CheckoutQuery {
  const normalized = Array.from(new Set(cartItemIds));
  if (
    normalized.length === 0 ||
    normalized.some((id) => !Number.isSafeInteger(id) || id <= 0)
  ) {
    return invalidCheckoutQuery();
  }
  return { source: "CART", cartItemIds: normalized };
}

export function buildDirectBuyUrl(skuId: number, quantity: number): string {
  const selection = directSelection(skuId, quantity);
  if (selection.source !== "DIRECT") {
    return invalidCheckoutQuery();
  }
  return `${PREVIEW_PATH}?source=DIRECT&sku_id=${encodeURIComponent(String(selection.skuId))}&quantity=${encodeURIComponent(String(selection.quantity))}`;
}

export function buildCartCheckoutUrl(cartItemIds: number[]): string {
  const selection = cartSelection(cartItemIds);
  if (selection.source !== "CART") {
    return invalidCheckoutQuery();
  }
  return `${PREVIEW_PATH}?source=CART&cart_item_ids=${encodeURIComponent(selection.cartItemIds.join(","))}`;
}

export function parseCheckoutQuery(input: QueryInput): CheckoutQuery {
  const query = typeof input === "string" ? parseQueryString(input) : input;
  if (query.source === "CART") {
    if (!hasOnlyKeys(query, ["source", "cart_item_ids"])) {
      return invalidCheckoutQuery();
    }
    const ids = parseCartItemIds(query.cart_item_ids);
    if (!ids || query.sku_id !== undefined || query.quantity !== undefined) {
      return invalidCheckoutQuery();
    }
    return cartSelection(ids);
  }
  if (query.source === "DIRECT") {
    if (!hasOnlyKeys(query, ["source", "sku_id", "quantity"])) {
      return invalidCheckoutQuery();
    }
    const skuId = positiveInteger(query.sku_id);
    const quantity = positiveInteger(query.quantity);
    if (
      skuId === null ||
      quantity === null ||
      quantity > 999 ||
      query.cart_item_ids !== undefined
    ) {
      return invalidCheckoutQuery();
    }
    return directSelection(skuId, quantity);
  }
  return invalidCheckoutQuery();
}

export function buildPreviewRequest(
  selection: CheckoutQuery,
  addressId: string | null,
  userCouponId: number | null
): OrderPreviewRequest {
  const common = {
    ...(addressId === null ? {} : { addressId }),
    userCouponId
  };
  return selection.source === "CART"
    ? { source: "CART", cartItemIds: [...selection.cartItemIds], ...common }
    : {
        source: "DIRECT",
        skuId: selection.skuId,
        quantity: selection.quantity,
        ...common
      };
}

export function buildSubmitRequest(
  selection: CheckoutQuery,
  addressId: string,
  userCouponId: number | null,
  idempotencyKey: string
): OrderSubmitRequest {
  const common = { addressId, userCouponId, idempotencyKey };
  return selection.source === "CART"
    ? { source: "CART", cartItemIds: [...selection.cartItemIds], ...common }
    : {
        source: "DIRECT",
        skuId: selection.skuId,
        quantity: selection.quantity,
        ...common
      };
}

export function buildProductCommand(
  action: "ADD_TO_CART" | "DIRECT_BUY",
  selectedSku: ProductSku | undefined,
  quantity: number
): ProductCommand {
  if (!selectedSku) {
    return { type: "ERROR", message: "请选择商品规格" };
  }
  if (selectedSku.status !== "ENABLED") {
    return { type: "ERROR", message: "该规格已下架" };
  }
  if (selectedSku.stockAvailable <= 0) {
    return { type: "ERROR", message: "该规格已售罄" };
  }
  const selectedQuantity = clampQuantity(quantity, selectedSku.stockAvailable);
  if (action === "ADD_TO_CART") {
    return {
      type: "ADD_TO_CART",
      payload: { skuId: selectedSku.id, quantity: selectedQuantity }
    };
  }
  return {
    type: "DIRECT_BUY",
    url: buildDirectBuyUrl(selectedSku.id, selectedQuantity)
  };
}

export function resolveAddressSelection(
  addresses: AddressResponse[],
  current: AddressResponse | null
): AddressResponse | null {
  if (current) {
    const stillExists = addresses.find((address) => address.id === current.id);
    if (stillExists) {
      return stillExists;
    }
  }
  return addresses.find((address) => address.isDefault) ?? addresses[0] ?? null;
}

export function replaceAddressFromEvent(
  _current: AddressResponse | null,
  selected: AddressResponse
): AddressResponse {
  return { ...selected };
}

export function isAddressResponse(value: unknown): value is AddressResponse {
  if (!value || typeof value !== "object") {
    return false;
  }
  const address = value as Partial<AddressResponse>;
  return (
    typeof address.id === "string" &&
    /^[1-9]\d*$/.test(address.id) &&
    typeof address.receiverName === "string" &&
    typeof address.receiverPhone === "string" &&
    typeof address.province === "string" &&
    typeof address.city === "string" &&
    typeof address.district === "string" &&
    typeof address.detailAddress === "string" &&
    typeof address.isDefault === "boolean" &&
    typeof address.formattedAddress === "string" &&
    typeof address.createdAt === "string" &&
    typeof address.updatedAt === "string"
  );
}

export function isCheckoutSubmitDisabled(
  hasPreview: boolean,
  selectedAddress: AddressResponse | null,
  submitting: boolean
): boolean {
  return !hasPreview || selectedAddress === null || submitting;
}

export function createIdempotencyKey(
  now = Date.now(),
  randomValue = Math.random()
): string {
  return `mp_${now.toString(36)}_${randomValue.toString(36).slice(2, 14)}`;
}
