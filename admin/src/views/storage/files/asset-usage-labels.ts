const USAGE_TYPE_LABELS: Record<Api.Storage.UsageType, string> = {
  PRODUCT_CATEGORY_ICON: '商品分类图标',
  PRODUCT_SPU_MAIN: '商品主图',
  PRODUCT_SPU_GALLERY: '商品轮播图',
  PRODUCT_SPU_VIDEO: '商品视频',
  PRODUCT_SKU_IMAGE: '商品 SKU 图片',
  PRODUCT_SPEC_VALUE_IMAGE: '商品规格值图片',
  GUARANTEE_SERVICE_ICON: '商品保障服务图标',
  PRODUCT_DETAIL_HTML: '商品详情图片',
  RICH_TEXT_IMAGE: '富文本图片',
  HOME_BANNER: '首页轮播图',
  HOME_CATEGORY_IMAGE: '首页分类图片',
  HOME_PRODUCT_IMAGE: '首页商品展示图',
  ORDER_ITEM_SNAPSHOT: '订单商品快照',
  AFTER_SALE_EVIDENCE: '售后凭证',
  PAYMENT_CONFIG_CERT: '支付配置证书'
}

const OWNER_TYPE_LABELS: Record<Api.Storage.UsageOwnerType, string> = {
  PRODUCT_CATEGORY: '商品分类',
  PRODUCT_SPU: '商品',
  PRODUCT_SKU: '商品 SKU',
  PRODUCT_SPEC_VALUE: '商品规格值',
  GUARANTEE_SERVICE: '商品保障服务',
  HOME_BANNER: '首页轮播图',
  HOME_CATEGORY_ITEM: '首页分类项',
  HOME_PRODUCT_ITEM: '首页商品项',
  ORDER_ITEM: '订单商品项',
  AFTER_SALE: '售后单',
  PAYMENT_CONFIG: '支付配置'
}

export const formatAssetUsageType = (value?: string | null) =>
  value ? USAGE_TYPE_LABELS[value as Api.Storage.UsageType] || value : '-'

export const formatAssetUsageOwnerType = (value?: string | null) =>
  value ? OWNER_TYPE_LABELS[value as Api.Storage.UsageOwnerType] || value : '-'
