import assert from 'node:assert/strict'
import test from 'node:test'
import { buildHomeCategoryOptions } from './home-category-options'

const category = (id: number, parentId: number, name: string): Api.Content.HomeCategoryOption => ({
  id,
  parentId,
  name,
  icon: ''
})

test('一级分类隐藏后，启用的二级分类仍可用于首页分类和轮播图跳转', () => {
  const options = buildHomeCategoryOptions([category(11, 1, '火锅底料'), category(12, 1, '蘸料')])

  assert.deepEqual(
    options.map(({ value, label }) => ({ value, label })),
    [
      { value: 11, label: '火锅底料' },
      { value: 12, label: '蘸料' }
    ]
  )
  assert.ok(options.every((option) => !option.disabled))
})

test('保留可见分类的层级和接口顺序，同时保留隐藏父分类下的分支', () => {
  const options = buildHomeCategoryOptions([
    category(2, 0, '饮品'),
    category(12, 1, '蘸料'),
    category(11, 1, '火锅底料'),
    category(22, 2, '茶饮'),
    category(21, 2, '果汁'),
    category(111, 11, '清汤底料')
  ])

  assert.deepEqual(
    options.map((option) => option.value),
    [2, 12, 11]
  )
  assert.deepEqual(
    options[0].children?.map((option) => option.value),
    [22, 21]
  )
  assert.equal(options[2].children?.[0].value, 111)
  assert.equal(options[1].children, undefined)
})

test('隐藏父分类下的分类仍遵守首页去重规则，正在编辑的分类可以继续选择', () => {
  const usedCategoryIds = new Set([11, 12])
  const editingCategoryId = 12
  const options = buildHomeCategoryOptions(
    [category(11, 1, '火锅底料'), category(12, 1, '蘸料'), category(13, 1, '配菜')],
    (id) => usedCategoryIds.has(id) && id !== editingCategoryId
  )

  assert.deepEqual(
    options.map((option) => option.disabled),
    [true, false, false]
  )
})
