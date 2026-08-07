import {
  addOrderSearchHistory,
  buildOrderSearchResultUrl,
  normalizeOrderKeyword,
  normalizeOrderRouteKeyword,
  normalizeOrderSearchHistory,
  ORDER_SEARCH_HISTORY_KEY
} from "../../../features/order-search";

interface SearchPageOptions {
  keyword?: string;
}

interface InputEvent {
  detail: {
    value: string;
  };
}

interface HistoryTapEvent {
  currentTarget: {
    dataset: {
      keyword?: string;
    };
  };
}

function readSearchHistory(): string[] {
  try {
    return normalizeOrderSearchHistory(wx.getStorageSync(ORDER_SEARCH_HISTORY_KEY));
  } catch {
    return [];
  }
}

Page({
  data: {
    keyword: "",
    history: [] as string[]
  },

  onLoad(options: SearchPageOptions) {
    this.setData({
      keyword: normalizeOrderRouteKeyword(options.keyword),
      history: readSearchHistory()
    });
  },

  onShow() {
    this.setData({ history: readSearchHistory() });
  },

  onInput(event: InputEvent) {
    this.setData({ keyword: event.detail.value });
  },

  onConfirm(event: InputEvent) {
    this.submitSearch(event.detail.value);
  },

  onSearchTap() {
    this.submitSearch(this.data.keyword);
  },

  onClearInput() {
    this.setData({ keyword: "" });
  },

  onHistoryTap(event: HistoryTapEvent) {
    this.submitSearch(event.currentTarget.dataset.keyword);
  },

  onClearHistory() {
    wx.showModal({
      title: "清空最近搜索",
      content: "确认清空全部订单搜索记录吗？",
      confirmColor: "#B72B22",
      success: (result) => {
        if (!result.confirm) {
          return;
        }
        try {
          wx.removeStorageSync(ORDER_SEARCH_HISTORY_KEY);
        } catch {
          // The page state can still be cleared if local storage is unavailable.
        }
        this.setData({ history: [] });
      }
    });
  },

  submitSearch(value: unknown) {
    const keyword = normalizeOrderKeyword(value);
    if (!keyword) {
      wx.showToast({ title: "请输入搜索内容", icon: "none" });
      return;
    }
    const history = addOrderSearchHistory(readSearchHistory(), keyword);
    try {
      wx.setStorageSync(ORDER_SEARCH_HISTORY_KEY, history);
    } catch {
      // Search should remain available even when local storage is full.
    }
    this.setData({ keyword, history });
    wx.redirectTo({ url: buildOrderSearchResultUrl(keyword) });
  }
});
