import img1 from '@/assets/images/cover/img1.webp'
import img2 from '@/assets/images/cover/img2.webp'
import img3 from '@/assets/images/cover/img3.webp'
import img4 from '@/assets/images/cover/img4.webp'

export interface ArticleListItem {
  id: number
  home_img: string
  type_name: string
  title: string
  create_time: string
  count: number
}

export const ArticleList: ArticleListItem[] = [
  {
    id: 1,
    home_img: img1,
    type_name: '技术',
    title: 'Vue 3 组合式 API 实战笔记',
    create_time: '2024-09-03',
    count: 1280
  },
  {
    id: 2,
    home_img: img2,
    type_name: '设计',
    title: '后台系统表格体验优化思路',
    create_time: '2024-08-18',
    count: 936
  },
  {
    id: 3,
    home_img: img3,
    type_name: '产品',
    title: '从用户路径拆解商品目录',
    create_time: '2024-07-26',
    count: 752
  },
  {
    id: 4,
    home_img: img4,
    type_name: '运营',
    title: '内容运营中的数据复盘方法',
    create_time: '2024-06-12',
    count: 684
  }
]
