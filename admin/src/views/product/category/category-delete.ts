export function buildCategoryDeleteBlockedMessage(
  impact: Api.Product.CategoryDeleteImpact
): string {
  const blockerLines = impact.blockers.map((blocker) => {
    const examples = blocker.examples.length ? `：${blocker.examples.join('、')}` : ''
    const remaining = blocker.count > blocker.examples.length ? '等' : ''
    return `• ${blocker.label} ${blocker.count} 项${examples}${remaining}`
  })
  return [
    `商品分类“${impact.categoryName}”暂时无法删除，请先处理以下占用项：`,
    '',
    ...blockerLines
  ].join('\n')
}
