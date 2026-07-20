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
    detail: (spuId: number): string => `/app/product/spus/${spuId}`
  },
  cart: {
    items: "/app/cart/items"
  }
});
