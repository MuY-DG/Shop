import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildMyProductReviewViews,
  buildProductReviewSummaryView,
  buildPublicProductReviewViews,
  buildRatingStars,
  buildReviewableOrderItemViews,
  formatReviewDate,
  normalizeRating,
  normalizeReviewContent
} from "../miniprogram/features/product-review";

test("评价摘要规范评分、数量和好评率", () => {
  assert.deepEqual(buildProductReviewSummaryView({
    reviewCount: 8,
    averageRating: 4.56,
    goodReviewCount: 7
  }), {
    reviewCount: 8,
    reviewCountText: "8 条评价",
    averageRating: 4.56,
    averageRatingText: "4.6",
    goodRateText: "88% 好评",
    hasReviews: true
  });
  assert.deepEqual(buildProductReviewSummaryView(), {
    reviewCount: 0,
    reviewCountText: "暂无评价",
    averageRating: 0,
    averageRatingText: "0.0",
    goodRateText: "期待首条评价",
    hasReviews: false
  });
});

test("评价星级限制在一到五星并生成稳定展示状态", () => {
  assert.equal(normalizeRating(7), 5);
  assert.equal(normalizeRating("2.4"), 2);
  assert.equal(normalizeRating("bad", 4), 4);
  assert.deepEqual(
    buildRatingStars(3).map((star) => star.filled),
    [true, true, true, false, false]
  );
  assert.equal(buildRatingStars(0).some((star) => star.filled), false);
});

test("公开评价过滤无效记录并生成匿名、日期和规格展示", () => {
  const views = buildPublicProductReviewViews([
    {
      id: 9,
      skuSpecText: " 500g 袋装 ",
      rating: 5,
      content: "  香味很足  ",
      anonymous: false,
      reviewerName: " 小灶 ",
      verifiedPurchase: true,
      createdAt: "2026-07-20T09:30:00",
      updatedAt: "2026-07-20T09:30:00"
    },
    {
      id: 0,
      skuSpecText: "",
      rating: 1,
      content: "",
      anonymous: true,
      reviewerName: "",
      verifiedPurchase: false,
      createdAt: "",
      updatedAt: ""
    }
  ]);
  assert.equal(views.length, 1);
  assert.equal(views[0]?.reviewerName, "小灶");
  assert.equal(views[0]?.reviewerInitial, "小");
  assert.equal(views[0]?.content, "香味很足");
  assert.equal(views[0]?.createdAtText, "2026-07-20");
  assert.equal(views[0]?.skuSpecText, "500g 袋装");
});

test("可评价订单和文字输入生成提交前的安全值", () => {
  const items = buildReviewableOrderItemViews([{
    orderItemId: 11,
    orderId: 3,
    orderNo: "SO-3",
    skuId: 7,
    skuSpecText: "",
    completedAt: "2026-07-01T12:00:00"
  }]);
  assert.equal(items[0]?.label, "默认规格");
  assert.equal(items[0]?.completedAtText, "2026-07-01");
  assert.equal(formatReviewDate("invalid"), "");
  assert.equal(normalizeReviewContent("  很好吃  "), "很好吃");
  assert.equal(normalizeReviewContent("x".repeat(1002)).length, 1000);
});

test("我的评价保留修改和删除所需的订单与匿名状态", () => {
  const views = buildMyProductReviewViews([{
    id: 13,
    spuId: 41,
    productTitle: "经典牛油锅底",
    orderItemId: 21,
    skuSpecText: "500g",
    rating: 4,
    content: "不错",
    anonymous: true,
    reviewerName: "匿名用户",
    verifiedPurchase: true,
    createdAt: "2026-07-20T09:30:00",
    updatedAt: "2026-07-20T09:30:00"
  }]);
  assert.equal(views[0]?.id, 13);
  assert.equal(views[0]?.orderItemId, 21);
  assert.equal(views[0]?.anonymous, true);
  assert.equal(views[0]?.stars.filter((star) => star.filled).length, 4);
});
