export function preserveCustomerServicePrependScrollTop(
  currentScrollTop: number,
  anchorTopBefore: number,
  anchorTopAfter: number
): number {
  if (
    !Number.isFinite(currentScrollTop) ||
    !Number.isFinite(anchorTopBefore) ||
    !Number.isFinite(anchorTopAfter)
  ) {
    return Math.max(0, Number.isFinite(currentScrollTop) ? currentScrollTop : 0)
  }
  return Math.max(0, currentScrollTop + anchorTopAfter - anchorTopBefore)
}
