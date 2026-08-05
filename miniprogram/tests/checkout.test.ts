import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildCartCheckoutUrl,
  buildCartSummary,
  buildCouponOptionViews,
  buildDirectBuyUrl,
  buildOrderPreviewView,
  buildPreviewRequest,
  buildSubmitRequest,
  createIdempotencyKey,
  parseCheckoutQuery,
  reconcileCartSelection,
  resolveAddressSelection,
  toggleCartSelection
} from "../miniprogram/features/checkout";
import type { CartItemResponse } from "../miniprogram/types/cart";
import type {
  AddressResponse,
  AvailableCouponItem,
  OrderPreviewResponse
} from "../miniprogram/types/checkout";

function cartItem(overrides: Partial<CartItemResponse> = {}): CartItemResponse {
  return {
    id: 11,
    skuId: 21,
    spuId: 31,
    productTitle: "牛油火锅底料",
    productSubtitle: "地道川味",
    mainImage: "https://example.com/product.png",
    specText: "绿色 / 500g",
    priceCent: 1680,
    retailPriceCent: 1880,
    wholesaleTierMinQuantity: 3,
    nextWholesaleTierMinQuantity: 6,
    nextWholesaleTierPriceCent: 1480,
    nextWholesaleTierQuantityNeeded: 3,
    quantity: 3,
    lineAmountCent: 5040,
    skuStatus: "ENABLED",
    spuStatus: "ON_SALE",
    available: true,
    ...overrides
  };
}

function address(overrides: Partial<AddressResponse> = {}): AddressResponse {
  return {
    id: "101",
    receiverName: "小灶",
    receiverPhone: "13800000000",
    province: "四川省",
    city: "成都市",
    district: "武侯区",
    detailAddress: "灶香路 1 号",
    formattedAddress: "四川省 成都市 武侯区 灶香路 1 号",
    isDefault: false,
    ...overrides
  };
}

test("购物车选择只保留可购买商品并计算选中金额", () => {
  const items = [
    cartItem(),
    cartItem({
      id: 12,
      skuId: 22,
      quantity: 1,
      lineAmountCent: 990,
      priceCent: 990,
      retailPriceCent: 990,
      wholesaleTierMinQuantity: undefined
    }),
    cartItem({
      id: 13,
      skuId: 23,
      available: false,
      unavailableReason: "STOCK_SHORTAGE",
      quantity: 9,
      lineAmountCent: 9000
    })
  ];

  assert.deepEqual(reconcileCartSelection(items, [], true), [11, 12]);
  assert.deepEqual(reconcileCartSelection(items, [12, 13, 999], false), [12]);

  const summary = buildCartSummary(items, [11, 13]);
  assert.deepEqual(summary.selectedIds, [11]);
  assert.equal(summary.selectedQuantity, 3);
  assert.equal(summary.selectedAmountCent, 5040);
  assert.equal(summary.selectedAmountText, "¥50.40");
  assert.equal(summary.availableCount, 2);
  assert.equal(summary.allAvailableSelected, false);
  assert.equal(summary.items[0]?.wholesaleText, "已享 3 件起批发价");
  assert.equal(summary.items[2]?.unavailableText, "库存不足，请调整数量");

  const legacySingle = buildCartSummary([
    cartItem({ specText: "默认规格" })
  ], [11]);
  assert.equal(legacySingle.items[0]?.specText, "");

  assert.deepEqual(reconcileCartSelection(items, [13], false, true), [13]);
  const managementSummary = buildCartSummary(items, [13], true);
  assert.deepEqual(managementSummary.selectedIds, [13]);
  assert.equal(managementSummary.items[2]?.selected, true);

  const soldOutSummary = buildCartSummary([
    cartItem({ available: false, unavailableReason: "SOLD_OUT" })
  ], []);
  assert.equal(soldOutSummary.items[0]?.unavailableText, "暂时售罄");

  assert.deepEqual(toggleCartSelection([11], 12), [11, 12]);
  assert.deepEqual(toggleCartSelection([11, 12], 11), [12]);
});

test("CART 与 DIRECT 结算链接只接受严格正整数参数", () => {
  assert.equal(
    buildCartCheckoutUrl([11, 12, 11]),
    "/pages/order/preview/preview?source=CART&cart_item_ids=11,12"
  );
  assert.equal(
    buildDirectBuyUrl(21, 3),
    "/pages/order/preview/preview?source=DIRECT&sku_id=21&quantity=3"
  );

  assert.deepEqual(parseCheckoutQuery({
    source: "CART",
    cart_item_ids: "11,12,11"
  }), {
    source: "CART",
    cartItemIds: [11, 12]
  });
  assert.deepEqual(parseCheckoutQuery({
    source: "DIRECT",
    sku_id: "21",
    quantity: "3"
  }), {
    source: "DIRECT",
    skuId: 21,
    quantity: 3
  });

  assert.throws(() => buildCartCheckoutUrl([]), /请选择/);
  assert.throws(() => buildDirectBuyUrl(0, 1), /无效/);
  assert.throws(() => parseCheckoutQuery({
    source: "CART",
    cart_item_ids: "11",
    sku_id: "21"
  }), /无效/);
  assert.throws(() => parseCheckoutQuery({
    source: "DIRECT",
    sku_id: "21",
    quantity: "1000"
  }), /无效/);
});

test("结算请求在 CART 与 DIRECT 之间保持互斥契约", () => {
  assert.deepEqual(buildPreviewRequest(
    { source: "CART", cartItemIds: [11, 12] },
    "101",
    501
  ), {
    source: "CART",
    cartItemIds: [11, 12],
    addressId: "101",
    userCouponId: 501
  });

  assert.deepEqual(buildSubmitRequest(
    { source: "DIRECT", skuId: 21, quantity: 3 },
    "101",
    undefined,
    "mp_test"
  ), {
    source: "DIRECT",
    skuId: 21,
    quantity: 3,
    addressId: "101",
    idempotencyKey: "mp_test"
  });
});

test("默认地址、当前地址和订单预览金额生成稳定展示模型", () => {
  const first = address();
  const preferred = address({ id: "102", isDefault: true });
  const addresses = [first, preferred];

  assert.equal(resolveAddressSelection(addresses, null)?.id, "102");
  assert.equal(resolveAddressSelection(addresses, first)?.id, "101");
  assert.equal(resolveAddressSelection([preferred], first)?.id, "102");
  assert.equal(resolveAddressSelection([], first), null);

  const preview: OrderPreviewResponse = {
    items: [{
      skuId: 21,
      spuId: 31,
      productTitle: "牛油火锅底料",
      productSubtitle: "地道川味",
      mainImage: "https://example.com/product.png",
      skuCode: "SKU-21",
      specText: "绿色 / 500g",
      originalPriceCent: 1880,
      unitPriceCent: 1680,
      retailUnitPriceCent: 1880,
      wholesaleTierMinQuantity: 3,
      quantity: 3,
      lineOriginalAmountCent: 5640,
      lineAmountCent: 5040
    }],
    productOriginalAmountCent: 5640,
    productAmountCent: 5040,
    userCouponId: 501,
    couponName: "新人券",
    couponDiscountCent: 500,
    freightCent: 0,
    payableAmountCent: 4540
  };
  const view = buildOrderPreviewView(preview);
  assert.equal(view.productAmountText, "¥56.40");
  assert.equal(view.wholesaleDiscountText, "¥6.00");
  assert.equal(view.hasWholesaleDiscount, true);
  assert.equal(view.couponDiscountText, "¥5.00");
  assert.equal(view.hasCouponDiscount, true);
  assert.equal(view.couponName, "新人券");
  assert.equal(view.freightText, "¥0.00");
  assert.equal(view.payableAmountText, "¥45.40");
  assert.equal(view.items[0]?.wholesaleText, "3 件起批发价");

  const legacySinglePreview = buildOrderPreviewView({
    ...preview,
    items: [{ ...preview.items[0]!, specText: "默认规格" }]
  });
  assert.equal(legacySinglePreview.items[0]?.specText, "");
});

test("优惠券选项区分可用状态、门槛和当前选中项", () => {
  const coupons: AvailableCouponItem[] = [{
    userCouponId: 501,
    templateId: 51,
    name: "新人券",
    couponType: "NO_THRESHOLD",
    thresholdCent: 0,
    discountCent: 500,
    discountAmountCent: 500,
    available: true,
    validEndAt: "2026-07-31T23:59:59Z"
  }, {
    userCouponId: 502,
    templateId: 52,
    name: "满百减十",
    couponType: "MIN_SPEND",
    thresholdCent: 10_000,
    discountCent: 1_000,
    discountAmountCent: 0,
    available: false,
    unavailableReason: "THRESHOLD_NOT_MET",
    validEndAt: "2026-08-31T23:59:59Z"
  }];

  const views = buildCouponOptionViews(coupons, 501);
  assert.equal(views[0]?.selected, true);
  assert.equal(views[0]?.conditionText, "无门槛");
  assert.equal(views[0]?.discountText, "¥5.00");
  assert.equal(views[1]?.selected, false);
  assert.equal(views[1]?.conditionText, "满 ¥100.00 可用");
  assert.equal(views[1]?.unavailableText, "未达到使用门槛");
  assert.equal(views[1]?.validityText, "有效期至 2026.08.31");
});

test("订单幂等键在同一输入下稳定且带小程序前缀", () => {
  assert.equal(createIdempotencyKey(1_000, 0.25), "mp_rs_9");
});
