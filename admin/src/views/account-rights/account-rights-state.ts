export const requestTypeLabel = (type: Api.AccountRights.RequestType) =>
  ({
    ACCOUNT_CANCELLATION: '注销账户',
    PERSONAL_INFORMATION_DELETION: '删除个人信息',
    ACCESS_COPY: '查阅/复制个人信息',
    CORRECTION: '更正个人信息'
  })[type]

export const requestStatusLabel = (status: Api.AccountRights.RequestStatus) =>
  ({
    PENDING: '待处理',
    IN_REVIEW: '审核中',
    APPROVED: '已批准',
    REJECTED: '已拒绝',
    WITHDRAWN: '用户已撤回',
    COMPLETED: '已完成'
  })[status]

export const requestStatusTone = (status: Api.AccountRights.RequestStatus) =>
  ({
    PENDING: 'warning',
    IN_REVIEW: 'primary',
    APPROVED: 'success',
    REJECTED: 'danger',
    WITHDRAWN: 'info',
    COMPLETED: 'success'
  })[status] as 'warning' | 'primary' | 'success' | 'danger' | 'info'

export const availableAdminActions = (
  status: Api.AccountRights.RequestStatus
): Api.AccountRights.AdminAction[] => {
  if (status === 'PENDING') return ['review', 'reject']
  if (status === 'IN_REVIEW') return ['approve', 'reject']
  if (status === 'APPROVED') return ['complete']
  return []
}

export const actionLabel = (action: Api.AccountRights.AdminAction) =>
  ({ review: '进入审核', reject: '拒绝申请', approve: '批准申请', complete: '确认完成' })[action]

export const validateActionForm = (form: Api.AccountRights.ActionForm): string | null => {
  if (!form.reason.trim()) return '请填写本次处理原因'
  if (!form.retentionExplanation.trim()) return '请填写数据保留或删除说明'
  return null
}
