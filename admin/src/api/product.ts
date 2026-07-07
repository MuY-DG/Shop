import request from '@/utils/http'

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
    data,
    showSuccessMessage: true
  })
}

export function updateProductSpu(spuId: number, data: Api.Product.SpuForm) {
  return request.put<void>({
    url: `/admin/product/spus/${spuId}`,
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
