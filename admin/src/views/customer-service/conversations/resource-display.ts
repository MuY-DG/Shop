export const resourceMoney = (value?: number | null) => `¥${((value ?? 0) / 100).toFixed(2)}`

export const resourceStatus = (status: string) => {
  const labels: Record<string, string> = {
    CREATED: '待付款',
    PAID: '待发货',
    PARTIALLY_SHIPPED: '部分发货',
    SHIPPED: '待收货',
    COMPLETED: '已完成',
    CLOSED: '已关闭',
    REFUNDING: '退款处理中',
    REFUNDED: '已退款',
    REQUESTED: '待审核',
    APPROVED: '审核已通过',
    REJECTED: '申请未通过',
    WAITING_RETURN: '待寄回商品',
    RETURNING: '退货运输中',
    WAITING_INSPECTION: '待验收',
    RETURN_REJECTED: '验收未通过',
    REFUND_FAILED: '退款异常',
    CANCELLED: '已取消',
    ON_SALE: '在售',
    OFF_SALE: '已下架',
    DRAFT: '未上架',
    ENABLED: '可售',
    DISABLED: '已停用'
  }
  return labels[status] || status
}

export const resourceProductPrice = (product: Api.CustomerService.LinkedProduct) => {
  if (product.minPriceCent == null) return '价格待确认'
  return product.maxPriceCent && product.maxPriceCent !== product.minPriceCent
    ? `${resourceMoney(product.minPriceCent)}–${resourceMoney(product.maxPriceCent)}`
    : resourceMoney(product.minPriceCent)
}
