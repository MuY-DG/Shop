import {
  buildAfterSaleDetailUrl,
  buildAfterSaleView,
  positiveAfterSaleId,
  type AfterSaleView
} from "../../../features/after-sale";
import { getAfterSales } from "../../../services/after-sale";
import { isApiError } from "../../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
    };
  };
}

interface RefreshOptions {
  silent?: boolean;
  suppressError?: boolean;
}

const PAGE_SIZE = 10;
let latestListRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

Page({
  data: {
    records: [] as AfterSaleView[],
    current: 1,
    total: 0,
    hasMore: false,
    loading: true,
    loadingMore: false,
    loaded: false,
    contentRefreshing: false,
    errorText: ""
  },

  onLoad() {
    void this.loadRecords(true);
  },

  onShow() {
    if (
      this.data.loaded
      && !this.data.loading
      && !this.data.loadingMore
      && !this.data.contentRefreshing
    ) {
      void this.loadRecords(true, { silent: true, suppressError: true });
    }
  },

  onUnload() {
    latestListRequest += 1;
  },

  async onContentRefresh() {
    if (this.data.contentRefreshing || this.data.loading || this.data.loadingMore) {
      return;
    }
    this.setData({ contentRefreshing: true });
    try {
      await this.loadRecords(true, { silent: true });
    } finally {
      this.setData({ contentRefreshing: false });
    }
  },

  onReachBottom() {
    if (this.data.hasMore && !this.data.loading && !this.data.loadingMore) {
      void this.loadRecords(false);
    }
  },

  onRetry() {
    void this.loadRecords(true);
  },

  async loadRecords(reset: boolean, options: RefreshOptions = {}) {
    if (reset && this.data.loading && this.data.loaded && !options.silent) {
      return;
    }
    const requestId = ++latestListRequest;
    const current = reset ? 1 : this.data.current + 1;
    const silent = reset && options.silent === true && this.data.loaded;
    if (silent) {
      if (!options.suppressError) {
        this.setData({ errorText: "" });
      }
    } else {
      this.setData(reset
        ? { loading: true, errorText: "" }
        : { loadingMore: true, errorText: "" });
    }
    try {
      const page = await getAfterSales(current, PAGE_SIZE);
      if (requestId !== latestListRequest) {
        return;
      }
      const incoming = (Array.isArray(page.records) ? page.records : []).map(buildAfterSaleView);
      const records = reset ? incoming : [...this.data.records, ...incoming];
      this.setData({
        records,
        current,
        total: Math.max(0, Number(page.total) || 0),
        hasMore: records.length < Math.max(0, Number(page.total) || 0),
        loading: false,
        loadingMore: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestListRequest) {
        if (silent && options.suppressError) {
          return;
        }
        this.setData({
          loading: false,
          loadingMore: false,
          loaded: this.data.records.length > 0,
          errorText: actionError(error, "售后记录加载失败，请稍后重试")
        });
      }
    }
  },

  onRecordTap(event: DatasetEvent) {
    const id = positiveAfterSaleId(event.currentTarget.dataset.id);
    if (id) {
      wx.navigateTo({ url: buildAfterSaleDetailUrl(id) });
    }
  }
});
