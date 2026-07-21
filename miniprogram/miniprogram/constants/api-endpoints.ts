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
    items: "/app/cart/items"
  }
});
