export const API_ENDPOINTS = Object.freeze({
  auth: {
    login: "/app/auth/login",
    refresh: "/app/auth/refresh",
    logout: "/app/auth/logout"
  },
  home: "/app/home",
  product: {
    categories: "/app/product/categories",
    list: "/app/product/spus",
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
    list: "/app/addresses"
  },
  coupons: {
    available: "/app/coupons/available"
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
  }
});
