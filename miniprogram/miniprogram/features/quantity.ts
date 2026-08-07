const MAX_PURCHASE_QUANTITY = 999;

export interface QuantityInputResult {
  quantity: number;
  exceededStock: boolean;
}

function safeMaximum(value: unknown): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed)
    ? Math.min(MAX_PURCHASE_QUANTITY, Math.max(0, parsed))
    : 0;
}

export function normalizeQuantityInput(
  value: unknown,
  currentQuantity: unknown,
  maximum: unknown
): QuantityInputResult {
  const max = safeMaximum(maximum);
  if (max <= 0) {
    return { quantity: 0, exceededStock: false };
  }
  const rawText = typeof value === "string" ? value.trim() : String(value ?? "");
  const parsed = /^\d+$/.test(rawText) ? Number(rawText) : Number.NaN;
  const current = Number(currentQuantity);
  const fallback = Number.isSafeInteger(current) && current > 0
    ? Math.min(current, max)
    : 1;
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    return { quantity: fallback, exceededStock: false };
  }
  return {
    quantity: Math.min(parsed, max),
    exceededStock: parsed > max
  };
}

export function stockQuantityCorrectedMessage(maximum: number): string {
  return `库存不足，已修改为${Math.max(0, Math.floor(maximum))}`;
}
