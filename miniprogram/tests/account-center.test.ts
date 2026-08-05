import assert from "node:assert/strict";
import { test } from "node:test";

import {
  ACCOUNT_ROUTES,
  accountNavigationPath,
  buildClaimableCouponViews,
  buildFavoriteProductViews,
  buildHistoryProductViews,
  buildUserCouponViews,
  composeAddressListTitle,
  groupHistoryProductViews,
  normalizeAddressForm,
  parseAddressId,
  parseCouponStatusFilter,
  validateAddressForm,
  type AddressFormValue
} from "../miniprogram/features/account-center";

const validAddress: AddressFormValue = {
  receiverName: " 张三 ",
  receiverPhone: " 138 0000 0000 ",
  province: "四川省",
  city: "成都市",
  district: "锦江区",
  detailAddress: " 春熙路  1 号 ",
  locationName: " 春熙里 ",
  doorplate: " 3 栋  201 ",
  isDefault: true
};

test("账户中心只允许注册页面并拒绝可疑路由", () => {
  assert.equal(accountNavigationPath(ACCOUNT_ROUTES.profile), ACCOUNT_ROUTES.profile);
  assert.equal(accountNavigationPath(ACCOUNT_ROUTES.addresses), ACCOUNT_ROUTES.addresses);
  assert.equal(accountNavigationPath(ACCOUNT_ROUTES.coupons), ACCOUNT_ROUTES.coupons);
  assert.equal(accountNavigationPath("/pages/message/message"), "");
  assert.equal(accountNavigationPath("https://example.test"), "");
});

test("地址表单规范空白并校验姓名、电话、地区和详细地址", () => {
  assert.equal(validateAddressForm(validAddress), "");
  assert.deepEqual(normalizeAddressForm(validAddress), {
    receiverName: "张三",
    receiverPhone: "13800000000",
    province: "四川省",
    city: "成都市",
    district: "锦江区",
    detailAddress: "春熙路 1 号",
    locationName: "春熙里",
    doorplate: "3 栋 201",
    isDefault: true
  });
  assert.equal(validateAddressForm({ ...validAddress, receiverName: "" }), "请填写收货人姓名");
  assert.equal(
    validateAddressForm({ ...validAddress, receiverName: "一二三四五六七八九十" }),
    ""
  );
  assert.equal(
    validateAddressForm({ ...validAddress, receiverName: "一二三四五六七八九十一" }),
    "收货人姓名不能超过 10 个字"
  );
  assert.equal(validateAddressForm({ ...validAddress, receiverPhone: "abc" }), "请填写有效的手机号码");
  assert.equal(
    validateAddressForm({ ...validAddress, receiverPhone: "12800138000" }),
    "请填写有效的手机号码"
  );
  assert.equal(
    validateAddressForm({ ...validAddress, receiverPhone: "1380013800" }),
    "请填写有效的手机号码"
  );
  assert.equal(validateAddressForm({ ...validAddress, district: "" }), "请通过地图选择完整地址");
  assert.equal(validateAddressForm({ ...validAddress, detailAddress: "" }), "请通过地图选择详细地址");
  assert.equal(parseAddressId("9007199254740993123"), "9007199254740993123");
  assert.equal(parseAddressId("0"), "");
  assert.equal(parseAddressId("12x"), "");
  assert.equal(
    composeAddressListTitle("吉利学院(成都校区)", "666", "成简大道二段123号", ""),
    "吉利学院(成都校区)666"
  );
  assert.equal(
    composeAddressListTitle("紫都学府", "666", "紫都学府商业楼", ""),
    "紫都学府666"
  );
  assert.equal(
    composeAddressListTitle("", "", "历史详细地址", "四川省成都市历史详细地址"),
    "历史详细地址"
  );
});

test("收藏复用分类商品卡片且不展示收藏时间，浏览记录保留足迹日期", () => {
  const favorites = buildFavoriteProductViews([{
    spuId: 12,
    title: "牛油火锅底料",
    subtitle: "醇厚牛油香",
    mainImage: "https://example.test/product.jpg",
    minPriceCent: 2990,
    maxPriceCent: 3990,
    available: true,
    favoritedAt: "2026-07-20T12:00:00Z"
  }]);
  assert.equal(favorites[0]?.priceText, "29.90–39.90");
  assert.equal(favorites[0]?.hasPrice, true);
  assert.equal(favorites[0]?.priceIntegerText, "29");
  assert.equal(favorites[0]?.priceDecimalText, ".90");
  assert.equal(favorites[0]?.rangePriceIntegerText, "39");
  assert.equal(favorites[0]?.rangePriceDecimalText, ".90");
  assert.equal(favorites[0]?.hasPriceRange, true);
  assert.equal(favorites[0]?.selected, false);
  assert.equal("metaText" in (favorites[0] ?? {}), false);
  assert.equal(favorites[0]?.navigationPath, "/pages/product/detail/detail?id=12");

  const history = buildHistoryProductViews([{
    spuId: 13,
    title: "清油火锅底料",
    minPriceCent: 1990,
    available: false,
    firstViewedAt: "2026-07-18T12:00:00Z",
    lastViewedAt: "2026-07-21T10:00:00Z",
    viewCount: 3
  }]);
  assert.equal(history[0]?.priceText, "¥19.90");
  assert.equal(history[0]?.availabilityText, "商品已下架");
  assert.equal(history[0]?.metaText, "");
  assert.equal(history[0]?.navigationPath, "/pages/product/detail/detail?id=13");
});

test("足迹按本地日期分组并在分页、删除和图片降级后重新归组", () => {
  const originalTimezone = process.env.TZ;
  try {
    process.env.TZ = "America/Los_Angeles";
    const firstPage = buildHistoryProductViews([{
      spuId: 21,
      title: "第一页商品",
      mainImage: "https://example.test/first.jpg",
      minPriceCent: 1290,
      available: true,
      firstViewedAt: "2026-07-29T18:00:00Z",
      lastViewedAt: "2026-07-30T06:30:00Z",
      viewCount: 2
    }]);
    const nextPage = buildHistoryProductViews([{
      spuId: 22,
      title: "同日跨页商品",
      mainImage: "https://example.test/second.jpg",
      minPriceCent: 2390,
      available: true,
      firstViewedAt: "2026-07-29T17:00:00Z",
      lastViewedAt: "2026-07-30T05:00:00Z",
      viewCount: 4
    }, {
      spuId: 23,
      title: "前一日商品",
      minPriceCent: 3190,
      available: false,
      firstViewedAt: "2026-07-28T16:00:00Z",
      lastViewedAt: "2026-07-29T06:30:00Z",
      viewCount: 1
    }]);

    assert.equal(firstPage[0]?.historyDateKey, "2026-07-29");
    assert.equal(firstPage[0]?.historyDateLabel, "07月29日");

    const today = buildHistoryProductViews([{
      spuId: 24,
      title: "今日商品",
      minPriceCent: 2190,
      available: true,
      firstViewedAt: "2026-07-30T16:00:00Z",
      lastViewedAt: "2026-07-30T16:00:00Z",
      viewCount: 1
    }], new Date("2026-07-30T18:00:00Z"));
    assert.equal(today[0]?.historyDateKey, "2026-07-30");
    assert.equal(today[0]?.historyDateLabel, "今天");

    const appended = [...firstPage, ...nextPage];
    const appendedGroups = groupHistoryProductViews(appended);
    assert.deepEqual(
      appendedGroups.map((group) => ({
        key: group.key,
        label: group.label,
        ids: group.items.map((item) => item.spuId)
      })),
      [{
        key: "2026-07-29",
        label: "07月29日",
        ids: [21, 22]
      }, {
        key: "2026-07-28",
        label: "07月28日",
        ids: [23]
      }]
    );

    const afterDeletion = appended.filter((item) => item.spuId !== 21);
    assert.deepEqual(
      groupHistoryProductViews(afterDeletion)[0]?.items.map((item) => item.spuId),
      [22]
    );

    const afterImageFallback = appended.map((item) => (
      item.spuId === 22 ? { ...item, hasImage: false } : item
    ));
    const fallbackGroups = groupHistoryProductViews(afterImageFallback);
    assert.equal(fallbackGroups[0]?.items[1]?.hasImage, false);
    assert.equal(appended[1]?.hasImage, true);
    assert.deepEqual(groupHistoryProductViews([]), []);
  } finally {
    if (originalTimezone === undefined) {
      delete process.env.TZ;
    } else {
      process.env.TZ = originalTimezone;
    }
  }
});

test("领券中心和我的优惠券生成稳定状态与操作", () => {
  const claimable = buildClaimableCouponViews([{
    templateId: 1,
    name: "新人立减券",
    description: "首次下单可领",
    couponType: "MIN_SPEND",
    thresholdCent: 5000,
    discountCent: 1000,
    validStartAt: "2026-07-01T00:00:00Z",
    validEndAt: "2026-07-31T23:59:59Z",
    claimedCount: 1,
    perUserLimit: 1,
    claimable: true
  }, {
    templateId: 2,
    name: "限领券",
    couponType: "NO_THRESHOLD",
    thresholdCent: 0,
    discountCent: 500,
    validStartAt: "2026-07-01T00:00:00Z",
    validEndAt: "2026-07-31T23:59:59Z",
    claimedCount: 1,
    perUserLimit: 1,
    claimable: false,
    unavailableReason: "CLAIM_LIMIT_REACHED"
  }]);
  assert.equal(claimable[0]?.amountText, "10");
  assert.equal(claimable[0]?.conditionText, "满 ¥50 可用");
  assert.equal(claimable[0]?.actionText, "立即领取");
  assert.equal(claimable[1]?.actionText, "已领取");
  assert.equal(claimable[1]?.actionDisabled, true);

  const mine = buildUserCouponViews([{
    userCouponId: 9,
    templateId: 1,
    name: "新人立减券",
    couponType: "MIN_SPEND",
    thresholdCent: 5000,
    discountCent: 1000,
    scopeType: "ALL",
    status: "CLAIMED",
    validStartAt: "2026-07-01T00:00:00Z",
    validEndAt: "2026-07-31T23:59:59Z",
    claimedAt: "2026-07-20T10:00:00Z"
  }, {
    userCouponId: 10,
    templateId: 2,
    name: "订单锁定券",
    couponType: "NO_THRESHOLD",
    thresholdCent: 0,
    discountCent: 500,
    scopeType: "PRODUCT",
    status: "LOCKED",
    validStartAt: "2026-07-01T00:00:00Z",
    validEndAt: "2026-07-31T23:59:59Z",
    claimedAt: "2026-07-20T10:00:00Z"
  }]);
  assert.equal(mine[0]?.statusText, "待使用");
  assert.equal(mine[0]?.actionText, "去选购");
  assert.equal(mine[1]?.statusText, "订单使用中");
  assert.equal(mine[1]?.actionDisabled, true);
  assert.equal(parseCouponStatusFilter("CLAIMED"), "CLAIMED");
  assert.equal(parseCouponStatusFilter("LOCKED"), "ALL");
});
