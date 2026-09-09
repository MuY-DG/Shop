export interface HomeCategoryTreeOption {
  [key: string]: unknown
  value: number
  label: string
  disabled?: boolean
  children?: HomeCategoryTreeOption[]
}

export function buildHomeCategoryOptions(
  categories: Api.Content.HomeCategoryOption[],
  isDisabled?: (categoryId: number) => boolean
): HomeCategoryTreeOption[] {
  const nodes = new Map<number, HomeCategoryTreeOption>(
    categories.map((category) => [
      category.id,
      {
        value: category.id,
        label: category.name,
        disabled: isDisabled?.(category.id)
      }
    ])
  )
  const roots: HomeCategoryTreeOption[] = []

  categories.forEach((category) => {
    const node = nodes.get(category.id)!
    const parent = nodes.get(category.parentId)
    // 接口只返回启用的分类；父分类隐藏时，子分类仍需作为可选入口保留。
    if (parent) {
      parent.children ??= []
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  })

  return roots
}
