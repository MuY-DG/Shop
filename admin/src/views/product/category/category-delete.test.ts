import assert from 'node:assert/strict'
import test from 'node:test'
import { buildCategoryDeleteBlockedMessage } from './category-delete'

test('商品分类删除占用提示包含类型、数量和示例', () => {
  const message = buildCategoryDeleteBlockedMessage({
    categoryId: 12,
    categoryName: '茶饮',
    deletable: false,
    blockers: [
      {
        type: 'PRODUCT',
        label: '商品（含回收站和已清理记录）',
        count: 5,
        examples: ['绿茶 (#21)', '红茶 (#22)', '乌龙茶 (#23)']
      },
      {
        type: 'HOME_CATEGORY',
        label: '首页分类导航',
        count: 1,
        examples: ['配置 #8（ENABLED）']
      }
    ]
  })

  assert.match(message, /商品分类“茶饮”暂时无法删除/)
  assert.match(message, /商品（含回收站和已清理记录） 5 项：绿茶 \(#21\).*等/)
  assert.match(message, /首页分类导航 1 项：配置 #8/)
})
