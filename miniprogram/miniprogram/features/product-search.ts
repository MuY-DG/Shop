import { normalizeProductKeyword } from "./product-catalog";

export const PRODUCT_SEARCH_HISTORY_KEY = "product-search-history";
export const PRODUCT_SEARCH_HISTORY_LIMIT = 10;

export function normalizeProductSearchHistory(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const seen = new Set<string>();
  return value
    .map(normalizeProductKeyword)
    .filter((keyword) => {
      const normalizedKey = keyword.toLocaleLowerCase();
      if (!keyword || seen.has(normalizedKey)) {
        return false;
      }
      seen.add(normalizedKey);
      return true;
    })
    .slice(0, PRODUCT_SEARCH_HISTORY_LIMIT);
}

export function addProductSearchHistory(
  history: unknown,
  keyword: unknown
): string[] {
  const normalizedKeyword = normalizeProductKeyword(keyword);
  if (!normalizedKeyword) {
    return normalizeProductSearchHistory(history);
  }
  return normalizeProductSearchHistory([
    normalizedKeyword,
    ...normalizeProductSearchHistory(history).filter(
      (item) => item.toLocaleLowerCase() !== normalizedKeyword.toLocaleLowerCase()
    )
  ]);
}
