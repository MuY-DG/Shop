import { displaySpecText, formatMoney } from "./product-catalog";
import type { CartItemResponse } from "../types/cart";
import type {
  AddressResponse,
  AvailableCouponItem,
  CheckoutSelection,
  OrderPreviewItem,
  OrderPreviewRequest,
  OrderPreviewResponse,
  OrderSubmitRequest
} from "../types/checkout";
import { formatLocalDate } from "../utils/date-time";

const PREVIEW_PATH = "/pages/order/preview/preview";

export interface CartItemView extends CartItemResponse {
  selected: boolean;
  hasImage: boolean;
  imageUrl: string;
  priceText: string;
  priceIntegerText: string;
  priceDecimalText: string;
  retailPriceText: string;
  hasRetailPrice: boolean;
  wholesaleText: string;
  nextWholesaleText: string;
  lineAmountText: string;
  unavailableText: string;
}

export interface CartSummaryView {
  items: CartItemView[];
  selectedIds: number[];
  selectedQuantity: number;
  selectedAmountCent: number;
  selectedAmountText: string;
  availableCount: number;
  allAvailableSelected: boolean;
  checkoutDisabled: boolean;
}

export interface OrderPreviewItemView extends OrderPreviewItem {
  imageUrl: string;
  hasImage: boolean;
  unitPriceText: string;
  retailPriceText: string;
  hasRetailPrice: boolean;
  retailLineAmountText: string;
  hasRetailLineAmount: boolean;
  wholesaleText: string;
  lineAmountText: string;
}

export interface CheckoutAddressView extends AddressResponse {
  receiverPhoneDisplay: string;
}

export interface OrderPreviewView extends OrderPreviewResponse {
  items: OrderPreviewItemView[];
  productAmountText: string;
  wholesaleDiscountCent: number;
  wholesaleDiscountText: string;
  hasWholesaleDiscount: boolean;
  couponDiscountText: string;
  hasCouponDiscount: boolean;
  freightText: string;
  payableAmountText: string;
  originalPayableAmountText: string;
  totalDiscountText: string;
  hasTotalDiscount: boolean;
  totalQuantity: number;
}

export interface CouponOptionView extends AvailableCouponItem {
  discountText: string;
  conditionText: string;
  validityText: string;
  unavailableText: string;
  selected: boolean;
}

function positiveInteger(value: unknown): number {
  const text = typeof value === "string" ? value.trim() : value;
  if (typeof text === "string" && !/^\d+$/.test(text)) {
    return 0;
  }
  const parsed = Number(text);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

function hasOnlyQueryKeys(
  query: Record<string, string | undefined>,
  allowed: readonly string[]
): boolean {
  const allowedKeys = new Set(allowed);
  return Object.keys(query).every((key) => (
    query[key] === undefined || allowedKeys.has(key)
  ));
}

function money(cent: unknown): string {
  return `¥${formatMoney(cent) || "0.00"}`;
}

function moneyParts(cent: unknown): { integerText: string; decimalText: string } {
  const [integerText, fraction = "00"] = (formatMoney(cent) || "0.00").split(".");
  return {
    integerText,
    decimalText: `.${fraction}`
  };
}

function unavailableText(item: CartItemResponse): string {
  switch (item.unavailableReason) {
    case "SKU_UNAVAILABLE":
      return "该规格已下架";
    case "PRODUCT_UNAVAILABLE":
      return "商品已下架";
    case "SOLD_OUT":
      return "暂时售罄";
    case "STOCK_SHORTAGE":
      return "库存不足，请调整数量";
    default:
      return item.available ? "" : "商品暂不可购买";
  }
}

function cartItemView(
  item: CartItemResponse,
  selected: boolean,
  includeUnavailableSelection = false
): CartItemView {
  const imageUrl = (item.displayImage || item.skuImage || item.mainImage || "").trim();
  const wholesaleApplied = Boolean(item.wholesaleTierMinQuantity);
  const nextTierQuantity = item.nextWholesaleTierQuantityNeeded ?? 0;
  const nextTierPrice = item.nextWholesaleTierPriceCent;
  const priceParts = moneyParts(item.priceCent);
  return {
    ...item,
    specText: displaySpecText(item.specText),
    selected: selected && (item.available || includeUnavailableSelection),
    hasImage: Boolean(imageUrl),
    imageUrl,
    priceText: money(item.priceCent),
    priceIntegerText: priceParts.integerText,
    priceDecimalText: priceParts.decimalText,
    retailPriceText: money(item.retailPriceCent),
    hasRetailPrice: wholesaleApplied && item.retailPriceCent > item.priceCent,
    wholesaleText: wholesaleApplied
      ? `已享 ${item.wholesaleTierMinQuantity} 件起批发价`
      : "",
    nextWholesaleText: nextTierQuantity > 0 && nextTierPrice !== undefined
      ? `再买 ${nextTierQuantity} 件，每件 ${money(nextTierPrice)}`
      : "",
    lineAmountText: money(item.lineAmountCent),
    unavailableText: unavailableText(item)
  };
}

export function reconcileCartSelection(
  items: CartItemResponse[],
  selectedIds: number[],
  selectAllAvailable: boolean,
  includeUnavailable = false
): number[] {
  const availableIds = items
    .filter((item) => item.available || includeUnavailable)
    .map((item) => item.id);
  if (selectAllAvailable) {
    return availableIds;
  }
  const availableSet = new Set(availableIds);
  return Array.from(new Set(selectedIds)).filter((id) => availableSet.has(id));
}

export function preserveCartItemOrder(
  items: CartItemResponse[],
  previousIds: number[]
): CartItemResponse[] {
  if (!items.length || !previousIds.length) {
    return [...items];
  }
  const itemById = new Map(items.map((item) => [item.id, item]));
  const ordered = previousIds
    .map((id) => itemById.get(id))
    .filter((item): item is CartItemResponse => Boolean(item));
  const previousIdSet = new Set(previousIds);
  return [
    ...ordered,
    ...items.filter((item) => !previousIdSet.has(item.id))
  ];
}

export function buildCartSummary(
  items: CartItemResponse[],
  selectedIds: number[],
  includeUnavailableSelection = false
): CartSummaryView {
  const selectedSet = new Set(selectedIds);
  const views = items.map((item) => cartItemView(
    item,
    selectedSet.has(item.id),
    includeUnavailableSelection
  ));
  const selectedItems = views.filter((item) => (
    item.selected && (item.available || includeUnavailableSelection)
  ));
  const availableCount = views.filter((item) => (
    item.available || includeUnavailableSelection
  )).length;
  const normalizedSelectedIds = selectedItems.map((item) => item.id);
  const selectedQuantity = selectedItems.reduce((total, item) => total + item.quantity, 0);
  const selectedAmountCent = selectedItems.reduce(
    (total, item) => total + item.lineAmountCent,
    0
  );
  return {
    items: views,
    selectedIds: normalizedSelectedIds,
    selectedQuantity,
    selectedAmountCent,
    selectedAmountText: money(selectedAmountCent),
    availableCount,
    allAvailableSelected: availableCount > 0 && normalizedSelectedIds.length === availableCount,
    checkoutDisabled: normalizedSelectedIds.length === 0
  };
}

export function toggleCartSelection(
  selectedIds: number[],
  cartItemId: number
): number[] {
  const normalizedId = positiveInteger(cartItemId);
  if (!normalizedId) {
    return [...selectedIds];
  }
  const selected = new Set(selectedIds);
  if (selected.has(normalizedId)) {
    selected.delete(normalizedId);
  } else {
    selected.add(normalizedId);
  }
  return [...selected];
}

export function buildDirectBuyUrl(skuId: number, quantity: number): string {
  const normalizedSkuId = positiveInteger(skuId);
  const normalizedQuantity = positiveInteger(quantity);
  if (!normalizedSkuId || !normalizedQuantity || normalizedQuantity > 999) {
    throw new Error("结算参数无效");
  }
  return `${PREVIEW_PATH}?source=DIRECT&sku_id=${normalizedSkuId}&quantity=${normalizedQuantity}`;
}

export function buildCartCheckoutUrl(cartItemIds: number[]): string {
  const normalizedIds = Array.from(new Set(cartItemIds.map(positiveInteger)));
  if (!normalizedIds.length || normalizedIds.some((id) => !id)) {
    throw new Error("请选择需要结算的商品");
  }
  return `${PREVIEW_PATH}?source=CART&cart_item_ids=${normalizedIds.join(",")}`;
}

export function parseCheckoutQuery(
  query: Record<string, string | undefined>
): CheckoutSelection {
  if (query.source === "CART") {
    if (!hasOnlyQueryKeys(query, ["source", "cart_item_ids"])) {
      throw new Error("购物车结算参数无效");
    }
    const rawIds = (query.cart_item_ids || "").split(",");
    const ids = Array.from(new Set(rawIds.map(positiveInteger)));
    if (!ids.length || ids.some((id) => !id)) {
      throw new Error("购物车结算参数无效");
    }
    return { source: "CART", cartItemIds: ids };
  }
  if (query.source === "DIRECT") {
    if (!hasOnlyQueryKeys(query, ["source", "sku_id", "quantity"])) {
      throw new Error("立即购买参数无效");
    }
    const skuId = positiveInteger(query.sku_id);
    const quantity = positiveInteger(query.quantity);
    if (!skuId || !quantity || quantity > 999) {
      throw new Error("立即购买参数无效");
    }
    return { source: "DIRECT", skuId, quantity };
  }
  throw new Error("结算来源无效");
}

export function buildCheckoutAddressView(address: AddressResponse): CheckoutAddressView {
  const receiverPhone = address.receiverPhone.trim();
  return {
    ...address,
    receiverPhoneDisplay: /^\d{11}$/.test(receiverPhone)
      ? `${receiverPhone.slice(0, 3)}****${receiverPhone.slice(-4)}`
      : receiverPhone
  };
}

export function resolveAddressSelection<T extends AddressResponse>(
  addresses: T[],
  current: AddressResponse | null
): T | null {
  if (current) {
    const matched = addresses.find((address) => address.id === current.id);
    if (matched) {
      return matched;
    }
  }
  return addresses.find((address) => address.isDefault) ?? addresses[0] ?? null;
}

export function buildPreviewRequest(
  selection: CheckoutSelection,
  addressId?: string,
  userCouponId?: number
): OrderPreviewRequest {
  const common = {
    ...(addressId ? { addressId } : {}),
    ...(userCouponId ? { userCouponId } : {})
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
  selection: CheckoutSelection,
  addressId: string,
  userCouponId: number | undefined,
  idempotencyKey: string
): OrderSubmitRequest {
  const common = {
    addressId,
    ...(userCouponId ? { userCouponId } : {}),
    idempotencyKey
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

function previewItemView(item: OrderPreviewItem): OrderPreviewItemView {
  const imageUrl = (item.displayImage || item.skuImage || item.mainImage || "").trim();
  const wholesaleApplied = Boolean(item.wholesaleTierMinQuantity);
  const retailLineAmountCent = item.retailUnitPriceCent * item.quantity;
  return {
    ...item,
    specText: displaySpecText(item.specText),
    imageUrl,
    hasImage: Boolean(imageUrl),
    unitPriceText: money(item.unitPriceCent),
    retailPriceText: money(item.retailUnitPriceCent),
    hasRetailPrice: wholesaleApplied && item.retailUnitPriceCent > item.unitPriceCent,
    retailLineAmountText: money(retailLineAmountCent),
    hasRetailLineAmount: wholesaleApplied && retailLineAmountCent > item.lineAmountCent,
    wholesaleText: wholesaleApplied
      ? `${item.wholesaleTierMinQuantity} 件起批发价`
      : "",
    lineAmountText: money(item.lineAmountCent)
  };
}

export function buildOrderPreviewView(preview: OrderPreviewResponse): OrderPreviewView {
  const retailProductAmountCent = preview.items.reduce(
    (total, item) => total + item.retailUnitPriceCent * item.quantity,
    0
  );
  const wholesaleDiscountCent = Math.max(
    retailProductAmountCent - preview.productAmountCent,
    0
  );
  const originalPayableAmountCent = retailProductAmountCent + preview.freightCent;
  const totalDiscountCent = Math.max(
    originalPayableAmountCent - preview.payableAmountCent,
    0
  );
  const totalQuantity = preview.items.reduce(
    (total, item) => total + item.quantity,
    0
  );
  return {
    ...preview,
    items: preview.items.map(previewItemView),
    productAmountText: money(retailProductAmountCent),
    wholesaleDiscountCent,
    wholesaleDiscountText: money(wholesaleDiscountCent),
    hasWholesaleDiscount: wholesaleDiscountCent > 0,
    couponDiscountText: money(preview.couponDiscountCent),
    hasCouponDiscount: preview.couponDiscountCent > 0,
    freightText: money(preview.freightCent),
    payableAmountText: money(preview.payableAmountCent),
    originalPayableAmountText: money(originalPayableAmountCent),
    totalDiscountText: money(totalDiscountCent),
    hasTotalDiscount: totalDiscountCent > 0,
    totalQuantity
  };
}

function couponUnavailableText(reason?: string): string {
  switch (reason) {
    case "THRESHOLD_NOT_MET":
      return "未达到使用门槛";
    case "SCOPE_NOT_APPLICABLE":
      return "不适用于当前商品";
    default:
      return "当前订单不可用";
  }
}

function couponValidityText(validEndAt: string): string {
  const dateText = formatLocalDate(validEndAt).replace(/-/g, ".");
  return dateText ? `有效期至 ${dateText}` : "";
}

export function buildCouponOptionViews(
  coupons: AvailableCouponItem[],
  selectedUserCouponId?: number
): CouponOptionView[] {
  return coupons.map((coupon) => ({
    ...coupon,
    discountText: money(coupon.discountCent),
    conditionText: coupon.thresholdCent > 0
      ? `满 ${money(coupon.thresholdCent)} 可用`
      : "无门槛",
    validityText: couponValidityText(coupon.validEndAt),
    unavailableText: coupon.available
      ? ""
      : couponUnavailableText(coupon.unavailableReason),
    selected: coupon.available && coupon.userCouponId === selectedUserCouponId
  }));
}

export function createIdempotencyKey(
  now = Date.now(),
  randomValue = Math.random()
): string {
  return `mp_${now.toString(36)}_${randomValue.toString(36).slice(2, 14)}`;
}
