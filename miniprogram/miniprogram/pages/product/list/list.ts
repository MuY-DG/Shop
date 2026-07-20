import {
  normalizeProductKeyword,
  parsePositiveId
} from "../../../features/product-catalog";

interface PageOptions {
  categoryId?: string;
  keyword?: string;
}

interface CatalogBrowserInstance {
  refresh(): Promise<void>;
  loadMore(): Promise<void>;
}

interface ProductSelectEvent {
  detail: {
    spuId?: number | string;
  };
}

Page({
  data: {
    ready: false,
    initialCategoryId: 0,
    initialKeyword: ""
  },

  onLoad(options: PageOptions) {
    this.setData({
      ready: true,
      initialCategoryId: parsePositiveId(options.categoryId),
      initialKeyword: normalizeProductKeyword(options.keyword)
    });
  },

  async onPullDownRefresh() {
    await this.catalog()?.refresh();
    wx.stopPullDownRefresh();
  },

  onReachBottom() {
    void this.catalog()?.loadMore();
  },

  onProductSelect(event: ProductSelectEvent) {
    const spuId = parsePositiveId(event.detail.spuId);
    if (spuId) {
      wx.navigateTo({ url: `/pages/product/detail/detail?id=${spuId}` });
    }
  },

  catalog(): CatalogBrowserInstance | undefined {
    return this.selectComponent("#catalog") as unknown as CatalogBrowserInstance | undefined;
  }
});
