export interface CommentListItem {
  id: number
  date: string
  content: string
  collection: number
  comment: number
  userName: string
}

export const commentList: CommentListItem[] = [
  {
    id: 1,
    date: '2024-09-03',
    content: '加油！学好 Node 自己写个小 Demo。',
    collection: 5,
    comment: 8,
    userName: '匿名'
  },
  {
    id: 2,
    date: '2024-09-04',
    content: '后台管理页面的交互细节越来越完整了。',
    collection: 12,
    comment: 3,
    userName: 'Ricky'
  },
  {
    id: 3,
    date: '2024-09-05',
    content: '商品目录如果支持更多筛选条件会更方便。',
    collection: 9,
    comment: 6,
    userName: 'Taylor'
  }
]
