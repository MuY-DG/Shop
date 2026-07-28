import {
  normalizeProductKeyword,
  parsePositiveId
} from "../../../features/product-catalog";
import { enableNativeShareMenu } from "../../../utils/share";

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
    enableNativeShareMenu();
    this.setData({
      ready: true,
      initialCategoryId: parsePositiveId(options.categoryId),
      initialKeyword: normalizeProductKeyword(options.keyword)
    });
  },

  onShareAppMessage() {
    return {
      title: this.data.initialKeyword
        ? `灶香集｜${this.data.initialKeyword}`
        : "灶香集好物",
      path: "/pages/product/list/list"
    };
  },

  onShareTimeline() {
    return {
      title: this.data.initialKeyword
        ? `灶香集｜${this.data.initialKeyword}`
        : "灶香集好物"
    };
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
