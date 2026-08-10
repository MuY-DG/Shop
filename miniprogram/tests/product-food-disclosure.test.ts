import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import { normalizeProductFoodDisclosure } from "../miniprogram/features/product-catalog";

const detailTemplate = readFileSync(
  resolve(process.cwd(), "miniprogram/pages/product/detail/detail.wxml"),
  "utf8"
);

const completeFood = {
  complianceType: "FOOD",
  foodName: "真实食品名称",
  ingredients: "真实配料",
  allergenInformation: "",
  storageConditions: "真实贮存条件",
  shelfLifeDescription: "标签载明期限",
  manufacturerName: "真实生产者",
  manufacturerAddress: "真实生产地址",
  productionLicenseNumber: "SC12345678901234",
  origin: "真实产地",
  consumerNotice: "",
  variableProductionNotice: "批次和生产日期以收到商品包装标示为准",
  labelAssets: [{ fileId: "91", url: "https://assets.example.test/label.jpg", sortOrder: 0 }]
};

test("商品食品披露只接受完整 FOOD 结构且不把未分类当食品展示", () => {
  assert.equal(normalizeProductFoodDisclosure({ complianceType: "UNCLASSIFIED" }), undefined);
  assert.equal(normalizeProductFoodDisclosure({ ...completeFood, ingredients: "" }), undefined);
  assert.equal(normalizeProductFoodDisclosure({ ...completeFood, labelAssets: [] }), undefined);
  assert.equal(normalizeProductFoodDisclosure(completeFood)?.foodName, "真实食品名称");
});

test("食品标签按排序展示且位于自由商品详情正文之前", () => {
  const disclosure = normalizeProductFoodDisclosure({
    ...completeFood,
    labelAssets: [
      { fileId: "2", url: "https://assets.example.test/two.jpg", sortOrder: 2 },
      { fileId: "1", url: "https://assets.example.test/one.jpg", sortOrder: 1 }
    ]
  });
  assert.deepEqual(disclosure?.labelAssets.map((item) => item.fileId), ["1", "2"]);
  assert.ok(
    detailTemplate.indexOf('class="detail-card food-disclosure"')
      < detailTemplate.indexOf('class="detail-card rich-detail"')
  );
  assert.match(detailTemplate, /variableProductionNotice/);
  assert.match(detailTemplate, /onFoodLabelPreview/);
});
