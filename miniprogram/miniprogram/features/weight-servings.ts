interface WeightServingBand {
  maxGram: number;
  text: string;
}

const WEIGHT_SERVING_BANDS: readonly WeightServingBand[] = [
  { maxGram: 150, text: "适合1-2人" },
  { maxGram: 250, text: "适合2-3人" },
  { maxGram: 500, text: "适合3-5人" },
  { maxGram: 750, text: "适合5-7人" },
  { maxGram: 1000, text: "适合8-10人" }
];

/**
 * 从商品参数展示文本中提取克数，支持 g、克、kg、千克和公斤。
 */
export function parseWeightGram(value: unknown): number | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  const match = value.trim().match(/(\d+(?:\.\d+)?)\s*(千克|公斤|kg|克|g)/i);
  if (!match) {
    return undefined;
  }
  const amount = Number(match[1]);
  const unit = match[2]?.toLowerCase();
  const gram = amount * (unit === "kg" || unit === "千克" || unit === "公斤" ? 1000 : 1);
  return Number.isFinite(gram) && gram > 0 ? gram : undefined;
}

/**
 * 将重量参数转换为卡片使用的建议人数文案。
 */
export function servingTextByWeight(value: unknown): string {
  const gram = parseWeightGram(value);
  if (gram === undefined) {
    return "";
  }
  return WEIGHT_SERVING_BANDS.find((band) => gram <= band.maxGram)?.text
    ?? "适合10人以上";
}
