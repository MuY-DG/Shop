export const API_ENDPOINTS = Object.freeze({
  auth: {
    login: "/app/auth/login",
    refresh: "/app/auth/refresh",
    phone: "/app/auth/phone",
    logout: "/app/auth/logout"
  },
  user: {
    me: "/app/users/me",
    overview: "/app/users/me/overview",
    avatar: "/app/users/me/avatar",
    avatarUploads: "/app/users/me/avatar/upload-sessions"
  },
  home: "/app/home",
  contact: "/app/contact",
  compliance: {
    merchant: "/app/compliance/merchant",
    currentDocument: (type: string): string =>
      `/app/compliance/documents/${type}/current`
  },
  accountRights: {
    list: "/app/account-rights/requests",
    detail: (requestId: string): string =>
      `/app/account-rights/requests/${requestId}`,
    withdraw: (requestId: string): string =>
      `/app/account-rights/requests/${requestId}/withdraw`
  },
  realtime: {
    ticket: "/app/realtime/tickets"
  },
  customerService: {
    presence: "/app/customer-service/presence",
    conversation: "/app/customer-service/conversation",
    open: "/app/customer-service/conversation/open",
    commonQuestions: "/app/customer-service/conversation/common-questions",
    messages: "/app/customer-service/conversation/messages",
    images: "/app/customer-service/conversation/images",
    imageUploads: "/app/customer-service/images/upload-sessions",
    orderCandidates: "/app/customer-service/conversation/order-candidates",
    order: (orderId: number): string =>
      `/app/customer-service/conversation/orders/${orderId}`,
    productCandidates: "/app/customer-service/conversation/product-candidates",
    product: (productId: number): string =>
      `/app/customer-service/conversation/products/${productId}`,
    image: (messageId: number): string =>
      `/app/customer-service/conversation/messages/${messageId}/image`,
    thumbnail: (messageId: number): string =>
      `/app/customer-service/conversation/messages/${messageId}/thumbnail`,
    imageAccess: (messageId: number): string =>
      `/app/customer-service/conversation/messages/${messageId}/image-access`
  },
  product: {
    categories: "/app/product/categories",
    list: "/app/product/spus",
    filterFacets: "/app/product/filter-facets",
    detail: (spuId: number): string => `/app/product/spus/${spuId}`,
    reviews: (spuId: number): string => `/app/product/spus/${spuId}/reviews`,
    reviewEligibility: (spuId: number): string =>
      `/app/product/spus/${spuId}/review-eligibility`,
    reviewImage: (orderItemId: number): string =>
      `/app/product/order-items/${orderItemId}/review-images`,
    reviewImageUploads: (orderItemId: number): string =>
      `/app/product/order-items/${orderItemId}/review-images/upload-sessions`
  },
  userProduct: {
    favorites: "/app/users/me/favorites",
    favoriteBatch: "/app/users/me/favorites/batch",
    favorite: (spuId: number): string => `/app/users/me/favorites/${spuId}`,
    browseHistory: "/app/users/me/browse-history",
    browseHistoryBatch: "/app/users/me/browse-history/batch",
    browseRecord: (spuId: number): string => `/app/users/me/browse-history/${spuId}`
  },
  cart: {
    items: "/app/cart/items",
    batchDelete: "/app/cart/items/batch",
    quantity: (cartItemId: number): string => `/app/cart/items/${cartItemId}/quantity`,
    item: (cartItemId: number): string => `/app/cart/items/${cartItemId}`
  },
  addresses: {
    list: "/app/addresses",
    item: (addressId: string): string => `/app/addresses/${addressId}`,
    setDefault: (addressId: string): string => `/app/addresses/${addressId}/default`
  },
  coupons: {
    available: "/app/coupons/available",
    claimable: "/app/coupons/claimable",
    mine: "/app/coupons/mine",
    claim: (templateId: number): string => `/app/coupons/templates/${templateId}/claim`
  },
  orders: {
    list: "/app/orders",
    preview: "/app/orders/preview",
    submit: "/app/orders",
    detail: (orderId: number): string => `/app/orders/${orderId}`,
    receiver: (orderId: number): string => `/app/orders/${orderId}/receiver`,
    delete: (orderId: number): string => `/app/orders/${orderId}`,
    pay: (orderId: number): string => `/app/orders/${orderId}/pay`,
    cancel: (orderId: number): string => `/app/orders/${orderId}/cancel`,
    paymentSync: (orderId: number): string => `/app/orders/${orderId}/payment/sync`,
    confirmReceipt: (orderId: number): string => `/app/orders/${orderId}/confirm-receipt`,
    waybillToken: (orderId: number): string =>
      `/app/orders/${orderId}/logistics/waybill-token`,
    tracking: (orderId: number): string => `/app/orders/${orderId}/logistics/tracking`,
    syncTracking: (orderId: number): string =>
      `/app/orders/${orderId}/logistics/tracking/sync`
  },
  afterSales: {
    list: "/app/after-sales",
    detail: (afterSaleId: number): string => `/app/after-sales/${afterSaleId}`,
    forOrder: (orderId: number): string => `/app/orders/${orderId}/after-sales`,
    eligibility: (orderId: number): string =>
      `/app/orders/${orderId}/after-sales/eligibility`,
    quote: (orderId: number): string => `/app/orders/${orderId}/after-sales/quote`,
    cancel: (afterSaleId: number): string => `/app/after-sales/${afterSaleId}/cancel`,
    returnShipment: (afterSaleId: number): string =>
      `/app/after-sales/${afterSaleId}/return-shipment`,
    evidence: (orderId: number): string => `/app/orders/${orderId}/after-sale-evidence`,
    evidenceUploads: (orderId: number): string =>
      `/app/orders/${orderId}/after-sale-evidence/upload-sessions`
  }
});
