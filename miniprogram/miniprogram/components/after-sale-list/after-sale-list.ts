import {
  buildAfterSaleApplyUrl,
  buildAfterSaleDetailUrl,
  buildAfterSaleView,
  positiveAfterSaleId,
  type AfterSaleView
} from "../../features/after-sale";
import { buildCustomerServiceUrl } from "../../features/customer-service";
import { deleteAfterSale, getAfterSales } from "../../services/after-sale";
import type { AfterSaleStatusGroup } from "../../types/after-sale";
import { isApiError } from "../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      group?: string;
      id?: number | string;
      itemId?: number | string;
      orderId?: number | string;
    };
  };
}

interface RefreshOptions {
  silent?: boolean;
  suppressError?: boolean;
}

const PAGE_SIZE = 10;
const STATUS_TABS: ReadonlyArray<{ value: AfterSaleStatusGroup; label: string }> = Object.freeze([
  { value: "PROCESSING", label: "处理中" },
  { value: "COMPLETED", label: "已完结" }
]);
let latestListRequest = 0;

function statusGroup(value: unknown): AfterSaleStatusGroup {
  return String(value).toUpperCase() === "COMPLETED" ? "COMPLETED" : "PROCESSING";
}

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function confirmDelete(): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title: "删除售后单",
      content: "删除后将不再在售后列表中显示，退款与审核记录不会被清除。",
      confirmText: "确认删除",
      confirmColor: "#B72B22",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    });
  });
}

Component({
  data: {
    tabs: STATUS_TABS,
    activeGroup: "PROCESSING" as AfterSaleStatusGroup,
    records: [] as AfterSaleView[],
    current: 1,
    total: 0,
    hasMore: false,
    loading: true,
    loadingMore: false,
    loaded: false,
    contentRefreshing: false,
    errorText: "",
    actionAfterSaleId: 0,
    openMenuAfterSaleId: 0
  },

  lifetimes: {
    attached() {
      void this.loadRecords(true);
    },
    detached() {
      latestListRequest += 1;
    }
  },

  pageLifetimes: {
    show() {
      if (
        this.data.loaded
        && !this.data.loading
        && !this.data.loadingMore
        && !this.data.contentRefreshing
        && !this.data.actionAfterSaleId
      ) {
        void this.loadRecords(true, { silent: true, suppressError: true });
      }
    }
  },

  methods: {
    onTabTap(event: DatasetEvent) {
      const group = statusGroup(event.currentTarget.dataset.group);
      if (
        group === this.data.activeGroup
        || this.data.loading
        || this.data.loadingMore
        || this.data.actionAfterSaleId
      ) {
        return;
      }
      this.setData({
        activeGroup: group,
        records: [],
        loaded: false,
        openMenuAfterSaleId: 0
      });
      void this.loadRecords(true);
    },

    async onContentRefresh() {
      if (this.data.contentRefreshing || this.data.loading || this.data.loadingMore) return;
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
      const requestId = ++latestListRequest;
      const current = reset ? 1 : this.data.current + 1;
      const silent = reset && options.silent === true && this.data.loaded;
      if (silent) {
        if (!options.suppressError) this.setData({ errorText: "" });
      } else {
        this.setData(reset
          ? { loading: true, loadingMore: false, errorText: "" }
          : { loadingMore: true, errorText: "" });
      }
      try {
        const page = await getAfterSales(current, PAGE_SIZE, undefined, this.data.activeGroup);
        if (requestId !== latestListRequest) return;
        const incoming = (Array.isArray(page.records) ? page.records : []).map(buildAfterSaleView);
        const records = reset ? incoming : [...this.data.records, ...incoming];
        const total = Math.max(0, Number(page.total) || 0);
        this.setData({
          records,
          current: Number(page.current) || current,
          total,
          hasMore: records.length < total,
          loading: false,
          loadingMore: false,
          loaded: true,
          errorText: ""
        });
      } catch (error) {
        if (requestId !== latestListRequest || silent && options.suppressError) return;
        this.setData({
          loading: false,
          loadingMore: false,
          loaded: this.data.records.length > 0,
          errorText: actionError(error, "售后记录加载失败，请稍后重试")
        });
      }
    },

    onRecordTap(event: DatasetEvent) {
      const afterSaleId = positiveAfterSaleId(event.currentTarget.dataset.id);
      if (afterSaleId) wx.navigateTo({ url: buildAfterSaleDetailUrl(afterSaleId) });
    },

    onItemImageError(event: DatasetEvent) {
      const afterSaleId = positiveAfterSaleId(event.currentTarget.dataset.id);
      const afterSaleItemId = positiveAfterSaleId(event.currentTarget.dataset.itemId);
      if (!afterSaleId || !afterSaleItemId) return;
      this.setData({
        records: this.data.records.map((record) => record.id === afterSaleId
          ? {
            ...record,
            items: record.items.map((item) => item.id === afterSaleItemId
              ? { ...item, hasImage: false, imageUrl: "" }
              : item)
          }
          : record)
      });
    },

    onMoreTap(event: DatasetEvent) {
      const afterSaleId = positiveAfterSaleId(event.currentTarget.dataset.id);
      const record = this.data.records.find((item) => item.id === afterSaleId);
      if (!record?.canDelete || this.data.actionAfterSaleId) return;
      this.setData({
        openMenuAfterSaleId: this.data.openMenuAfterSaleId === afterSaleId ? 0 : afterSaleId
      });
    },

    onDeleteTap(event: DatasetEvent) {
      const afterSaleId = positiveAfterSaleId(event.currentTarget.dataset.id);
      const record = this.data.records.find((item) => item.id === afterSaleId);
      if (record?.canDelete) void this.deleteRecord(afterSaleId);
    },

    async deleteRecord(afterSaleId: number) {
      this.setData({ openMenuAfterSaleId: 0 });
      if (this.data.actionAfterSaleId || !await confirmDelete()) return;
      this.setData({ actionAfterSaleId: afterSaleId });
      try {
        await deleteAfterSale(afterSaleId);
        wx.showToast({ title: "售后单已删除", icon: "success" });
        await this.loadRecords(true, { silent: true });
      } catch (error) {
        wx.showToast({
          title: actionError(error, "售后单删除失败"),
          icon: "none"
        });
      } finally {
        this.setData({ actionAfterSaleId: 0 });
      }
    },

    onCustomerServiceTap(event: DatasetEvent) {
      const orderId = positiveAfterSaleId(event.currentTarget.dataset.orderId);
      if (!orderId || this.data.actionAfterSaleId) return;
      wx.navigateTo({ url: buildCustomerServiceUrl("ORDER", orderId) });
    },

    onReapplyTap(event: DatasetEvent) {
      const afterSaleId = positiveAfterSaleId(event.currentTarget.dataset.id);
      const record = this.data.records.find((item) => item.id === afterSaleId);
      if (!record?.canReapply || this.data.actionAfterSaleId) return;
      wx.navigateTo({ url: buildAfterSaleApplyUrl(record.orderId) });
    }
  }
});
