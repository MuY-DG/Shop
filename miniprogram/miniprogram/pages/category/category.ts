import { parsePositiveId } from "../../features/product-catalog";
import { enableNativeShareMenu } from "../../utils/share";
import {
  refreshCustomTabBarCartCount,
  syncCustomTabBar
} from "../../utils/tab-bar";

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
    enableNativeShareMenu();
    syncCustomTabBar(this, 1);
  },

  onShareAppMessage() {
    return {
      title: "灶香集好物分类",
      path: "/pages/category/category"
    };
  },

  onShareTimeline() {
    return {
      title: "灶香集好物分类"
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

  onCartChange() {
    void refreshCustomTabBarCartCount(this);
  },

  catalog(): CatalogBrowserInstance | undefined {
    return this.selectComponent("#catalog") as unknown as CatalogBrowserInstance | undefined;
  }
});
