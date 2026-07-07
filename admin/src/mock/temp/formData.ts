import avatar1 from '@/assets/images/avatar/avatar1.webp'
import avatar2 from '@/assets/images/avatar/avatar2.webp'
import avatar3 from '@/assets/images/avatar/avatar3.webp'
import avatar4 from '@/assets/images/avatar/avatar4.webp'

export interface AccountTableDataItem {
  id: number
  avatar: string
}

export interface RoleListDataItem {
  roleCode: string
  roleName: string
}

export const ACCOUNT_TABLE_DATA: AccountTableDataItem[] = [
  {
    id: 1,
    avatar: avatar1
  },
  {
    id: 2,
    avatar: avatar2
  },
  {
    id: 3,
    avatar: avatar3
  },
  {
    id: 4,
    avatar: avatar4
  }
]

export const ROLE_LIST_DATA: RoleListDataItem[] = [
  {
    roleCode: 'admin',
    roleName: '管理员'
  },
  {
    roleCode: 'operator',
    roleName: '运营'
  },
  {
    roleCode: 'viewer',
    roleName: '访客'
  }
]
