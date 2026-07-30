export const API_ENDPOINTS = Object.freeze({
  auth: {
    login: "/app/auth/login",
    refresh: "/app/auth/refresh",
    phone: "/app/auth/phone",
    logout: "/app/auth/logout"
  },
  user: {
    me: "/app/users/me",
    avatar: "/app/users/me/avatar"
  },
  home: "/app/home",
  contact: "/app/contact",
  realtime: {
    ticket: "/app/realtime/tickets"
  },
  customerService: {
    conversation: "/app/customer-service/conversation",
    open: "/app/customer-service/conversation/open",
    messages: "/app/customer-service/conversation/messages",
    images: "/app/customer-service/conversation/images",
    orderCandidates: "/app/customer-service/conversation/order-candidates",
    order: (orderId: number): string =>
      `/app/customer-service/conversation/orders/${orderId}`,
    productCandidates: "/app/customer-service/conversation/product-candidates",
    product: (productId: number): string =>
      `/app/customer-service/conversation/products/${productId}`,
    image: (messageId: number): string =>
      `/app/customer-service/conversation/messages/${messageId}/image`
  },
  product: {
    categories: "/app/product/categories",
    list: "/app/product/spus",
    filterFacets: "/app/product/filter-facets",
    detail: (spuId: number): string => `/app/product/spus/${spuId}`,
    reviews: (spuId: number): string => `/app/product/spus/${spuId}/reviews`,
    reviewEligibility: (spuId: number): string =>
      `/app/product/spus/${spuId}/review-eligibility`,
    review: (reviewId: number): string => `/app/product/reviews/${reviewId}`,
    myReviews: "/app/product/reviews/mine"
  },
  userProduct: {
    favorites: "/app/users/me/favorites",
    favorite: (spuId: number): string => `/app/users/me/favorites/${spuId}`,
    browseHistory: "/app/users/me/browse-history",
    browseRecord: (spuId: number): string => `/app/users/me/browse-history/${spuId}`
  },
  cart: {
    items: "/app/cart/items",
    quantity: (cartItemId: number): string => `/app/cart/items/${cartItemId}/quantity`,
    item: (cartItemId: number): string => `/app/cart/items/${cartItemId}`
  },
  addresses: {
    list: "/app/addresses",
    item: (addressId: string): string => `/app/addresses/${addressId}`,
    setDefault: (addressId: string): string => `/app/addresses/${addressId}/default`
  },
  location: {
    config: "/app/location/config"
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
    delete: (orderId: number): string => `/app/orders/${orderId}`,
    pay: (orderId: number): string => `/app/orders/${orderId}/pay`,
    cancel: (orderId: number): string => `/app/orders/${orderId}/cancel`,
    paymentSync: (orderId: number): string => `/app/orders/${orderId}/payment/sync`,
    confirmReceipt: (orderId: number): string => `/app/orders/${orderId}/confirm-receipt`
  },
  afterSales: {
    list: "/app/after-sales",
    detail: (afterSaleId: number): string => `/app/after-sales/${afterSaleId}`,
    forOrder: (orderId: number): string => `/app/orders/${orderId}/after-sales`,
    evidence: (orderId: number): string => `/app/orders/${orderId}/after-sale-evidence`
  }
});
