export const ORDER_SEARCH_HISTORY_KEY = "order-search-history";
export const ORDER_SEARCH_HISTORY_LIMIT = 10;

export function normalizeOrderKeyword(value: unknown): string {
  return typeof value === "string"
    ? value.trim().replace(/\s+/g, " ").slice(0, 80)
    : "";
}

export function normalizeOrderRouteKeyword(value: unknown): string {
  if (typeof value !== "string") {
    return "";
  }
  try {
    return normalizeOrderKeyword(decodeURIComponent(value));
  } catch {
    return normalizeOrderKeyword(value);
  }
}

export function normalizeOrderSearchHistory(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const seen = new Set<string>();
  return value
    .map(normalizeOrderKeyword)
    .filter((keyword) => {
      const normalizedKey = keyword.toLocaleLowerCase();
      if (!keyword || seen.has(normalizedKey)) {
        return false;
      }
      seen.add(normalizedKey);
      return true;
    })
    .slice(0, ORDER_SEARCH_HISTORY_LIMIT);
}

export function addOrderSearchHistory(
  history: unknown,
  keyword: unknown
): string[] {
  const normalizedKeyword = normalizeOrderKeyword(keyword);
  if (!normalizedKeyword) {
    return normalizeOrderSearchHistory(history);
  }
  return normalizeOrderSearchHistory([
    normalizedKeyword,
    ...normalizeOrderSearchHistory(history).filter(
      (item) => item.toLocaleLowerCase() !== normalizedKeyword.toLocaleLowerCase()
    )
  ]);
}

export function buildOrderSearchResultUrl(keyword: unknown): string {
  const normalizedKeyword = normalizeOrderKeyword(keyword);
  if (!normalizedKeyword) {
    throw new Error("订单搜索关键词不能为空");
  }
  return `/pages/order/list/list?keyword=${encodeURIComponent(normalizedKeyword)}`;
}
