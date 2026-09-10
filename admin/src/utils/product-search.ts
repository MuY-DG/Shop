export function formatProductSearchMatches(matches?: Api.Product.ProductSearchMatch[]): string {
  const labels = (Array.isArray(matches) ? matches : []).map((match) => {
    const label = match.specText?.trim() || match.skuCode
    const state =
      match.status !== 'ENABLED' ? '已停用' : match.saleState === 'SOLD_OUT' ? '缺货' : ''
    return `${label}${state ? `（${state}）` : ''}`
  })
  return labels.length
    ? `相关规格：${labels.slice(0, 2).join('；')}${labels.length > 2 ? ' 等' : ''}`
    : ''
}
