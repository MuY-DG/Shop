import { parsePositiveId } from "../../features/product-catalog";
import { syncCustomTabBar } from "../../utils/tab-bar";

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
  onShow() {
    syncCustomTabBar(this, 1);
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
