import type { ProductSku, WholesaleTier } from "../types/api";

export interface WholesalePriceResolution {
  unitPriceCent: number;
  appliedTier: WholesaleTier | null;
  nextTier: WholesaleTier | null;
  quantityNeeded: number | null;
}

export function resolveWholesalePrice(
  sku: Pick<ProductSku, "priceCent" | "wholesaleTiers">,
  quantity: number
): WholesalePriceResolution {
  const normalizedQuantity = Math.max(1, Math.trunc(quantity));
  const tiers = [...(sku.wholesaleTiers || [])]
    .filter((tier) => tier.minQuantity >= 2 && tier.unitPriceCent > 0)
    .sort((left, right) => left.minQuantity - right.minQuantity);
  let appliedTier: WholesaleTier | null = null;
  let nextTier: WholesaleTier | null = null;

  for (const tier of tiers) {
    if (tier.minQuantity <= normalizedQuantity) {
      appliedTier = tier;
    } else {
      nextTier = tier;
      break;
    }
  }

  return {
    unitPriceCent: appliedTier?.unitPriceCent ?? sku.priceCent,
    appliedTier,
    nextTier,
    quantityNeeded: nextTier ? nextTier.minQuantity - normalizedQuantity : null
  };
}
