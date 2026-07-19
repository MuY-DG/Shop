export const MAX_PRODUCT_SEARCH_KEYWORD_LENGTH = 80;

export function normalizeProductSearchKeyword(value: string): string {
  return value.trim().slice(0, MAX_PRODUCT_SEARCH_KEYWORD_LENGTH);
}

export function buildProductListFilters(
  categoryId: number,
  keyword: string
): { categoryId?: number; keyword?: string } {
  const normalizedKeyword = normalizeProductSearchKeyword(keyword);
  return {
    ...(Number.isSafeInteger(categoryId) && categoryId > 0 ? { categoryId } : {}),
    ...(normalizedKeyword ? { keyword: normalizedKeyword } : {})
  };
}
