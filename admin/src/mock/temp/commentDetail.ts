import { ref, type Ref } from 'vue'

export interface Comment {
  id: number
  author: string
  content: string
  timestamp: string
  replies: Comment[]
}

export const commentList: Ref<Comment[]> = ref([
  {
    id: 1,
    author: 'Ricky',
    content: '这个商品目录页面的交互很清晰，继续保持。',
    timestamp: '2024-09-03T09:30:00.000Z',
    replies: [
      {
        id: 11,
        author: 'Art Design',
        content: '收到，后续会继续补齐列表和编辑体验。',
        timestamp: '2024-09-03T10:12:00.000Z',
        replies: []
      }
    ]
  },
  {
    id: 2,
    author: 'Taylor',
    content: '希望后面能看到更多真实业务场景示例。',
    timestamp: '2024-09-04T14:08:00.000Z',
    replies: []
  }
])
