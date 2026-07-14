import request from '@/utils/http'

export { centToYuan, toNullableNumber, yuanToCent } from '@/utils/product-number'

export function fetchProductCategories() {
  return request.get<Api.Product.Category[]>({
    url: '/admin/product/categories'
  })
}

export function createProductCategory(data: Api.Product.CategoryForm) {
  return request.post<number>({
    url: '/admin/product/categories',
    data,
    showSuccessMessage: true
  })
}

export function updateProductCategory(categoryId: number, data: Api.Product.CategoryForm) {
  return request.put<void>({
    url: `/admin/product/categories/${categoryId}`,
    data,
    showSuccessMessage: true
  })
}

export function fetchProductSpus(params: Api.Product.SpuSearchParams) {
  return request.get<Api.Product.SpuList>({
    url: '/admin/product/spus',
    params
  })
}

export function fetchProductSpuDetail(spuId: number) {
  return request.get<Api.Product.SpuDetail>({
    url: `/admin/product/spus/${spuId}`
  })
}

export function createProductSpu(data: Api.Product.SpuForm) {
  return request.post<number>({
    url: '/admin/product/spus',
    data
  })
}

export function updateProductSpu(spuId: number, data: Api.Product.SpuForm) {
  return request.put<void>({
    url: `/admin/product/spus/${spuId}`,
    data
  })
}

export function deleteProductSpu(spuId: number) {
  return request.del<void>({
    url: `/admin/product/spus/${spuId}`,
    showSuccessMessage: true
  })
}

export function restoreProductSpu(spuId: number) {
  return request.post<void>({
    url: `/admin/product/spus/${spuId}/restore`,
    showSuccessMessage: true
  })
}

export function purgeProductSpu(spuId: number, data: Api.Product.SpuPurgeForm) {
  return request.post<void>({
    url: `/admin/product/spus/${spuId}/purge`,
    data,
    showSuccessMessage: true
  })
}

export function publishProductSpu(spuId: number) {
  return request.post<void>({
    url: `/admin/product/spus/${spuId}/publish`,
    showSuccessMessage: true
  })
}

export function unpublishProductSpu(spuId: number) {
  return request.post<void>({
    url: `/admin/product/spus/${spuId}/unpublish`,
    showSuccessMessage: true
  })
}

export function adjustSkuStock(skuId: number, data: Api.Product.StockAdjustmentForm) {
  return request.post<void>({
    url: `/admin/product/skus/${skuId}/stock-adjustments`,
    data,
    showSuccessMessage: true
  })
}

export function fetchProductSpecTemplates() {
  return request.get<Api.Product.SpecTemplateSummary[]>({
    url: '/admin/product/spec-templates'
  })
}

export function fetchProductSpecTemplateDetail(templateId: number) {
  return request.get<Api.Product.SpecTemplateDetail>({
    url: `/admin/product/spec-templates/${templateId}`
  })
}

export function createProductSpecTemplate(data: Api.Product.SpecTemplateForm) {
  return request.post<number>({
    url: '/admin/product/spec-templates',
    data,
    showSuccessMessage: true
  })
}

export function updateProductSpecTemplate(templateId: number, data: Api.Product.SpecTemplateForm) {
  return request.put<void>({
    url: `/admin/product/spec-templates/${templateId}`,
    data,
    showSuccessMessage: true
  })
}

export function saveSpuSpecTemplate(spuId: number, data: Api.Product.SpecTemplateSaveForm) {
  return request.post<number>({
    url: `/admin/product/spus/${spuId}/spec-template`,
    data,
    showSuccessMessage: true
  })
}

export function fetchProductGuaranteeServices(
  params: Api.Product.GuaranteeServiceSearchParams = {}
) {
  return request.get<Api.Product.GuaranteeServiceList>({
    url: '/admin/product/guarantee-services',
    params
  })
}

export function createProductGuaranteeService(data: Api.Product.GuaranteeServiceForm) {
  return request.post<number>({
    url: '/admin/product/guarantee-services',
    data,
    showSuccessMessage: true
  })
}

export function updateProductGuaranteeService(
  serviceId: number,
  data: Api.Product.GuaranteeServiceForm
) {
  return request.put<void>({
    url: `/admin/product/guarantee-services/${serviceId}`,
    data,
    showSuccessMessage: true
  })
}

export function updateProductGuaranteeServiceVisibility(
  serviceId: number,
  data: Api.Product.GuaranteeServiceVisibilityForm
) {
  return request.post<void>({
    url: `/admin/product/guarantee-services/${serviceId}/visibility`,
    data,
    showSuccessMessage: true
  })
}

export function deleteProductGuaranteeService(serviceId: number) {
  return request.del<void>({
    url: `/admin/product/guarantee-services/${serviceId}`,
    showSuccessMessage: true
  })
}

export function fetchProductFreightTemplates() {
  return request.get<Api.Product.FreightTemplate[]>({
    url: '/admin/product/freight-templates'
  })
}

export function createProductFreightTemplate(data: Api.Product.FreightTemplateForm) {
  return request.post<number>({
    url: '/admin/product/freight-templates',
    data,
    showSuccessMessage: true
  })
}

export function updateProductFreightTemplate(
  templateId: number,
  data: Api.Product.FreightTemplateForm
) {
  return request.put<void>({
    url: `/admin/product/freight-templates/${templateId}`,
    data,
    showSuccessMessage: true
  })
}

export function fetchProductSpuCoupons(spuId: number) {
  return request.get<Api.Marketing.CouponTemplate[]>({
    url: `/admin/product/spus/${spuId}/coupons`
  })
}

export function bindProductSpuCoupons(spuId: number, data: Api.Product.ProductCouponBindingForm) {
  return request.put<void>({
    url: `/admin/product/spus/${spuId}/coupons`,
    data
  })
}

export function createProductSpuCoupon(spuId: number, data: Api.Product.ProductCouponCreateForm) {
  return request.post<number>({
    url: `/admin/product/spus/${spuId}/coupons`,
    data,
    showSuccessMessage: true
  })
}
